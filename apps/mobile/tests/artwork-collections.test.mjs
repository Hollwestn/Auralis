import test from 'node:test';
import assert from 'node:assert/strict';
import { artworkSource } from '../src/core/artwork.ts';
import { smartCollection } from '../src/core/collections.ts';

test('local artwork is never prefixed with a server or bearer credentials', () => {
  assert.deepEqual(artworkSource({coverUrl:'file:///owned/cover.png'}, null),{uri:'file:///owned/cover.png'});
  const session={server:'https://music.example',token:'private'};
  assert.deepEqual(artworkSource({coverUrl:'/api/v1/tracks/track-id/cover'},session),{uri:'https://music.example/api/v1/tracks/track-id/cover',headers:{Authorization:'Bearer private'}});
  assert.equal(artworkSource({coverUrl:'https://untrusted.example/cover'},session),undefined);
  assert.equal(artworkSource({coverUrl:'//untrusted.example/cover'},session),undefined);
  assert.equal(artworkSource({coverUrl:'/api/v1/tracks/id/cover'},{...session,mode:'local'}),undefined);
});
test('smart collections distinguish recent imports, short skips and listening time', () => {
  const now=Date.parse('2026-10-22T12:00:00Z');
  const library={tracks:[{id:'a',title:'A',importedAt:'2026-10-22T10:00:00Z'},{id:'b',title:'B',importedAt:'2026-01-01T00:00:00Z'},{id:'c',title:'C'}],history:[{trackId:'a',playedSeconds:1,playedAt:'2026-10-22T11:00:00Z'},{trackId:'b',playedSeconds:30,playedAt:'2026-10-21T11:00:00Z'},{trackId:'c',playedSeconds:80,playedAt:'2026-01-01T00:00:00Z'}]};
  assert.deepEqual(smartCollection(library,'recent',now).map(t=>t.id),['a']);
  assert.deepEqual(smartCollection(library,'rediscover',now).map(t=>t.id),['a','c']);
  assert.deepEqual(smartCollection(library,'mostPlayed',now).map(t=>t.id),['c','b']);
  assert.deepEqual(library.tracks.map(t=>t.id),['a','b','c']);
});
