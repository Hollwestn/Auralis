import { exportBackupSettings } from './backupSettings';
import { Directory, File, FileMode, Paths } from 'expo-file-system';
import { Zip, ZipPassThrough, Unzip, UnzipInflate, strFromU8, strToU8 } from 'fflate';
import { randomUUID } from 'expo-crypto';
import { validateManifest, type BackupManifest, type BackupAsset } from './backupFormat';
import type { CachedLibrary, OfflineTrack, Playback } from './types';

const CHUNK = 16384, MAX_ARCHIVE = 3_000_000_000, MANIFEST_LIMIT = 16 * 1024 * 1024;
export type BackupPreview = { file: File; manifest: BackupManifest; tracks: number; playlists: number; favorites: number; history: number; audioBytes: number; missingAudio: number; conflicts: number };
export async function createBackup(library: CachedLibrary, downloads: OfflineTrack[], state: Record<string, unknown>, includeAudio: boolean, selection?: Set<string>, playlistSelection?: Set<string>): Promise<File> {
  const files = new Map<string, File>();
  const manifest: BackupManifest = { format: 'auralis.backup', version: 1, createdAt: new Date().toISOString(), tracks: [], favorites: selection ? [] : library.favorites.map(t => t.id), playlists: selection ? [] : library.playlists, history: selection ? [] : library.history, settings: selection ? {} : exportBackupSettings(state,library.preferences), playback: selection ? {} : (state.playback as Playback || {}), feedback:selection?{}:state.feedback as Record<string,string> || {} };
  let total = 0;
  if(selection&&playlistSelection)manifest.playlists=library.playlists.filter(p=>playlistSelection.has(p.id)).map(p=>({...p,trackIds:p.trackIds.filter(id=>selection.has(id))}));
  const asset = async (uri: string, folder: 'audio'|'artwork', extension: string): Promise<BackupAsset> => {
    const source = new File(uri), sha256 = (await source.digest('SHA-256')).toLowerCase(), entry = `${folder}/${sha256}.${extension}`;
    if (!files.has(entry)) total += source.size;
    if (total > MAX_ARCHIVE) throw new Error('Архив телефона ограничен 3 ГБ. Выберите меньше треков или отключите включение аудио.');
    files.set(entry, source); return { entry, sha256, size: source.size };
  };
  for (const track of library.tracks) {
    if (selection && !selection.has(track.id)) continue;
    const offline = downloads.find(d => d.track.id === track.id && new File(d.uri).exists);
    const hash = offline ? (await new File(offline.uri).digest('SHA-256')).toLowerCase() : track.contentHash || track.id.replace(/^local-/, '');
    if (!/^[a-f0-9]{64}$/.test(hash)) throw new Error(`Для копии трека «${track.title}» сначала скачайте его на устройство.`);
    const ext = /\.([a-z0-9]{1,8})$/i.exec(offline?.uri || '')?.[1].toLowerCase() || 'audio';
    const entry: BackupManifest['tracks'][number] = { id: track.id, contentHash: hash, title: track.title, artist: track.artist, album: track.album, genre: track.genre, durationSeconds: track.durationSeconds, trackNumber: track.trackNumber, discNumber: track.discNumber, loudnessLufs: track.loudnessLufs, truePeakDb: track.truePeakDb, samplePeakDb: track.samplePeakDb, mimeType: track.mimeType };
    if (includeAudio && offline) entry.audio = await asset(offline.uri, 'audio', ext);
    if (track.coverUrl?.startsWith('file://') && new File(track.coverUrl).exists) entry.cover = await asset(track.coverUrl, 'artwork', 'png');
    manifest.tracks.push(entry);
  }
  validateManifest(manifest);
  const json = strToU8(JSON.stringify(manifest)); if (json.length > MANIFEST_LIMIT) throw new Error('Описание библиотеки превышает 16 МБ.');
  const output = new File(Paths.cache, `Auralis-${Date.now()}.auralis`); output.create({ overwrite: true }); const handle = output.open();
  let failure: Error | null = null;
  const zip = new Zip((error, data) => { if (error) { failure = error; return; } handle.writeBytesSync(data); });
  try {
    const header = new ZipPassThrough('manifest.json'); zip.add(header); header.push(json, true);
    for (const [name, file] of files) {
      const entry = new ZipPassThrough(name); zip.add(entry); const input = file.open(FileMode.ReadOnly);
      try { while ((input.offset || 0) < (input.size || 0)) { entry.push(await input.readBytes(CHUNK), false); if (failure) throw failure; } entry.push(new Uint8Array(), true); } finally { input.close(); }
    }
    zip.end(); if (failure) throw failure;
  } catch (error) { handle.close(); if (output.exists) output.delete(); throw error; }
  handle.close(); return output;
}
export async function inspectBackup(file: File, library: CachedLibrary): Promise<BackupPreview> {
  if (!file.exists || file.size > MAX_ARCHIVE || file.size < 32) throw new Error('Нет корректного архива размером до 3 ГБ.');
  let manifest: BackupManifest | null = null, error: Error | null = null, size = 0; const chunks: Uint8Array[] = [];
  const unzip = new Unzip(entry => {
    if (entry.name !== 'manifest.json') { if (!manifest) throw new Error('Первым файлом архива должен быть manifest.json.'); return; }
    if (chunks.length || manifest) throw new Error('Повторяющееся описание архива.');
    entry.ondata = (failure, data, final) => { if (failure) { error = failure; return; } size += data.length; if (size > MANIFEST_LIMIT) throw new Error('Слишком большой manifest.json.'); chunks.push(data); if (final) { const text = new Uint8Array(size); let offset = 0; for (const c of chunks) { text.set(c, offset); offset += c.length; } manifest = validateManifest(JSON.parse(strFromU8(text))); } }; entry.start();
  }); unzip.register(UnzipInflate);
  const input = file.open(FileMode.ReadOnly);
  try { while (!manifest && (input.offset || 0) < file.size) { unzip.push(await input.readBytes(CHUNK), false); if (error) throw error; } } finally { input.close(); }
  if (!manifest) throw new Error('В архиве нет описания медиатеки.');
  const data = manifest as BackupManifest;
  return { file, manifest: data, tracks: data.tracks.length, playlists: data.playlists.length, favorites: data.favorites.length, history: data.history.length, audioBytes: data.tracks.reduce((n,t) => n+(t.audio?.size || 0),0), missingAudio: data.tracks.filter(t => !t.audio).length, conflicts: data.tracks.filter(t => library.tracks.some(known => (known.contentHash || known.id.replace(/^local-/,'')) === t.contentHash)).length };
}
export async function extractBackup(preview: BackupPreview, directory: Directory): Promise<Record<string,string>> {
  directory.create({ intermediates: true, idempotent: true }); const allowed = new Map<string, BackupAsset>();
  for (const track of preview.manifest.tracks) for (const asset of [track.audio, track.cover]) if (asset) allowed.set(asset.entry, asset);
  const assets: Record<string,string> = {}, seen = new Set<string>(), handles = new Set<ReturnType<File['open']>>(), created: File[] = [];
  let failure: Error | null = null, inflated = 0, manifestSeen = false;
  const unzip = new Unzip(entry => {
    if (seen.has(entry.name)) throw new Error('Повторяющийся файл архива.'); seen.add(entry.name);
    if (entry.name === 'manifest.json') { manifestSeen = true; return; }
    const expected = allowed.get(entry.name); if (!manifestSeen || !expected) throw new Error('Архив содержит неизвестный файл.');
    const target = new File(directory, `${randomUUID()}-${entry.name.split('/').pop()!}`); target.create(); created.push(target); const output = target.open(); handles.add(output); let bytes = 0;
    entry.ondata = (error, data, final) => { if (error) { failure = error; return; } bytes += data.length; inflated += data.length; if (bytes > expected.size || inflated > MAX_ARCHIVE) throw new Error('Архив превышает объявленный размер.'); output.writeBytesSync(data); if (final) { output.close(); handles.delete(output); if (bytes !== expected.size) throw new Error('Файл в архиве обрезан.'); assets[entry.name] = target.uri; } }; entry.start();
  }); unzip.register(UnzipInflate);
  const input = preview.file.open(FileMode.ReadOnly);
  try {
    while ((input.offset || 0) < preview.file.size) { const data = await input.readBytes(CHUNK); unzip.push(data, (input.offset || 0) >= preview.file.size); if (failure) throw failure; }
    if (handles.size || !manifestSeen || [...allowed.keys()].some(name => !assets[name])) throw new Error('Архив неполный.');
    for (const [name, uri] of Object.entries(assets)) if ((await new File(uri).digest('SHA-256')).toLowerCase() !== allowed.get(name)!.sha256) throw new Error('Не совпала контрольная сумма файла.');
    return assets;
  } catch (error) { for (const h of handles) h.close(); for (const f of created) if (f.exists) f.delete(); throw error; } finally { input.close(); }
}
