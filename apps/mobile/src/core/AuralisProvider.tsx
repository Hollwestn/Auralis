import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Alert, AppState, Platform } from 'react-native';
import { File } from 'expo-file-system';
import * as DocumentPicker from 'expo-document-picker';
import { randomUUID, getRandomValues } from 'expo-crypto';
import { requireNativeModule } from 'expo';
import { normalizeAudioPreferences, defaultAudioPreferences, normalizationGainDb, albumKey, type AudioPreferences } from './audioPreferences';
import { setAudioModeAsync, useAudioPlaylist, useAudioPlaylistStatus } from 'expo-audio';
import { AuralisApi, ApiError } from './api';
import { storage } from './storage';
import { readLocalMetadata } from './metadata';
import { artworkSource } from './artwork';
import { createBackup, inspectBackup, extractBackup, type BackupPreview } from './backupIO';
import { mergeBackup } from './backupFormat';
import { moveQueue, insertQueue, removeQueue } from './queue';
import { normalizeTheme, themes } from './theme';
import { updateNowPlaying } from '../widgets/NowPlaying';
import { setNativeLoop, setNativeRate, setNativeVolume, setNativeRepeat } from './nativeAudio';
import { acceptSpectrum, audioTelemetry, clearSpectrum, type SpectrumFrame } from './spectrum';
import { visualModes, normalizeVisualPreferences, type VisualPreferences } from '../visuals/shaders';
import { decodeSettings, defaultExperience, encodeSettings, type Experience } from './settings';
import { isLocalSession, localSession, localAudioInfo, localTrackId, localWave, reconcileLocalLibrary, withoutLocalTrack } from './local';
import type { CachedLibrary, HistoryEntry, OfflineTrack, Playback, Playlist, Preferences, Session, SyncedState, ThemeName, Track, WaveEntry } from './types';

const empty: CachedLibrary = { tracks: [], favorites: [], playlists: [], history: [], preferences: { genres: [], artists: [] } };
type PendingHistory = Omit<HistoryEntry, 'id'>;

