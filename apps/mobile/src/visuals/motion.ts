export const defaultIdleSpeed = 1;
export const defaultPlayingSpeed = .75;

export function normalizeSpeed(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(2, value)) : fallback;
}

/** Speed affects scene time only; audio envelopes and transient flashes use real time. */
export function visualClockRate(playing: boolean, idleSpeed: number, playingSpeed: number): number {
  return playing ? normalizeSpeed(playingSpeed, defaultPlayingSpeed) : .18 * normalizeSpeed(idleSpeed, defaultIdleSpeed);
}
