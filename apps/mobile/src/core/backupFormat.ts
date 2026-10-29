import { importBackupSettings } from './backupSettings.ts';
import type { CachedLibrary, OfflineTrack, Playback, Track } from './types.ts';

export type BackupAsset = { entry: string; sha256: string; size: number };
export type BackupTrack = { id: string; contentHash: string; title: string; artist: string; album: string; genre: string; durationSeconds: number; trackNumber?: number; discNumber?: number; loudnessLufs?: number; truePeakDb?: number; samplePeakDb?: number; originalPath?: string; audio?: BackupAsset; cover?: BackupAsset; mimeType?: string };
export type BackupManifest = { format: 'auralis.backup'; version: 1; createdAt: string; tracks: BackupTrack[]; favorites: string[]; playlists: CachedLibrary['playlists']; history: CachedLibrary['history']; settings: Record<string, unknown>; playback: Playback; feedback?: Record<string, string> };
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
export const portableSetting = (key: string) => /^(theme|visuals|experience|audioPreferences|genres|artists|reducedMotion|advanced|language|volume|preferences\.(genres|artists)|visual\.[A-Za-z]+|background\.enabled|animations\.enabled|eq\.((saved\.)?[0-9]+|enabled)|audio\.[A-Za-z]+|albumTransition\.[a-f0-9]+)$/.test(key);
export function validateManifest(value: unknown): BackupManifest {
  if (!record(value) || value.format !== 'auralis.backup' || value.version !== 1) throw new Error('Неизвестный формат резервной копии.');
  for (const field of ['tracks', 'favorites', 'playlists', 'history']) if (!Array.isArray(value[field]) || value[field].length > 100000) throw new Error(`Повреждён раздел ${field}.`);
  const data = value as unknown as BackupManifest, ids = new Set<string>();
  for (const track of data.tracks) {
    if (!record(track) || typeof track.id !== 'string' || !track.id || track.id.length > 200 || ids.has(track.id) || !/^[a-f0-9]{64}$/.test(track.contentHash) || !Number.isFinite(track.durationSeconds) || track.durationSeconds < 0) throw new Error('Повреждена запись трека.');
    ids.add(track.id);
    for (const field of ['title','artist','album','genre'] as const) if (typeof track[field] !== 'string' || track[field].length > 4096) throw new Error('Некорректные метаданные.');
    for (const field of ['audio','cover'] as const) {
      const asset = track[field]; if (asset == null) continue;
      if (!record(asset) || typeof asset.entry !== 'string' || !new RegExp(`^${field === 'audio' ? 'audio' : 'artwork'}/[a-f0-9]{64}\\.[a-z0-9]{1,8}$`).test(asset.entry) || !/^[a-f0-9]{64}$/.test(asset.sha256) || !asset.entry.includes(asset.sha256) || !Number.isSafeInteger(asset.size) || asset.size < 0) throw new Error('Некорректный путь или размер файла.');
    }
  }
  if (data.favorites.some(id => !ids.has(id))) throw new Error('Избранное ссылается на неизвестный трек.');
  const playlists = new Set<string>();
  for (const p of data.playlists) { if (!record(p) || typeof p.id !== 'string' || !p.id || playlists.has(p.id) || typeof p.name !== 'string' || !p.name.trim() || p.name.length > 200 || !Array.isArray(p.trackIds) || p.trackIds.length > 100000 || p.trackIds.some(id => !ids.has(id))) throw new Error('Повреждён плейлист.'); playlists.add(p.id); }
  for (const h of data.history) if (!record(h) || !ids.has(h.trackId) || !Number.isFinite(h.playedSeconds) || h.playedSeconds < 0 || !Number.isFinite(Date.parse(h.playedAt))) throw new Error('Повреждена история.');
  return { ...data, settings: record(data.settings) ? Object.fromEntries(Object.entries(data.settings).filter(([key]) => portableSetting(key))) : {}, playback: record(data.playback) ? data.playback : {} };
}
export function mergeBackup(library: CachedLibrary, files: OfflineTrack[], state: Record<string, unknown>, manifest: BackupManifest, assets: Record<string, string>, replace: boolean, uuid: () => string) {
  const current = replace ? { tracks: [], favorites: [], playlists: [], history: [], preferences: { genres: [], artists: [] } } as CachedLibrary : structuredClone(library);
  const downloads = replace ? [] : [...files], mapping = new Map<string, string>();
  for (const entry of manifest.tracks) {
    const known = current.tracks.find(t => (t.contentHash || t.id.replace(/^local-/, '')) === entry.contentHash);
    const id = known?.id || `local-${entry.contentHash}`; mapping.set(entry.id, id);
    const uri = entry.audio && assets[entry.audio.entry];
    if (known && !replace) { if (uri && !downloads.some(d => d.track.id === id)) { known.missing = false; downloads.push({ track: known, uri, downloadedAt: new Date().toISOString(), metadataVersion: 1 }); } continue; }
    const coverUrl = entry.cover ? assets[entry.cover.entry] || null : null;
    const track: Track = { id, contentHash: entry.contentHash, title: entry.title, artist: entry.artist, album: entry.album, genre: entry.genre, durationSeconds: entry.durationSeconds, sizeBytes: entry.audio?.size || 0, mimeType: entry.mimeType || 'audio/mpeg', source: 'LOCAL', coverUrl, importedAt: new Date().toISOString(), missing: !uri, trackNumber: entry.trackNumber, discNumber: entry.discNumber, loudnessLufs: entry.loudnessLufs, truePeakDb: entry.truePeakDb, samplePeakDb: entry.samplePeakDb };
    current.tracks.push(track); if (uri) downloads.push({ track, uri, downloadedAt: new Date().toISOString(), metadataVersion: 1 });
  }
  const favoriteIds = new Set([...current.favorites.map(t => t.id), ...manifest.favorites.map(id => mapping.get(id)!)]); current.favorites = current.tracks.filter(t => favoriteIds.has(t.id));
  for (const p of manifest.playlists) {
    const trackIds = [...new Set(p.trackIds.map(id => mapping.get(id)!))];
    if(current.playlists.some(item=>item.name===p.name&&JSON.stringify(item.trackIds)===JSON.stringify(trackIds)))continue;
    const known = current.playlists.find(item => item.id === p.id);
    if (known && known.name === p.name && JSON.stringify(known.trackIds) === JSON.stringify(trackIds)) continue;
    current.playlists.push({ id: known ? uuid() : p.id, name: p.name, trackIds, updatedAt: p.updatedAt || manifest.createdAt });
  }
  const eventKey = (h: CachedLibrary['history'][number]) => `${h.trackId}|${h.playedAt}|${h.playedSeconds}|${!!h.skipped}`;
  const history = new Set(current.history.map(eventKey));
  for (const h of manifest.history) { const entry = { ...h, id: h.eventId || h.id || uuid(), trackId: mapping.get(h.trackId)! }; if (!history.has(eventKey(entry))) { current.history.push(entry); history.add(eventKey(entry)); } }
  current.history.sort((a,b) => Date.parse(b.playedAt)-Date.parse(a.playedAt));
  const playback = { ...manifest.playback, trackId: mapping.get(manifest.playback.trackId || ''), queue: manifest.playback.queue?.map(id => mapping.get(id)).filter((id): id is string => !!id), playing: false };
  const importedSettings=importBackupSettings(manifest.settings);
  const settings = replace ? importedSettings : { ...importedSettings, ...state };
  const list = (value: unknown) => Array.isArray(value) ? value.filter((s):s is string=>typeof s==='string') : typeof value==='string' ? value.split(',').map(s=>s.trim()).filter(Boolean) : [];
  if(replace || !current.preferences.genres.length)current.preferences.genres=list(importedSettings.genres);
  if(replace || !current.preferences.artists.length)current.preferences.artists=list(importedSettings.artists);
  const feedback=Object.fromEntries(Object.entries(manifest.feedback || {}).filter(([id])=>mapping.has(id)).map(([id,value])=>[mapping.get(id)!,value]));
  const restoredState: Record<string,unknown> = { ...settings,feedback:replace?feedback:{...feedback,...state.feedback as Record<string,string>}, playback: !replace && state.playback ? state.playback : playback };
  return { library: current, downloads, state: restoredState };
}
