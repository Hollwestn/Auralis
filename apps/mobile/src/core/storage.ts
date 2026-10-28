import { Directory, File, Paths } from 'expo-file-system';
import * as SecureStore from 'expo-secure-store';
import type { CachedLibrary, OfflineTrack, Session } from './types';
import { accountPath, trackFilename } from './paths';

const SESSION_KEY = 'auralis.session.v1';
const root = () => new Directory(Paths.document, 'auralis');
const userDirectory = (session: Session) => new Directory(root(), ...accountPath(session.server, session.user.id));

function file(session: Session, name: string) {
  const directory = userDirectory(session);
  directory.create({ idempotent: true, intermediates: true });
  return new File(directory, name);
}
async function read<T>(target: File, fallback: T): Promise<T> {
  if (!target.exists) return fallback;
  try { return JSON.parse(await target.text()) as T; } catch { return fallback; }
}
function write(target: File, value: unknown) { target.writeSync(JSON.stringify(value)); }

export const storage = {
  cacheRoot: () => Paths.cache.uri,
  async session(): Promise<Session | null> {
    const value = await SecureStore.getItemAsync(SESSION_KEY);
    if (!value) return null;
    try { return JSON.parse(value) as Session; } catch { await SecureStore.deleteItemAsync(SESSION_KEY); return null; }
  },
  async saveSession(value: Session | null) {
    if (value) await SecureStore.setItemAsync(SESSION_KEY, JSON.stringify(value), { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY });
    else await SecureStore.deleteItemAsync(SESSION_KEY);
  },
  library(session: Session) { return read<CachedLibrary | null>(file(session, 'library.json'), null); },
  saveLibrary(session: Session, library: CachedLibrary) { write(file(session, 'library.json'), library); },
  downloads(session: Session) { return read<OfflineTrack[]>(file(session, 'downloads.json'), []); },
  saveDownloads(session: Session, downloads: OfflineTrack[]) { write(file(session, 'downloads.json'), downloads); },
  pendingHistory(session: Session) { return read<unknown[]>(file(session, 'pending-history.json'), []); },
  savePendingHistory(session: Session, entries: unknown[]) { write(file(session, 'pending-history.json'), entries); },
  localState(session: Session) { return read<Record<string, unknown>>(file(session, 'state.json'), {}); },
  saveLocalState(session: Session, value: unknown) { write(file(session, 'state.json'), value); },
  downloadTarget(session: Session, id: string, mime: string) {
    const extensions: Record<string, string> = { 'audio/mpeg': 'mp3', 'audio/flac': 'flac', 'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/mp4': 'm4a', 'audio/aac': 'aac', 'audio/ogg': 'ogg', 'audio/aiff': 'aiff', 'audio/x-aiff': 'aiff', 'audio/x-flac': 'flac' };
    return file(session, trackFilename(id, extensions[mime] || 'audio'));
  },
  coverTarget(session: Session, id: string) { return file(session, trackFilename(id, 'png')); },
  exists(uri: string) { return new File(uri).exists; },
  deleteFile(uri: string) { const target = new File(uri); if (target.exists) target.delete(); },
  deleteUser(session: Session) { const directory = userDirectory(session); if (directory.exists) directory.delete(); },
};
