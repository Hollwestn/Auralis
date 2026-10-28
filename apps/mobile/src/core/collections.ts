import type { CachedLibrary, Track } from './types';

export type SmartCollection = 'recent' | 'rediscover' | 'mostPlayed';
export function smartCollection(library: CachedLibrary, kind: SmartCollection, now = Date.now()): Track[] {
  const cutoff = now - 30 * 86400_000;
  const history = library.history.filter(item => item.playedSeconds >= 10);
  if (kind === 'recent') return library.tracks.filter(item => !!item.importedAt && Date.parse(item.importedAt) >= cutoff).sort((a, b) => Date.parse(b.importedAt!) - Date.parse(a.importedAt!));
  if (kind === 'rediscover') {
    const recent = new Set(history.filter(item => Date.parse(item.playedAt) >= cutoff).map(item => item.trackId));
    return library.tracks.filter(item => !recent.has(item.id));
  }
  const seconds = new Map<string, number>();
  for (const item of history) seconds.set(item.trackId, (seconds.get(item.trackId) || 0) + item.playedSeconds);
  return library.tracks.filter(item => seconds.has(item.id)).sort((a, b) => seconds.get(b.id)! - seconds.get(a.id)! || a.title.localeCompare(b.title));
}
