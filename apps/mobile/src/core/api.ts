import type { HistoryEntry, Playlist, Preferences, Session, SyncedState, Track, User, WaveEntry } from './types';

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

/** Reject credential-bearing URLs and insecure Internet origins before sending a token. */
export function normalizeServer(input: string): string {
  const url = new URL(input.trim());
  if (url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) throw new Error('Укажите только адрес сервера без пути, пароля и параметров.');
  const local = /^(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)$/.test(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) throw new Error('Для подключения через интернет нужен HTTPS. HTTP допустим только в локальной сети.');
  return url.origin;
}

export class AuralisApi {
  constructor(readonly server: string, readonly token?: string) {}
  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 20_000);
    try {
      const headers = new Headers(init.headers);
      if (this.token) headers.set('Authorization', `Bearer ${this.token}`);
      if (init.body) headers.set('Content-Type', 'application/json');
      const response = await fetch(`${this.server}/api/v1${path}`, { ...init, headers, signal: abort.signal });
      if (!response.ok) {
        let message = `Сервер ответил ${response.status}`;
        try { const body = await response.json(); message = body.message || body.detail || message; } catch { /* An upstream proxy may return HTML. */ }
        throw new ApiError(response.status, message);
      }
      const body = await response.text();
      return body ? JSON.parse(body) as T : undefined as T;
    } finally { clearTimeout(timer); }
  }
  async authenticate(kind: 'login' | 'register', email: string, password: string, displayName: string): Promise<Session> {
    const result = await this.request<{token: string; user: User}>(`/auth/${kind}`, { method: 'POST', body: JSON.stringify({ email: email.trim(), password, ...(kind === 'register' ? { displayName: displayName.trim() } : {}) }) });
    return { ...result, server: this.server };
  }
  tracks(q = '', offset = 0) { return this.request<Track[]>(`/tracks?q=${encodeURIComponent(q)}&offset=${offset}&limit=200`); }
  async catalog(q = '') {
    const result: Track[] = [];
    for (let offset = 0; ; offset += 200) {
      const page = await this.tracks(q, offset); result.push(...page);
      if (page.length < 200) return result;
    }
  }
  favorites() { return this.request<Track[]>('/favorites'); }
  favorite(id: string, value: boolean) { return this.request<void>(`/favorites/${encodeURIComponent(id)}`, { method: value ? 'PUT' : 'DELETE' }); }
  playlists() { return this.request<Playlist[]>('/playlists'); }
  createPlaylist(name: string) { return this.request<Playlist>('/playlists', { method: 'POST', body: JSON.stringify({ name }) }); }
  savePlaylist(playlist: Playlist) { return this.request<Playlist>(`/playlists/${encodeURIComponent(playlist.id)}`, { method: 'PUT', body: JSON.stringify({ name: playlist.name, trackIds: playlist.trackIds, version: playlist.version }) }); }
  deletePlaylist(id: string) { return this.request<void>(`/playlists/${encodeURIComponent(id)}`, { method: 'DELETE' }); }
  history() { return this.request<HistoryEntry[]>('/history'); }
  record(entry: Omit<HistoryEntry, 'id'>) { return this.request<void>('/history', { method: 'POST', body: JSON.stringify(entry) }); }
  clearHistory() { return this.request<void>('/history', { method: 'DELETE' }); }
  wave(mode = 'MY_WAVE') { return this.request<WaveEntry[]>(`/wave?mode=${encodeURIComponent(mode)}&limit=30`); }
  feedback(trackId: string, action: string) { return this.request<void>('/feedback', { method: 'POST', body: JSON.stringify({ trackId, action }) }); }
  preferences() { return this.request<Preferences>('/preferences'); }
  savePreferences(value: Preferences) { return this.request<Preferences>('/preferences', { method: 'PUT', body: JSON.stringify(value) }); }
  sync() { return this.request<SyncedState>('/sync'); }
  saveSync(value: Pick<SyncedState, 'version' | 'settings' | 'playback'>) { return this.request<SyncedState>('/sync', { method: 'PUT', body: JSON.stringify(value) }); }
  stream(id: string) { return `${this.server}/api/v1/tracks/${encodeURIComponent(id)}/stream`; }
  logout() { return this.request<void>('/auth/logout', { method: 'POST' }); }
  deleteAccount() { return this.request<void>('/account', { method: 'DELETE' }); }
}
