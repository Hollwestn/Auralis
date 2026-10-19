import { defaultIdleSpeed, defaultPlayingSpeed, normalizeSpeed } from './motion.ts';
import { cinematicFragmentShader } from './cinematic.generated.ts';
export const visualModeKeys = ['BLACK_HOLE', 'NEBULA_DRIFT', 'RESONANCE', 'PHOTON_TUNNEL', 'PRISM_BLOOM', 'MAGNETIC_FIELD', 'OCEAN_CAUSTICS', 'FRACTAL_CATHEDRAL'] as const;
export const visualModes = ['Black Hole', 'Nebula Drift', 'Resonance', 'Photon Tunnel', 'Prism Bloom', 'Magnetic Field', 'Ocean Caustics', 'Fractal Cathedral'] as const;
export type VisualMode = typeof visualModes[number];
export type VisualQuality = 'Eco' | 'Balanced' | 'Ultra';
export const qualityRenderScale: Record<VisualQuality, number> = { Eco: .55, Balanced: .8, Ultra: 1 };
export type VisualPreferences = { mode: VisualMode; quality: VisualQuality; intensity: number; enabled: boolean; idleSpeed: number; playingSpeed: number; fps?: 15 | 30 | 45 | 60 };
export const vertexShader = `attribute vec2 position; void main(){gl_Position=vec4(position,0.0,1.0);}`;
export const fragmentShader = cinematicFragmentShader;

const legacyModes = ['LIQUID_CORE','PARTICLE_UNIVERSE','AURORA','SPECTRUM_WAVES','REACTIVE_ALBUM_ART','MINIMAL_PULSE','AMBIENT_FLOW','Liquid Core','Particle Universe','Aurora','Spectrum Waves','Reactive Album Art','Minimal Pulse','Ambient Flow'];
export function normalizeVisualMode(value: unknown): VisualMode | undefined {
  const index = visualModeKeys.indexOf(value as typeof visualModeKeys[number]);
  if (index >= 0) return visualModes[index];
  if (visualModes.includes(value as VisualMode)) return value as VisualMode;
  return legacyModes.includes(String(value)) ? 'Black Hole' : undefined;
}
export function normalizeVisualPreferences(value: unknown): VisualPreferences {
  const input = value && typeof value === 'object' ? value as Partial<VisualPreferences> : {};
  return { mode: normalizeVisualMode(input.mode) || 'Black Hole', quality: ['Eco','Balanced','Ultra'].includes(String(input.quality)) ? input.quality! : 'Balanced', intensity: typeof input.intensity === 'number' && Number.isFinite(input.intensity) ? Math.max(0, Math.min(2, input.intensity)) : 1, idleSpeed: normalizeSpeed(input.idleSpeed, defaultIdleSpeed), playingSpeed: normalizeSpeed(input.playingSpeed, defaultPlayingSpeed), enabled: typeof input.enabled === 'boolean' ? input.enabled : true, fps: [15,30,45,60].includes(input.fps || 0) ? input.fps : 30 };
}
