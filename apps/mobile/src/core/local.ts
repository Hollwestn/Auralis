import type { CachedLibrary, OfflineTrack, Session, WaveEntry } from './types';

/** A device profile scopes persisted files; it has no credentials and never creates an API. */
export const localSession: Session = { mode: 'local', token: '', server: 'local://device', user: { id: 'device-library', displayName: 'На этом устройстве', email: '' } };
export const isLocalSession = (session: Session | null) => session?.mode === 'local';

const audioTypes: Record<string, string> = { mp3: 'audio/mpeg', flac: 'audio/flac', wav: 'audio/wav', m4a: 'audio/mp4', aac: 'audio/aac', ogg: 'audio/ogg', opus: 'audio/ogg', aif: 'audio/aiff', aiff: 'audio/aiff' };
export function localAudioInfo(name: string, mimeType?: string) {
  const extension = name.split('.').pop()?.toLowerCase() || '';
  if (!audioTypes[extension]) throw new Error(`Не поддерживается файл «${name}». Выберите MP3, M4A, AAC, FLAC, WAV, OGG, OPUS или AIFF.`);
  const stem = name.slice(0, -(extension.length + 1)).trim();
  if (!stem) throw new Error('У музыкального файла отсутствует название.');
  const separator = stem.indexOf(' - ');
  return { extension, mimeType: mimeType?.startsWith('audio/') ? mimeType : audioTypes[extension], title: separator > 0 ? stem.slice(separator + 3).trim() || stem : stem, artist: separator > 0 ? stem.slice(0, separator).trim() : 'Неизвестный исполнитель' };
}

export function localTrackId(sha256: string) {
  if (!/^[a-f0-9]{64}$/i.test(sha256)) throw new Error('Не удалось проверить музыкальный файл.');
  return 'local-' + sha256.toLowerCase();
}

/** The durable file index is authoritative, including recovery after an interrupted import. */
export function reconcileLocalLibrary(cached: CachedLibrary, files: OfflineTrack[]): CachedLibrary {
  const tracks = files.map(item => cached.tracks.find(track => track.id === item.track.id) || item.track);
  const ids = new Set(tracks.map(track => track.id));
  return { ...cached, tracks, favorites: tracks.filter(track => cached.favorites.some(item => item.id === track.id)), playlists: cached.playlists.map(item => ({ ...item, trackIds: item.trackIds.filter(id => ids.has(id)) })), history: cached.history.filter(item => ids.has(item.trackId)) };
}

export function localWave(library: CachedLibrary, mode = 'MY_WAVE', feedback: Record<string, string> = {}): WaveEntry[] {
  const favorites = new Set(library.favorites.map(track => track.id));
  const known = new Set(library.history.map(item => item.trackId));
  const matches = (items: string[], text: string) => items.some(item => item.toLocaleLowerCase() === text.toLocaleLowerCase());
  return library.tracks.filter(track => mode !== 'FAVORITES' || favorites.has(track.id)).filter(track => feedback[track.id] !== 'DISLIKE').map(track => {
    let score = 1; const reasons = ['Из музыки на вашем телефоне'];
    if (favorites.has(track.id)) { score += 4; reasons.push('В вашем избранном'); }
    if (matches(library.preferences.artists, track.artist)) { score += 3; reasons.push('Любимый исполнитель'); }
    if (matches(library.preferences.genres, track.genre)) { score += 2; reasons.push('Любимый жанр'); }
    if (!known.has(track.id)) { score += 1; reasons.push('Ещё не звучала в Auralis'); }
    if (feedback[track.id] === 'LESS') { score -= 4; reasons.push('Понижено по вашей просьбе'); }
    return { track, score, reasons };
  }).sort((a, b) => b.score - a.score || a.track.id.localeCompare(b.track.id)).slice(0, 50);
}

export function withoutLocalTrack(library: CachedLibrary, id: string): CachedLibrary {
  return { ...library, tracks: library.tracks.filter(track => track.id !== id), favorites: library.favorites.filter(track => track.id !== id), history: library.history.filter(item => item.trackId !== id), playlists: library.playlists.map(item => ({ ...item, trackIds: item.trackIds.filter(trackId => trackId !== id) })) };
}
