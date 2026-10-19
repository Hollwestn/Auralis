import type { ThemeName } from './types';

export const palette = {
  background: '#08090B', surface: '#111318', raised: '#1A1D23', border: '#292D35',
  text: '#F6F5F1', muted: '#9297A1', danger: '#FF8B8B',
};
export const themes: Record<ThemeName, { accent: string; tint: string }> = {
  ObsidianYellow: { accent: '#F8D34A', tint: '#29240E' },
  Monochrome: { accent: '#E8E8E8', tint: '#22252A' },
  CrimsonNoir: { accent: '#F16C79', tint: '#32151D' },
  DeepViolet: { accent: '#B18CFF', tint: '#22163B' },
  ArcticSilver: { accent: '#B8D7E4', tint: '#192831' },
  Jade: { accent: '#67D9AE', tint: '#163A32' },
  Amber: { accent: '#F6BE68', tint: '#3D2F1C' },
  Azure: { accent: '#68C8F3', tint: '#172F42' },
};
export function normalizeTheme(value: unknown): ThemeName | null {
  return (Object.keys(themes) as ThemeName[]).find(name => name.toLowerCase() === String(value).replace(/[^a-z]/gi, '').toLowerCase()) || null;
}
export function themeToWire(value: ThemeName) { return value.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase(); }
export function formatTime(seconds: number) {
  const value = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
}
