import test from 'node:test';
import assert from 'node:assert/strict';
import { accountPath } from '../src/core/paths.ts';
import { localSession, isLocalSession, localAudioInfo, localTrackId, reconcileLocalLibrary, withoutLocalTrack, localWave } from '../src/core/local.ts';

const track = (id, artist = 'Artist') => ({ id, artist, title: id, album: '', genre: '', source: 'LOCAL', sizeBytes: 1, durationSeconds: 0, mimeType: 'audio/wav', coverUrl: null });
const empty = () => ({ tracks: [], favorites: [], playlists: [], history: [], preferences: { genres: [], artists: [] } });

test('local device profile needs no credentials and is isolated from every server cache', () => {
  assert(isLocalSession(localSession)); assert.equal(localSession.token, ''); assert.equal(localSession.user.email, '');
  const localPath = accountPath(localSession.server, localSession.user.id);
  assert.notDeepEqual(localPath, accountPath('https://music.example', localSession.user.id));
  assert(!isLocalSession({ server: 'https://music.example', user: { id: 'u1' }, token: 'token' }));
});

test('imports recognize audio names, reject non-audio files, and use content identity instead of filenames', () => {
  assert.deepEqual(localAudioInfo('Исполнитель - Песня.FLAC'), { extension: 'flac', mimeType: 'audio/flac', title: 'Песня', artist: 'Исполнитель' });
  assert.equal(localAudioInfo('Музыка.m4a').mimeType, 'audio/mp4');
  assert.throws(() => localAudioInfo('song.exe', 'audio/mpeg'));
  assert.throws(() => localAudioInfo('.mp3'));
  assert.equal(localTrackId('A'.repeat(64)), 'local-' + 'a'.repeat(64));
  assert.throws(() => localTrackId('../file'));
});

test('restart recovers a committed import and prunes missing files across playlists, favorites and history', () => {
  const a = track('a'), b = track('b'), c = track('c');
  const cache = { ...empty(), tracks: [a, b], favorites: [a, b], playlists: [{ id: 'p', name: 'P', trackIds: ['b', 'a', 'a'] }], history: [{ trackId: 'a' }, { trackId: 'b' }] };
  const restored = reconcileLocalLibrary(cache, [{ track: a, uri: 'file:///a' }, { track: c, uri: 'file:///c' }]);
  assert.deepEqual(restored.tracks.map(t => t.id), ['a', 'c']); assert.deepEqual(restored.favorites.map(t => t.id), ['a']);
  assert.deepEqual(restored.playlists[0].trackIds, ['a', 'a']); assert.deepEqual(restored.history, [{ trackId: 'a' }]);
  assert.deepEqual(cache.playlists[0].trackIds, ['b', 'a', 'a']);
});

test('deleting an import removes references and keeps unrelated tracks and playlists', () => {
  const a = track('a'), b = track('b');
  const result = withoutLocalTrack({ ...empty(), tracks: [a, b], favorites: [a], playlists: [{ id: 'p', trackIds: ['a', 'b', 'a'] }], history: [{ trackId: 'a' }] }, 'a');
  assert.deepEqual(result.tracks, [b]); assert.deepEqual(result.favorites, []); assert.deepEqual(result.history, []); assert.deepEqual(result.playlists[0].trackIds, ['b']);
});

test('local Wave uses only actual imported tracks, favorites, preferences and feedback without network', () => {
  const a = track('a'), b = track('b', 'Preferred'), c = track('c');
  const library = { ...empty(), tracks: [a, b, c], favorites: [a], preferences: { genres: [], artists: ['preferred'] } };
  assert.deepEqual(localWave(library, 'FAVORITES').map(e => e.track.id), ['a']);
  assert.equal(localWave(library, 'MY_WAVE', { a: 'LESS' })[0].track.id, 'b');
  assert(!localWave(library, 'MY_WAVE', { a: 'DISLIKE' }).some(e => e.track.id === 'a'));
  assert.deepEqual(localWave(empty()), []);
});
