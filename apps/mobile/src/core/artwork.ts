import type { Session, Track } from './types';

/** Local URIs never receive a server prefix or bearer token. */
export function artworkSource(track: Track | null, session: Session | null): { uri: string; headers?: Record<string, string> } | undefined {
  const value = track?.coverUrl;
  if (!value) return undefined;
  if (value.startsWith('file://')) return { uri: value };
  if (session && session.mode !== 'local' && /^\/api\/v1\/tracks\/[^/]+\/cover$/.test(value)) return { uri: session.server + value, headers: { Authorization: `Bearer ${session.token}` } };
  return undefined;
}
