import test from 'node:test';
import assert from 'node:assert/strict';
import { AuralisApi, ApiError, normalizeServer } from '../src/core/api.ts';
import { accountPath, trackFilename } from '../src/core/paths.ts';

test('tokens are only sent to HTTPS or explicit private-network HTTP servers', () => {
  assert.equal(normalizeServer(' https://music.example.com/ '), 'https://music.example.com');
  assert.equal(normalizeServer('http://192.168.0.4:8080'), 'http://192.168.0.4:8080');
  assert.equal(normalizeServer('http://10.0.2.2:8080'), 'http://10.0.2.2:8080');
  for (const value of ['http://music.example.com', 'https://user:password@example.com', 'https://example.com/api', 'https://example.com?token=bad', 'ftp://localhost', 'http://172.32.0.1']) assert.throws(() => normalizeServer(value));
});
test('offline caches are isolated by server AND user, with traversal-safe path segments', () => {
  const first = accountPath('https://one.example', '../alice');
  assert.notDeepEqual(first, accountPath('https://two.example', '../alice'));
  assert.notDeepEqual(first, accountPath('https://one.example', 'bob'));
  assert(first.every(part => /^[a-f0-9]+$/.test(part) || part === 'account'));
  assert(first.every(part => part.length <= 96));
  assert.throws(() => trackFilename('../../secret', 'mp3'));
  assert.equal(trackFilename('a123-456', 'flac'), 'a123-456.flac');
});
test('API uses Authorization header, never query-string credentials, and supports empty success bodies', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, init) => {
      assert.equal(url, 'https://music.example/api/v1/favorites/track-1');
      assert.equal(init.headers.get('Authorization'), 'Bearer secret');
      assert.equal(init.method, 'PUT');
      return new Response(null, { status: 204 });
    };
    const api = new AuralisApi('https://music.example', 'secret');
    assert.equal(await api.favorite('track-1', true), undefined);
    assert(!api.stream('track-1').includes('secret'));
  } finally { globalThis.fetch = original; }
});
test('authentication sends required registration fields and preserves server scope', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, init) => {
      assert.equal(url, 'https://music.example/api/v1/auth/register');
      assert.deepEqual(JSON.parse(init.body), { email: 'alice@example.com', password: 'long-password', displayName: 'Alice' });
      assert.equal(init.headers.get('Authorization'), null);
      return Response.json({ token: 'new-token', user: { id: 'u1', displayName: 'Alice', email: 'alice@example.com' } });
    };
    const value = await new AuralisApi('https://music.example').authenticate('register', ' alice@example.com ', 'long-password', ' Alice ');
    assert.equal(value.server, 'https://music.example'); assert.equal(value.user.id, 'u1');
  } finally { globalThis.fetch = original; }
});
test('catalog follows pagination instead of silently losing tracks after the first page', async () => {
  const original = globalThis.fetch; const offsets = [];
  try {
    globalThis.fetch = async (url) => {
      const offset = Number(new URL(url).searchParams.get('offset')); offsets.push(offset);
      return Response.json(Array.from({ length: offset ? 3 : 200 }, (_, index) => ({ id: `t${offset + index}` })));
    };
    const result = await new AuralisApi('https://music.example', 'token').catalog('jazz & soul');
    assert.equal(result.length, 203); assert.deepEqual(offsets, [0, 200]);
  } finally { globalThis.fetch = original; }
});
test('sync conflicts preserve HTTP status for explicit conflict handling', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => Response.json({ detail: 'Version conflict' }, { status: 409 });
    await assert.rejects(() => new AuralisApi('https://music.example', 'token').saveSync({ version: 1, settings: {}, playback: {} }), failure => failure instanceof ApiError && failure.status === 409);
  } finally { globalThis.fetch = original; }
});