function useAuralisStore() {
  const [session, setSession] = useState<Session | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [systemReducedMotion,setSystemReducedMotion]=useState(false);
  useEffect(()=>{void AccessibilityInfo.isReduceMotionEnabled().then(setSystemReducedMotion);const listener=AccessibilityInfo.addEventListener('reduceMotionChanged',setSystemReducedMotion);return()=>listener.remove();},[]);
  const [library, setLibraryState] = useState<CachedLibrary>(empty);
  const libraryRef = useRef<CachedLibrary>(empty);
  const [downloads, setDownloads] = useState<OfflineTrack[]>([]);
  const [theme, setTheme] = useState<ThemeName>('ObsidianYellow');
  const [visuals, setVisuals] = useState<VisualPreferences>(() => normalizeVisualPreferences(null));
  const [experience, setExperience] = useState<Experience>(defaultExperience);
  const [audioPreferences,setAudioPreferences]=useState<AudioPreferences>(defaultAudioPreferences);
  const [analysisProgress,setAnalysisProgress]=useState<{done:number;total:number;failed:number}|null>(null);
  const analysisActive=useRef(false);
  const [shuffleEnabled, setShuffleEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [online, setOnline] = useState(false);
  const [notice, setNotice] = useState('');
  const [queue, setQueue] = useState<Track[]>([]);
  const [current, setCurrent] = useState<Track | null>(null);
  const [waveEntries, setWaveEntries] = useState<WaveEntry[]>([]);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const importLock = useRef(false);
  const [remotePlayback, setRemotePlayback] = useState<Playback | null>(null);
  const [sleepDeadline, setSleepDeadline] = useState(0);
  const [sleepRemaining, setSleepRemaining] = useState(0);
  const [backupPreview, setBackupPreview] = useState<BackupPreview | null>(null);
  const [libraryWorking, setLibraryWorking] = useState(false);
  const [queueRevision, setQueueRevision] = useState(0);
  const [canUndoQueue,setCanUndoQueue]=useState(false);
  const queueUndo = useRef<({ type: 'remove'; track: Track; index: number } | { type: 'move'; from: number; to: number })[]>([]);
  const player = useAudioPlaylist({ sources: [], updateInterval: 1000, loop: 'none' });
  const status = useAudioPlaylistStatus(player);
  const setSleepTimer = useCallback(async (minutes: number) => {
    if (![0, 5, 15, 30, 60].includes(minutes)) throw new Error('Выберите время таймера.');
    const native = player as unknown as { setAuralisSleepTimer?: (seconds: number) => Promise<void> };
    if (!native.setAuralisSleepTimer) throw new Error('Таймер сна доступен в новой нативной сборке приложения.');
    await native.setAuralisSleepTimer(minutes * 60);
    setSleepDeadline(minutes ? Date.now() + minutes * 60_000 : 0);
    setSleepRemaining(minutes * 60);
  }, [player]);
  useEffect(() => {
    if (!sleepDeadline) return;
    const update = () => { const remaining = Math.max(0, Math.ceil((sleepDeadline - Date.now()) / 1000)); setSleepRemaining(remaining); if (!remaining) setSleepDeadline(0); };
    const tick = setInterval(update, 1000), resume = AppState.addEventListener('change', update);
    return () => { clearInterval(tick); resume.remove(); };
  }, [sleepDeadline]);
  const localMode = isLocalSession(session);
  const api = useMemo(() => session && !isLocalSession(session) ? new AuralisApi(session.server, session.token) : null, [session]);
  const version = useRef<SyncedState | null>(null);
  const pendingHistory = useRef<PendingHistory[]>([]);
  const historySending = useRef(false);
  const syncConflict = useRef(false);
  const sessionRef = useRef<Session | null>(null);
  const cacheOwner = useRef('');
  const setLibrary = useCallback((update: React.SetStateAction<CachedLibrary>) => {
    const value = typeof update === 'function' ? update(libraryRef.current) : update;
    libraryRef.current = value; setLibraryState(value);
    const owner = sessionRef.current;
    if (owner && cacheOwner.current === `${owner.server}|${owner.user.id}`) storage.saveLibrary(owner, value);
  }, []);
  const queueRef = useRef<Track[]>([]);
  const activeTrack = useRef<Track | null>(null);
  const activeQueueIndex = useRef(0);
  const manualSkip = useRef(false);
  const listened = useRef(0);
  const lastPosition = useRef(0);
  const localState = useRef<Record<string, unknown>>({});
  const latest = useRef({ current, queue, theme, status });
  const preferencesLatest = useRef({ visuals, experience });
  useEffect(() => { latest.current = { current, queue, theme, status }; }, [current, queue, theme, status]);
  useEffect(() => { preferencesLatest.current = { visuals, experience }; }, [visuals, experience]);

  const report = useCallback((error: unknown) => {
    setNotice(error instanceof Error ? error.message : 'Не удалось выполнить действие.');
  }, []);

  const flushHistory = useCallback(async () => {
    if (!api || !session || historySending.current) return;
    historySending.current = true;
    try {
      while (sessionRef.current === session && pendingHistory.current.length) {
        const pending = pendingHistory.current;
        const entry = pending[0];
        await api.record(entry);
        // A logout/account switch may replace the queue while the request is
        // in flight. Never shift or submit another account's pending history.
        if (sessionRef.current !== session || pendingHistory.current !== pending) return;
        pending.shift(); storage.savePendingHistory(session, pending);
      }
    } finally { historySending.current = false; }
  }, [api, session]);

  const activate = useCallback(async (value: Session) => {
    await storage.recoverRestore(value);
    const [cached, offline, history, state] = await Promise.all([storage.library(value), storage.downloads(value), storage.pendingHistory(value), storage.localState(value)]);
    cacheOwner.current = `${value.server}|${value.user.id}`;
    const available = offline.filter(item => storage.exists(item.uri));
    setLibrary(isLocalSession(value) ? reconcileLocalLibrary(cached || empty, available) : cached || empty); setDownloads(available);
    setOnline(false); setNotice(''); setWaveEntries([]); pendingHistory.current = [];
    pendingHistory.current = history as PendingHistory[]; localState.current = state;
    setTheme(normalizeTheme(state.theme) || 'ObsidianYellow');
    setVisuals(normalizeVisualPreferences(state.visuals));
    setExperience({ ...defaultExperience, ...state.experience as Experience });
    setAudioPreferences(normalizeAudioPreferences(state.audioPreferences));
    setRemotePlayback(state.playback as Playback || null);
    await storage.saveSession(value); sessionRef.current = value; setSession(value);
  }, [setLibrary]);
  useEffect(() => { void setAudioModeAsync({ shouldPlayInBackground: experience.backgroundAudioEnabled }).catch(report); }, [experience.backgroundAudioEnabled, report]);

  useEffect(() => {
    const handle = player as unknown as { setSpectrumEnabled?: (enabled: boolean) => void; addListener: (name: 'spectrumUpdate', callback: (frame: SpectrumFrame) => void) => { remove: () => void } };
    if (typeof handle.setSpectrumEnabled !== 'function') { audioTelemetry.supported = false; return; }
    audioTelemetry.supported = true;
    const enabled = () => handle.setSpectrumEnabled!(status.playing && visuals.enabled && AppState.currentState === 'active');
    enabled();
    const frames = handle.addListener('spectrumUpdate', acceptSpectrum);
    const state = AppState.addEventListener('change', enabled);
    return () => { handle.setSpectrumEnabled!(false); frames.remove(); state.remove(); clearSpectrum(); };
  }, [player, status.playing, visuals.enabled]);

  useEffect(() => {
    void storage.session().then(async value => { if (value) await activate(value); }).catch(report).finally(() => setHydrated(true));
    void setAudioModeAsync({ playsInSilentMode: true, shouldPlayInBackground: true, interruptionMode: 'doNotMix' }).catch(report);
  }, [activate, report]);

  const refresh = useCallback(async () => {
    if (session && isLocalSession(session)) {
      const files = (await storage.downloads(session)).filter(item => storage.exists(item.uri));
      if (sessionRef.current !== session) return;
      setDownloads(files); setLibrary(value => reconcileLocalLibrary(value, files)); setNotice(''); return;
    }
    if (!api || !session) return;
    setBusy(true);
    try {
      const [tracks, favorites, playlists, history, preferences, synced] = await Promise.all([api.catalog(), api.favorites(), api.playlists(), api.history(), api.preferences(), api.sync()]);
      if (sessionRef.current !== session) return;
      const value = { tracks, favorites, playlists, history, preferences };
      setLibrary(value); storage.saveLibrary(session, value); setOnline(true);
      version.current = synced;
      if (!latest.current.current) {
        setRemotePlayback(synced.playback);
        const decoded = decodeSettings(synced.settings, latest.current.theme, preferencesLatest.current.visuals, preferencesLatest.current.experience);
        setTheme(decoded.theme); setVisuals(decoded.visuals); setExperience(decoded.experience);
      }
      await flushHistory();
    } catch (error) { setOnline(false); report(error); } finally { setBusy(false); }
  }, [api, session, report, flushHistory, setLibrary]);
  useEffect(() => { let active = true; queueMicrotask(() => { if (active && api) void refresh(); }); return () => { active = false; }; }, [api, refresh]);
  // Migrate existing offline imports too: users need not delete/re-import music.
  useEffect(() => {
    if (!session || Platform.OS === 'web') return;
    let cancelled = false;
    void (async () => {
      const files = await storage.downloads(session);
      for (const item of files) {
        if (cancelled || sessionRef.current !== session) return;
        if (item.metadataVersion === 1 || !storage.exists(item.uri)) continue;
        try {
          const metadata = await readLocalMetadata(item.uri, storage.coverTarget(session, item.track.id).uri);
          if (cancelled || sessionRef.current !== session) return;
          const track = { ...item.track, ...(isLocalSession(session) ? metadata : { coverUrl: metadata.coverUrl || item.track.coverUrl }), importedAt: item.track.importedAt || item.downloadedAt };
          // Re-read the manifest: importing/removing files may run while native code works.
          const latest = await storage.downloads(session);
          if (!latest.some(value => value.track.id === item.track.id)) { if (metadata.coverUrl) storage.deleteFile(metadata.coverUrl); continue; }
          const next = latest.map(value => value.track.id === track.id ? { ...value, track, metadataVersion: 1 } : value);
          storage.saveDownloads(session, next); setDownloads(next);
          if (isLocalSession(session)) setLibrary(value => ({ ...value, tracks: value.tracks.map(t => t.id === track.id ? track : t), favorites: value.favorites.map(t => t.id === track.id ? track : t) }));
        } catch { /* Unsupported container: keep playable file and filename labels; retry next launch. */ }
      }
    })().catch(report);
    return () => { cancelled = true; };
  }, [session, report, setLibrary]);
  const recordCurrent = useCallback(async (completed = false, skipped = false) => {
    const track = activeTrack.current;
    if (!track || !session || listened.current < 1) return;
    const entry = { eventId: randomUUID(), playedAt: new Date().toISOString(), trackId: track.id, playedSeconds: Math.round(track.durationSeconds > 0 ? Math.min(track.durationSeconds, listened.current) : listened.current), completed, skipped };
    listened.current = 0;
    if (isLocalSession(session)) {
      setLibrary(value => ({ ...value, history: [{ ...entry, id: entry.eventId }, ...value.history].slice(0, 2000) }));
      return;
    }
    pendingHistory.current.push(entry); storage.savePendingHistory(session, pendingHistory.current);
    if (api) {
      try {
        await flushHistory();
      } catch { setOnline(false); }
    }
  }, [api, session, flushHistory, setLibrary]);

  const sync = useCallback(async () => {
    if (!session) return;
    const state = latest.current;
    const playback: Playback = state.current ? { trackId: state.current.id, positionSeconds: state.status.currentTime, queue: state.queue.map(t => t.id), queueIndex: state.status.currentIndex, repeat: state.status.loop === 'single' ? 'ONE' : state.status.loop === 'all' ? 'ALL' : 'OFF', shuffle: shuffleEnabled, playing: state.status.playing, device: 'mobile', source: state.current.source } : (remotePlayback || {});
    localState.current = { ...localState.current, theme: state.theme, playback, visuals, experience };
    storage.saveLocalState(session, localState.current);
    if (!api) return;
    if (!state.current || syncConflict.current) return; // Opening the app never replaces another device's position.
    try {
      const currentVersion = version.current || await api.sync();
      if (state.queue[state.status.currentIndex]?.id !== state.current.id) return;
      const saved = await api.saveSync({ version: currentVersion.version, settings: { ...currentVersion.settings, ...encodeSettings(state.theme, visuals, experience) }, playback: { ...playback, shuffle: shuffleEnabled } });
      version.current = saved; setOnline(true);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        const remote = await api.sync(); version.current = remote; setRemotePlayback(remote.playback);
        syncConflict.current = true;
        setNotice('Позиция изменилась на другом устройстве. В настройках можно продолжить с его места.');
      } else { setOnline(false); }
    }
  }, [api, session, remotePlayback, visuals, experience, shuffleEnabled]);
  useEffect(() => {
    if (!session) return;
    const timer = setInterval(() => { void sync().catch(report); }, 15_000);
    const subscription = AppState.addEventListener('change', state => {
      if (state !== 'active') { void sync().catch(report); void recordCurrent().catch(report); }
    });
    return () => { clearInterval(timer); subscription.remove(); };
  }, [session, sync, recordCurrent, report]);

  const play = useCallback(async (track: Track, items: Track[] = [track], position = 0, queueIndex?: number, shuffle = false, autoplay = true) => {
    if (!session) return;
    try {
      await recordCurrent(false, false);
      const offline = downloads.find(item => item.track.id === track.id && storage.exists(item.uri));
      if (!online && !offline) throw new Error(localMode ? 'Файл отсутствует во внутреннем хранилище. Добавьте его заново из памяти телефона.' : 'Трек не скачан. Подключитесь к серверу или выберите музыку в разделе «Скачано».');
      listened.current = 0; lastPosition.current = position;
      syncConflict.current = false;
      const available = online ? items : items.filter(item => downloads.some(download => download.track.id === item.id && storage.exists(download.uri)));
      queueUndo.current=[];setCanUndoQueue(false);queueRef.current = available; activeTrack.current = track;
      player.pause(); player.clear();
      for (const item of available) {
        const saved = downloads.find(download => download.track.id === item.id && storage.exists(download.uri));
        if (saved) player.add({ uri: saved.uri, name: item.title });
        else if (api) player.add({ uri: api.stream(item.id), headers: { Authorization: `Bearer ${session.token}` }, name: item.title });
      }
      const requested = queueIndex !== undefined && available[queueIndex]?.id === track.id ? queueIndex : available.findIndex(item => item.id === track.id);
      activeQueueIndex.current = Math.max(0, requested); setShuffleEnabled(shuffle);
      player.skipTo(Math.max(0, requested));
      localState.current.playback={trackId:track.id,queue:available.map(t=>t.id),queueIndex:Math.max(0,requested),positionSeconds:position,shuffle,repeat:player.loop==='single'?'ONE':player.loop==='all'?'ALL':'OFF',playing:false};storage.saveLocalState(session,localState.current);
      player.setActiveForLockScreen(true, { title: track.title, artist: track.artist, albumTitle: track.album });
      setQueue(available); setCurrent(track);
      if (position > 0) await player.seekTo(position);
      if (autoplay) player.play(); setNotice('');
    } catch (error) { report(error); }
  }, [api, session, localMode, downloads, online, player, recordCurrent, report]);
  const restoredSession = useRef('');
  const resumePrepared = useRef<Promise<void>>(Promise.resolve());
  useEffect(() => {
    if(!hydrated || !session || !localMode || restoredSession.current===session.user.id)return;
    restoredSession.current=session.user.id;
    const saved=localState.current.playback as Playback | undefined;
    const items=(saved?.queue || []).map(id=>libraryRef.current.tracks.find(t=>t.id===id)).filter((t):t is Track=>!!t&&!t.missing);
    const track=items.find(t=>t.id===saved?.trackId);if(track){setNativeRepeat(player,saved?.repeat || 'OFF');resumePrepared.current=play(track,items,saved?.positionSeconds || 0,saved?.queueIndex,saved?.shuffle || false,false);}
  },[hydrated,session,localMode,play,player]);

  useEffect(() => {
    if (!localMode) return;
    const subscription = player.addListener('playlistStatusUpdate', frame => {
      const track = activeTrack.current;
      if (!track || queueRef.current[frame.currentIndex]?.id !== track.id || !frame.isLoaded || !Number.isFinite(frame.duration) || frame.duration <= 0 || track.durationSeconds === frame.duration) return;
      const updated = { ...track, durationSeconds: frame.duration };
      setCurrent(updated); activeTrack.current = updated;
      queueRef.current = queueRef.current.map(item => item.id === updated.id ? updated : item);
      setLibrary(value => ({ ...value, tracks: value.tracks.map(item => item.id === updated.id ? updated : item), favorites: value.favorites.map(item => item.id === updated.id ? updated : item) }));
    });
    return () => subscription.remove();
  }, [localMode, player, setLibrary]);

  const next = useCallback(async (offset = 1, completed = false) => {
    if (!activeTrack.current) return;
    await recordCurrent(completed, !completed && offset === 1);
    manualSkip.current = true;
    if (offset < 0) player.previous(); else player.next();
  }, [player, recordCurrent]);
  useEffect(() => {
    const subscription = player.addListener('trackChanged', ({ currentIndex }) => {
      const track = queueRef.current[currentIndex];
      if (!track || currentIndex === activeQueueIndex.current) return;
      void recordCurrent(!manualSkip.current, manualSkip.current).catch(report);
      manualSkip.current = false; lastPosition.current = 0;
      activeTrack.current = track; activeQueueIndex.current = currentIndex; setCurrent(track);
      player.updateLockScreenMetadata({ title: track.title, artist: track.artist, albumTitle: track.album });
    });
    return () => subscription.remove();
  }, [player, recordCurrent, report]);
  useEffect(() => {
    if (status.playing && current) {
      const difference = status.currentTime - lastPosition.current;
      if (difference > 0 && difference < 300) listened.current += difference;
    }
    lastPosition.current = status.currentTime;
  }, [status.currentTime, status.playing, current]);
  useEffect(() => { if (status.didJustFinish) queueMicrotask(() => { void recordCurrent(true).catch(report); }); }, [status.didJustFinish, recordCurrent, report]);

  const togglePlayback = async () => {
    if (!current) return;
    if (status.playing) { player.pause(); await recordCurrent(); await sync(); }
    else { if (status.didJustFinish) await player.seekTo(0); player.play(); }
  };
  const seek = async (seconds: number) => { lastPosition.current = seconds; await player.seekTo(seconds); };
  const setColorTheme = async (value: ThemeName) => {
    setTheme(value);
    if (session) { localState.current = { ...localState.current, theme: value }; storage.saveLocalState(session, localState.current); }
    if (api) {
      try {
        const remote = await api.sync();
        version.current = await api.saveSync({ ...remote, settings: { ...remote.settings, ...encodeSettings(value, visuals, experience) } });
      } catch (error) { report(error); }
    }
  };
  const setVisualPreferences = async (value: Partial<VisualPreferences>) => {
    const next = normalizeVisualPreferences({ ...visuals, ...value, ...(value.quality ? { fps: value.quality === 'Eco' ? 15 as const : value.quality === 'Ultra' ? 60 as const : 30 as const } : {}) });
    if (!visualModes.includes(next.mode)) return;
    setVisuals(next);
    if (session) { localState.current = { ...localState.current, visuals: next }; storage.saveLocalState(session, localState.current); }
    if (api) { const remote = await api.sync(); version.current = await api.saveSync({ ...remote, settings: { ...remote.settings, ...encodeSettings(theme, next, experience) } }); }
  };
  const setExperiencePreferences = async (value: Partial<Experience>) => {
    const next = { ...experience, ...value }; setExperience(next);
    if (session) { localState.current = { ...localState.current, experience: next }; storage.saveLocalState(session, localState.current); }
    if (api) { const remote = await api.sync(); version.current = await api.saveSync({ ...remote, settings: { ...remote.settings, ...encodeSettings(theme, visuals, next) } }); }
  };
  const favorite = async (track: Track) => {
    if (!session) return;
    const exists = library.favorites.some(item => item.id === track.id);
    if (api) await api.favorite(track.id, !exists);
    setLibrary(value => ({ ...value, favorites: exists ? value.favorites.filter(item => item.id !== track.id) : [...value.favorites, track] }));
  };
  const download = async (track: Track) => {
    if (!session || !api || downloading) return;
    const destination = storage.downloadTarget(session, track.id, track.mimeType);
    setDownloading(track.id);
    try {
      await File.downloadFileAsync(api.stream(track.id), destination, { headers: { Authorization: `Bearer ${session.token}` }, idempotent: true });
      let coverUrl = track.coverUrl;
      try { const metadata = await readLocalMetadata(destination.uri, storage.coverTarget(session, track.id).uri); coverUrl = metadata.coverUrl || coverUrl; } catch { /* Audio can play without embedded metadata. */ }
      const item: OfflineTrack = { track: { ...track, coverUrl }, uri: destination.uri, downloadedAt: new Date().toISOString(), metadataVersion: 1 };
      const previous = await storage.downloads(session);
      const result = [...previous.filter(i => i.track.id !== track.id), item];
      storage.saveDownloads(session, result);
      if (sessionRef.current === session) setDownloads(result);
    } catch (error) { if (destination.exists) destination.delete(); report(error); } finally { setDownloading(null); }
  };
  const removeDownload = (id: string) => {
    if (!session) return;
    const item = downloads.find(i => i.track.id === id);
    if (item) storage.deleteFile(item.uri);
    setDownloads(value => { const result = value.filter(i => i.track.id !== id); storage.saveDownloads(session, result); return result; });
  };
  const startWave = async (mode = 'MY_WAVE') => {
    if (!session) return;
    setBusy(true);
    try {
      const entries = api ? await api.wave(mode) : localWave(library, mode, localState.current.feedback as Record<string, string> || {}); setWaveEntries(entries);
      if (!entries.length) throw new Error(localMode ? 'Добавьте музыку из памяти телефона в «Мою музыку». Волна выбирает треки из вашей локальной коллекции.' : 'В каталоге пока нет подходящей музыки. Добавьте свои файлы на сервер и выберите любимые жанры.');
      await play(entries[0].track, entries.map(entry => entry.track));
    } finally { setBusy(false); }
  };
  const resume = async () => {
    if (!session) return;
    const remote = online && api ? await api.sync() : null;
    if (remote) version.current = remote;
    const playback = remote?.playback || localState.current.playback as Playback || remotePlayback;
    if (!playback?.trackId) throw new Error('Сохранённая позиция отсутствует.');
    const tracks = [...library.tracks, ...downloads.map(item => item.track)];
    const track = tracks.find(item => item.id === playback.trackId);
    if (!track) throw new Error(localMode ? 'Сохранённый трек удалён из локальной библиотеки.' : 'Этот трек недоступен в серверной библиотеке. Локальную музыку ПК нужно отдельно добавить на сервер с вашего согласия.');
    const items = playback.queue?.map(id => tracks.find(t => t.id === id)).filter((item): item is Track => !!item) || [track];
    setNativeRepeat(player, playback.repeat || 'OFF');
    await play(track, items.length ? items : [track], playback.positionSeconds || 0, playback.queueIndex, playback.shuffle || false);
  };
  const widgetCommand = async (command: string) => {
    if (!['open','toggle','next','previous'].includes(command)) return;
    await new Promise(resolve => setTimeout(resolve, 0));
    await resumePrepared.current;
    if (command === 'open') return;
    if (!activeTrack.current) { await resume(); return; }
    if (command === 'toggle') {
      if (player.playing) { player.pause(); await recordCurrent(); await sync(); }
      else { if (player.duration > 0 && player.currentTime >= player.duration) await player.seekTo(0); player.play(); }
    } else await next(command === 'previous' ? -1 : 1);
  };
  useEffect(() => {
    if (Platform.OS === 'web') return;
    try { updateNowPlaying({ title: current?.title || 'Ваша музыка. Ваше пространство.', artist: current?.artist || 'Откройте Auralis', playing: status.playing, accent: themes[theme].accent }); }
    catch { /* A system widget is optional; playback must remain available. */ }
  }, [current?.title,current?.artist,status.playing,theme]);
  const savePlaylist = async (playlist: Playlist) => { if (session) { const saved = api ? await api.savePlaylist(playlist) : { ...playlist, updatedAt: new Date().toISOString() }; setLibrary(value => ({ ...value, playlists: value.playlists.map(item => item.id === saved.id ? saved : item) })); } };
  const createPlaylist = async (name: string) => { if (session && name.trim()) { const playlist = api ? await api.createPlaylist(name) : { id: randomUUID(), name: name.trim(), trackIds: [], updatedAt: new Date().toISOString() }; setLibrary(value => ({ ...value, playlists: [...value.playlists, playlist] })); } };
  const deletePlaylist = async (id: string) => { if (session) { if (api) await api.deletePlaylist(id); setLibrary(value => ({ ...value, playlists: value.playlists.filter(item => item.id !== id) })); } };
  const savePreferences = async (preferences: Preferences) => { if (session) { if (api) await api.savePreferences(preferences); setLibrary(value => ({ ...value, preferences })); } };
  const feedback = async (action: string) => { if (current) { if (api) await api.feedback(current.id, action); else if (session) { localState.current.feedback = { ...localState.current.feedback as Record<string, string>, [current.id]: action }; storage.saveLocalState(session, localState.current); } } };
  const clearHistory = async () => { if (session) { if (api) await api.clearHistory(); pendingHistory.current = []; storage.savePendingHistory(session, []); setLibrary(value => ({ ...value, history: [] })); } };
  const importMusic = async () => {
    if (!session || !localMode || importLock.current) return;
    if (Platform.OS === 'web') throw new Error('Импорт музыки с телефона доступен в установленном приложении Android/iOS.');
    importLock.current = true; setImporting(true);
    let added = 0, duplicates = 0; const failures: string[] = [];
    try {
      const result = await DocumentPicker.getDocumentAsync({ type: ['audio/*', 'application/ogg', 'application/x-flac'], multiple: true, copyToCacheDirectory: true });
      if (result.canceled || sessionRef.current !== session) return;
      for (const asset of result.assets) {
        let destination: File | null = null;
        try {
          const info = localAudioInfo(asset.name, asset.mimeType), source = new File(asset.uri);
          if (!source.exists || source.size === 0) throw new Error(`Файл «${asset.name}» пуст или недоступен.`);
          const id = localTrackId(await source.digest('SHA-256'));
          if (sessionRef.current !== session) break;
          const previous = await storage.downloads(session);
          if (previous.some(item => item.track.id === id && storage.exists(item.uri))) { duplicates++; continue; }
          destination = storage.downloadTarget(session, id, info.mimeType);
          await source.copy(destination, { overwrite: true });
          if (sessionRef.current !== session) { destination.delete(); break; }
          let metadata = {}; let metadataVersion: number | undefined;
          try { metadata = await readLocalMetadata(destination.uri, storage.coverTarget(session, id).uri); metadataVersion = 1; } catch { /* Some system decoders cannot read all tag containers. */ }
          if (sessionRef.current !== session) { destination.delete(); break; }
          const restored=libraryRef.current.tracks.find(track=>track.id===id && track.missing);
          const track: Track = { ...(restored || {}), id, title: info.title, artist: info.artist, album: '', genre: '', durationSeconds: 0, sizeBytes: destination.size, mimeType: info.mimeType, source: 'LOCAL', coverUrl: null, ...metadata, ...(restored ? { title:restored.title, artist:restored.artist, album:restored.album, genre:restored.genre, coverUrl:restored.coverUrl || null } : {}), contentHash:id.slice(6), missing:false, importedAt: new Date().toISOString() };
          const item: OfflineTrack = { track, uri: destination.uri, downloadedAt: new Date().toISOString(), metadataVersion };
          const files = [...(await storage.downloads(session)).filter(value => value.track.id !== id), item];
          storage.saveDownloads(session, files); setDownloads(files);
          setLibrary(value => ({ ...value, tracks: [...value.tracks.filter(t => t.id !== id), track] })); added++; destination = null;
        } catch (error) { if (destination?.exists) destination.delete(); failures.push(error instanceof Error ? error.message : `Не удалось добавить ${asset.name}`); }
        finally { // Picker copies are temporary; never delete the user's original document.
          const cached = new File(asset.uri); if (cached.uri.startsWith(storage.cacheRoot()) && cached.exists) cached.delete();
        }
      }
      if (sessionRef.current === session) setNotice(`Добавлено: ${added}${duplicates ? ` · уже в библиотеке: ${duplicates}` : ''}${failures.length ? ` · ошибок: ${failures.length}. ${failures[0]}` : ''}`);
    } finally { importLock.current = false; setImporting(false); }
  };
  const removeLocalTrack = async (id: string) => {
    if (!session || !localMode || importLock.current) return;
    for (let index=queueRef.current.length-1;index>=0;index--) if(queueRef.current[index].id===id) await removeQueueItem(index);
    const item = downloads.find(value => value.track.id === id); if (item) storage.deleteFile(item.uri);
    const files = downloads.filter(value => value.track.id !== id); storage.saveDownloads(session, files); setDownloads(files);
    setLibrary(value => withoutLocalTrack(value, id)); setWaveEntries(value => value.filter(entry => entry.track.id !== id));
    if ((localState.current.playback as Playback | undefined)?.queue?.includes(id)) { localState.current.playback = {}; storage.saveLocalState(session, localState.current); setRemotePlayback(null); }
  };
  const logout = async (deleteAccount = false) => {
    if (downloading || importLock.current) throw new Error('Дождитесь завершения добавления музыки.');
    if (!deleteAccount) await recordCurrent();
    const native = player as unknown as { setAuralisSleepTimer?: (seconds: number) => Promise<void> }; await native.setAuralisSleepTimer?.(0); setSleepDeadline(0); setSleepRemaining(0);
    player.pause(); player.setActiveForLockScreen(false); player.clear(); activeTrack.current = null; queueRef.current = [];
    if (session && deleteAccount && api) { await api.deleteAccount(); storage.deleteUser(session); }
    else { await sync(); if (api) { try { await api.logout(); } catch { /* Offline local logout remains available. */ } } }
    await storage.saveSession(null); sessionRef.current = null; cacheOwner.current = ''; setSession(null); setCurrent(null); setQueue([]); setLibrary(empty); setDownloads([]); setRemotePlayback(null); version.current = null;
  };
  const run = (task: () => Promise<unknown>) => { void task().catch(error => { report(error); Alert.alert('Auralis', error instanceof Error ? error.message : 'Не удалось выполнить действие.'); }); };

  const setLoop = (value: boolean) => setNativeLoop(player, value);
  const setRate = (value: number) => {setNativeRate(player,value);if(session){localState.current['audio.rate']=value;storage.saveLocalState(session,localState.current);}};
  const setVolume = (value: number) => { const native=player as unknown as {setAuralisVolume?:(value:number)=>void};if(native.setAuralisVolume)native.setAuralisVolume(value);else setNativeVolume(player,value);if(session){localState.current.volume=value;storage.saveLocalState(session,localState.current);} };
  const setRepeat = (value: 'OFF'|'ALL'|'ONE') => setNativeRepeat(player, value);
  const shuffleQueue = async () => {
    if (!current) return;
    const others = queue.filter((_, index) => index !== status.currentIndex);
    for (let index = others.length-1; index > 0; index--) { const random = getRandomValues(new Uint32Array(1))[0]; const target = random % (index+1); [others[index], others[target]] = [others[target], others[index]]; }
    await play(current, [current, ...others], status.currentTime, 0, true);
  };
  const updateLocalMetadata = async (ids: string[], changes: Partial<Pick<Track,'title'|'artist'|'album'|'genre'|'trackNumber'|'discNumber'|'coverUrl'>>) => {
    if(!session || !localMode || importLock.current)throw new Error('Редактор доступен для локальной библиотеки.');
    const clean={...changes};for(const key of ['title','artist','album','genre'] as const)if(clean[key]!==undefined){if(typeof clean[key]!=='string'||clean[key]!.length>4096)throw new Error('Слишком длинное поле.');clean[key]=clean[key]!.trim();}
    if(clean.title!==undefined&&!clean.title)throw new Error('Название не может быть пустым.');
    for(const key of ['trackNumber','discNumber'] as const)if(clean[key]!==undefined&&(!Number.isInteger(clean[key])||clean[key]!<0||clean[key]!>(key==='discNumber'?999:9999)))throw new Error('Номер должен быть целым положительным числом.');
    const selected=new Set(ids);const update=(track:Track)=>selected.has(track.id)?{...track,...clean}:track;
    setLibrary(v=>({...v,tracks:v.tracks.map(update),favorites:v.favorites.map(update)}));
    const offline=(await storage.downloads(session)).map(item=>({...item,track:update(item.track)}));storage.saveDownloads(session,offline);setDownloads(offline);
    queueRef.current=queueRef.current.map(update);setQueue(queueRef.current);if(activeTrack.current&&selected.has(activeTrack.current.id)){activeTrack.current=update(activeTrack.current);setCurrent(activeTrack.current);player.updateLockScreenMetadata({title:activeTrack.current.title,artist:activeTrack.current.artist,albumTitle:activeTrack.current.album});}
    setNotice('Метаданные сохранены в библиотеке. Исходные аудиофайлы сохранены.');
  };
  const pickTrackCover = async () => { if(!session)throw new Error('Нет локальной библиотеки.');const result=await DocumentPicker.getDocumentAsync({type:['image/png','image/jpeg'],copyToCacheDirectory:true});if(result.canceled)return null;const source=new File(result.assets[0].uri);if(source.size>16*1024*1024)throw new Error('Обложка должна быть меньше 16 МБ.');const destination=storage.coverTarget(session,randomUUID());await source.copy(destination);return destination.uri; };
  const currentArtwork = artworkSource(downloads.find(item => item.track.id === current?.id && item.track.coverUrl?.startsWith('file://'))?.track || current, session);
  useEffect(()=>{
    if(Platform.OS==='web')return;
    const native=player as unknown as {setAuralisAudioOptions?:(seconds:number,gains:number[],blocked:boolean[])=>void};
    native.setAuralisAudioOptions?.(audioPreferences.crossfadeSeconds,queue.map(t=>audioPreferences.normalization?normalizationGainDb(t.loudnessLufs,t.truePeakDb??t.samplePeakDb,audioPreferences.targetLufs):0),queue.map(t=>audioPreferences.disabledAlbums.includes(albumKey(t))));
  },[player,queue,audioPreferences]);
  const saveAudioPreferences=async(change:Partial<AudioPreferences>)=>{const next=normalizeAudioPreferences({...audioPreferences,...change});setAudioPreferences(next);if(session){localState.current.audioPreferences=next;storage.saveLocalState(session,localState.current);}};
  const analyzeLibrary=async()=>{
    if(!session||!localMode||analysisActive.current)return;
    if(Platform.OS==='web')throw new Error('Анализ файлов доступен в установленном приложении.');
    const native=requireNativeModule('ExpoAudio') as {analyzeAuralisLoudness?:(uri:string)=>Promise<{loudnessLufs:number;samplePeakDb:number}>};
    if(!native.analyzeAuralisLoudness)throw new Error('Установите новую сборку приложения с анализом громкости.');
    analysisActive.current=true;const files=downloads.filter(d=>d.track.loudnessLufs===undefined&&storage.exists(d.uri));let done=0,failed=0;setAnalysisProgress({done,total:files.length,failed});
    try{for(const item of files){if(!analysisActive.current||sessionRef.current!==session)break;try{const measurement=await native.analyzeAuralisLoudness(item.uri);if(!Number.isFinite(measurement.loudnessLufs)||!Number.isFinite(measurement.samplePeakDb))throw new Error('Некорректный анализ');if(sessionRef.current!==session)break;const latest=await storage.downloads(session);if(!latest.some(t=>t.track.id===item.track.id))continue;const update=(track:Track)=>track.id===item.track.id?{...track,...measurement}:track;const saved=latest.map(d=>({...d,track:update(d.track)}));storage.saveDownloads(session,saved);setDownloads(saved);setLibrary(v=>({...v,tracks:v.tracks.map(update),favorites:v.favorites.map(update)}));queueRef.current=queueRef.current.map(update);setQueue(queueRef.current);if(activeTrack.current?.id===item.track.id){activeTrack.current=update(activeTrack.current);setCurrent(activeTrack.current);}}catch{failed++;}done++;setAnalysisProgress({done,total:files.length,failed});}}finally{analysisActive.current=false;setAnalysisProgress(null);setNotice(`Анализ завершён: ${done-failed} · ошибок: ${failed}. Оригиналы сохранены.`);}
  };
  const sourceFor = (track: Track) => {
    const saved = downloads.find(item => item.track.id === track.id && storage.exists(item.uri));
    if (saved) return { uri: saved.uri, name: track.title };
    if (api && session && online) return { uri: api.stream(track.id), headers: { Authorization: `Bearer ${session.token}` }, name: track.title };
    throw new Error('Трек недоступен. Добавьте файл или подключитесь к серверу.');
  };
  const persistQueue = (items: Track[], index: number) => {
    queueRef.current = items; activeQueueIndex.current = index; setQueue(items); setQueueRevision(v => v+1);setCanUndoQueue(queueUndo.current.length>0);
    if (session) { localState.current.playback = { queue: items.map(t => t.id), queueIndex: index, trackId: items[index]?.id, positionSeconds: player.currentTime, repeat: player.loop === 'single' ? 'ONE' : player.loop === 'all' ? 'ALL' : 'OFF', shuffle: shuffleEnabled, playing: false }; storage.saveLocalState(session, localState.current); }
  };
  const queueTrack = async (track: Track, next = false) => {
    if (!queueRef.current.length) { await play(track,[track],0,0,false,false); return; }
    const index = next ? activeQueueIndex.current+1 : queueRef.current.length;
    const state = insertQueue({ items: queueRef.current, index: activeQueueIndex.current }, track, index);
    player.insert(sourceFor(track),index); persistQueue(state.items,state.index);
  };
  const moveQueueItem = async (from: number, to: number, undo = true) => {
    const state = moveQueue({ items: queueRef.current, index: activeQueueIndex.current },from,to);
    const native = player as unknown as { moveAuralisQueue?: (from: number,to: number) => void };
    if (!native.moveAuralisQueue) throw new Error('Для перемещения без перезапуска звука установите новую сборку.');
    if (undo) {queueUndo.current.push({ type:'move',from:to,to:from });if(queueUndo.current.length>20)queueUndo.current.shift();}
    activeQueueIndex.current = state.index; native.moveAuralisQueue(from,to); persistQueue(state.items,state.index);
  };
  const removeQueueItem = async (index: number) => {
    const previous = queueRef.current, state = removeQueue({ items: previous,index: activeQueueIndex.current },index);
    queueUndo.current.push({ type:'remove',track: previous[index],index });if(queueUndo.current.length>20)queueUndo.current.shift();
    if (index === activeQueueIndex.current) await recordCurrent(false,true);
    queueRef.current = state.items; activeQueueIndex.current = state.index;
    player.remove(index); persistQueue(state.items,state.index); activeTrack.current = state.items[state.index] || null; setCurrent(activeTrack.current);
    if (!state.items.length) { player.pause(); player.setActiveForLockScreen(false); }
  };
  const undoQueue = async () => { const action=queueUndo.current.pop(); if (!action) return; if(action.type==='move') await moveQueueItem(action.from,action.to,false); else { const index=Math.min(action.index,queueRef.current.length);if(!queueRef.current.length)await play(action.track,[action.track],0,0,false,false);else{const state=insertQueue({items:queueRef.current,index:activeQueueIndex.current},action.track,index);player.insert(sourceFor(action.track),index);persistQueue(state.items,state.index);}} setQueueRevision(v=>v+1);setCanUndoQueue(queueUndo.current.length>0); };
  const saveQueuePlaylist = async (name: string) => {
    if(!session || !name.trim() || !queueRef.current.length) return;
    const base = api ? await api.createPlaylist(name.trim()) : { id: randomUUID(), name:name.trim(), trackIds:[], updatedAt:new Date().toISOString() };
    const result = { ...base, trackIds:[...new Set(queueRef.current.map(t=>t.id))] };
    const saved=api?await api.savePlaylist(result):result;setLibrary(v=>({...v,playlists:[...v.playlists,saved]}));setNotice('Очередь сохранена в плейлист. Повторяющиеся треки включены один раз.');
  };
  const exportBackup = async (includeAudio: boolean) => {
    if(!session || !localMode || importLock.current) throw new Error('Резервные копии доступны для локальной библиотеки.');
    setLibraryWorking(true);importLock.current=true;
    try { await sync(); const file=await createBackup(libraryRef.current,await storage.downloads(session),localState.current,includeAudio); const sharing=await import('expo-sharing');if(await sharing.isAvailableAsync())await sharing.shareAsync(file.uri,{mimeType:'application/zip',dialogTitle:'Сохранить резервную копию Auralis',UTI:'public.zip-archive'});else throw new Error('На устройстве недоступно сохранение через системное меню.');setNotice('Резервная копия подготовлена. Сохраните файл вне приложения.'); } finally {importLock.current=false;setLibraryWorking(false);}
  };
  const chooseBackup = async () => { if(!session || !localMode)throw new Error('Перейдите в локальную библиотеку.');const picked=await DocumentPicker.getDocumentAsync({type:'*/*',copyToCacheDirectory:true});if(!picked.canceled)setBackupPreview(await inspectBackup(new File(picked.assets[0].uri),libraryRef.current)); };
  const restoreBackup = async (replace: boolean) => {
    if(!session || !localMode || !backupPreview || importLock.current)return;
    setLibraryWorking(true);importLock.current=true;
    try {
      await recordCurrent();
      const assets=await extractBackup(backupPreview,storage.restoredDirectory(session));
      const restored=mergeBackup(libraryRef.current,await storage.downloads(session),localState.current,backupPreview.manifest,assets,replace,randomUUID);
      storage.commitRestore(session,restored.library,restored.downloads,restored.state);player.pause();player.clear();activeTrack.current=null;queueRef.current=[];setCurrent(null);setQueue([]);queueUndo.current=[];setCanUndoQueue(false);localState.current=restored.state;setLibrary(restored.library);setDownloads(restored.downloads);setTheme(normalizeTheme(restored.state.theme)||theme);setVisuals(normalizeVisualPreferences(restored.state.visuals));setExperience({...defaultExperience,...restored.state.experience as Experience});setAudioPreferences(normalizeAudioPreferences(restored.state.audioPreferences));restoredSession.current='';setRemotePlayback(restored.state.playback as Playback);setBackupPreview(null);setNotice(`Восстановлено: ${restored.library.tracks.length} треков. Отсутствующие аудиофайлы можно добавить позже.`);
    } finally {importLock.current=false;setLibraryWorking(false);}
  };
  const previewTransferredBackup=async(file:File)=>{setBackupPreview(await inspectBackup(file,libraryRef.current));};
  const createTransferArchive=async(ids:Set<string>,playlistIds:Set<string>)=>{if(!session||!localMode||importLock.current)throw new Error('Дождитесь завершения импорта и используйте локальную библиотеку.');for(const id of ids)if(!downloads.some(item=>item.track.id===id&&storage.exists(item.uri)))throw new Error('Некоторые выбранные треки отсутствуют в памяти телефона. Добавьте их заново.');importLock.current=true;try{return await createBackup(libraryRef.current,await storage.downloads(session),localState.current,true,ids,playlistIds);}finally{importLock.current=false;}};
  const effectiveExperience=useMemo(()=>({...experience,reducedMotion:experience.reducedMotion||systemReducedMotion}),[experience,systemReducedMotion]);
  return { widgetCommand, previewTransferredBackup, createTransferArchive, audioPreferences, saveAudioPreferences, analyzeLibrary, analysisProgress, stopAnalysis:()=>{analysisActive.current=false;}, exportBackup, chooseBackup, restoreBackup, backupPreview, setBackupPreview, libraryWorking, queueTrack, moveQueueItem, removeQueueItem, undoQueue, queueRevision, canUndoQueue, saveQueuePlaylist, updateLocalMetadata, pickTrackCover, currentArtwork, setSleepTimer, sleepRemaining, session, localMode, enterLocal: () => activate(localSession), importMusic, importing, removeLocalTrack, hydrated, library, downloads, theme, visuals, experience:effectiveExperience, shuffleEnabled, shuffleQueue, setVisualPreferences, setExperiencePreferences, accent: themes[theme].accent, tint: themes[theme].tint, busy, online, notice, setNotice, activate, refresh, current, queue, status, play, next, togglePlayback, seek, favorite, download, removeDownload, downloading, waveEntries, startWave, remotePlayback, resume, sync, setColorTheme, savePlaylist, createPlaylist, deletePlaylist, savePreferences, feedback, clearHistory, logout, run, setLoop, setRepeat, setRate, setVolume };
}

type Store = ReturnType<typeof useAuralisStore>;
const Context = createContext<Store | null>(null);
export function AuralisProvider({ children }: { children: React.ReactNode }) { return <Context.Provider value={useAuralisStore()}>{children}</Context.Provider>; }
export function useAuralis() { const value = useContext(Context); if (!value) throw new Error('Missing AuralisProvider'); return value; }
