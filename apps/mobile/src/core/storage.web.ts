import type { CachedLibrary, OfflineTrack, Session } from './types';

// Browser preview is deliberately ephemeral: bearer tokens never enter localStorage.
let session: Session | null = null;
const libraries = new Map<string, CachedLibrary>();
const histories = new Map<string, unknown[]>();
const states = new Map<string, Record<string, unknown>>();
const key = (value: Session) => `${value.server}|${value.user.id}`;
export const storage = {
  async session() { return session; },
  async saveSession(value: Session | null) { session = value; },
  async library(value: Session) { return libraries.get(key(value)) || null; },
  saveLibrary(value: Session, data: CachedLibrary) { libraries.set(key(value), structuredClone(data)); },
  async downloads(_value: Session): Promise<OfflineTrack[]> { return []; },
  saveDownloads(_value: Session, _data: OfflineTrack[]) { throw new Error('Офлайн-загрузки доступны в нативных приложениях Android/iOS.'); },
  async pendingHistory(value: Session) { return histories.get(key(value)) || []; },
  savePendingHistory(value: Session, data: unknown[]) { histories.set(key(value), structuredClone(data)); },
  async localState(value: Session) { return states.get(key(value)) || {}; },
  saveLocalState(value: Session, data: Record<string, unknown>) { states.set(key(value), structuredClone(data)); },
  downloadTarget() { throw new Error('Для скачивания музыки установите нативный Auralis APK или iOS-приложение.'); },
  exists(_uri: string) { return false; },
  deleteFile(_uri: string) {},
  deleteUser(value: Session) { libraries.delete(key(value)); histories.delete(key(value)); states.delete(key(value)); },
};
