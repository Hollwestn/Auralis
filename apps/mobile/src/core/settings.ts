import { normalizeSpeed } from '../visuals/motion.ts';
import type { ThemeName } from './types';
import { normalizeTheme, themeToWire } from './theme.ts';
import { visualModeKeys, visualModes, normalizeVisualMode, type VisualPreferences } from '../visuals/shaders.ts';

export type Experience = { backgroundEnabled: boolean; backgroundAudioEnabled: boolean; animationsEnabled: boolean; reducedMotion: boolean; advancedMode: boolean };
export const defaultExperience: Experience = { backgroundEnabled: true, backgroundAudioEnabled: true, animationsEnabled: true, reducedMotion: false, advancedMode: false };
export function decodeSettings(settings: Record<string, unknown>, theme: ThemeName, visuals: VisualPreferences, experience: Experience) {
  const mode = normalizeVisualMode(settings.visualMode);
  const quality = settings.visualQuality === 'LOW' ? 'Eco' : settings.visualQuality === 'HIGH' ? 'Ultra' : settings.visualQuality === 'MEDIUM' ? 'Balanced' : visuals.quality;
  const fps = [15,30,45,60].includes(Number(settings.visualFps)) ? Number(settings.visualFps) as 15|30|45|60 : visuals.fps;
  return { theme: normalizeTheme(settings.theme) || theme,
    visuals: { ...visuals, mode: mode || visuals.mode, quality, fps, idleSpeed: normalizeSpeed(settings.visualIdleSpeed, visuals.idleSpeed), playingSpeed: normalizeSpeed(settings.visualPlayingSpeed, visuals.playingSpeed), intensity: typeof settings.visualIntensity === 'number' ? Math.max(0, Math.min(2, settings.visualIntensity)) : visuals.intensity, enabled: typeof settings.visualEnabled === 'boolean' ? settings.visualEnabled : visuals.enabled } as VisualPreferences,
    experience: Object.fromEntries(Object.entries(experience).map(([key, value]) => [key, typeof settings[key] === 'boolean' ? settings[key] : value])) as Experience };
}
export function encodeSettings(theme: ThemeName, visuals: VisualPreferences, experience: Experience) {
  return { theme: themeToWire(theme), visualMode: visualModeKeys[visualModes.indexOf(visuals.mode)], visualQuality: visuals.quality === 'Eco' ? 'LOW' : visuals.quality === 'Ultra' ? 'HIGH' : 'MEDIUM', visualFps: visuals.fps || (visuals.quality === 'Eco' ? 15 : visuals.quality === 'Ultra' ? 60 : 30), visualIdleSpeed: visuals.idleSpeed, visualPlayingSpeed: visuals.playingSpeed, visualIntensity: visuals.intensity, visualEnabled: visuals.enabled, ...experience };
}
