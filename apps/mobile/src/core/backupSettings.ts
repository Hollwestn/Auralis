import { normalizeAudioPreferences } from './audioPreferences.ts';
import { normalizeVisualPreferences, visualModeKeys, visualModes } from '../visuals/shaders.ts';
import { normalizeTheme, themeToWire } from './theme.ts';

const bool = (value: unknown, fallback: boolean) => value === true || value === 'true' ? true : value === false || value === 'false' ? false : fallback;
const number = (value: unknown, fallback: number) => value !== undefined && value !== '' && Number.isFinite(Number(value)) ? Number(value) : fallback;
const list = (value: unknown): string[] => { if (Array.isArray(value)) return value.filter((v): v is string => typeof v === 'string'); if (typeof value === 'string') { try { const parsed=JSON.parse(value); if(Array.isArray(parsed)) return list(parsed); } catch {} return value.split(',').map(v=>v.trim()).filter(Boolean); } return []; };

/** The archive uses Desktop's flat names; only portable preferences cross devices. */
export function exportBackupSettings(state: Record<string, unknown>, preferences: { genres: string[]; artists: string[] }) {
  const visuals=normalizeVisualPreferences(state.visuals),audio=normalizeAudioPreferences(state.audioPreferences),experience=(state.experience || {}) as Record<string,unknown>;
  return { ...Object.fromEntries(Object.entries(state).filter(([key])=>/^(volume|audio\.rate|eq\.(enabled|[0-9]|saved\.[0-9]))$/.test(key))),theme:themeToWire(normalizeTheme(state.theme)||'ObsidianYellow'), genres:preferences.genres.join(','),artists:preferences.artists.join(','),
    'visual.mode':visualModeKeys[visualModes.indexOf(visuals.mode)],'visual.quality':visuals.quality==='Eco'?'LOW':visuals.quality==='Ultra'?'HIGH':'MEDIUM','visual.fps':visuals.fps,'visual.intensity':visuals.intensity,'visual.idleSpeed':visuals.idleSpeed,'visual.playingSpeed':visuals.playingSpeed,'visual.enabled':visuals.enabled,'visual.adaptive':visuals.adaptive,
    'background.enabled':experience.backgroundEnabled!==false,'animations.enabled':experience.animationsEnabled!==false,reducedMotion:experience.reducedMotion===true,advanced:experience.advancedMode===true,
    'audio.gapless':audio.gapless,'audio.crossfadeSeconds':audio.crossfadeSeconds,'audio.normalization':audio.normalization,'audio.targetLufs':audio.targetLufs,'audio.disabledAlbums':JSON.stringify(audio.disabledAlbums),
    // iOS/Android-only preferences remain portable between phones.
    experience:state.experience || {},audioPreferences:audio,visuals };
}
export function importBackupSettings(settings: Record<string, unknown>) {
  const v=normalizeVisualPreferences(settings.visuals),a=normalizeAudioPreferences(settings.audioPreferences),experience=(settings.experience || {}) as Record<string,unknown>;
  return { ...settings,theme:normalizeTheme(settings.theme)||'ObsidianYellow',
    visuals:normalizeVisualPreferences({ ...v,mode:settings['visual.mode']??v.mode,quality:settings['visual.quality']==='LOW'?'Eco':settings['visual.quality']==='HIGH'?'Ultra':settings['visual.quality']==='MEDIUM'?'Balanced':v.quality,fps:number(settings['visual.fps'],v.fps||30),intensity:number(settings['visual.intensity'],v.intensity),idleSpeed:number(settings['visual.idleSpeed'],v.idleSpeed),playingSpeed:number(settings['visual.playingSpeed'],v.playingSpeed),enabled:bool(settings['visual.enabled'],v.enabled),adaptive:bool(settings['visual.adaptive'],v.adaptive!==false) }),
    experience:{ ...experience,backgroundEnabled:bool(settings['background.enabled'],experience.backgroundEnabled!==false),animationsEnabled:bool(settings['animations.enabled'],experience.animationsEnabled!==false),backgroundAudioEnabled:experience.backgroundAudioEnabled!==false,reducedMotion:bool(settings.reducedMotion,experience.reducedMotion===true),advancedMode:bool(settings.advanced,experience.advancedMode===true) },
    audioPreferences:normalizeAudioPreferences({ ...a,gapless:bool(settings['audio.gapless'],a.gapless),crossfadeSeconds:number(settings['audio.crossfadeSeconds'],a.crossfadeSeconds),normalization:bool(settings['audio.normalization'],a.normalization),targetLufs:number(settings['audio.targetLufs'],a.targetLufs),disabledAlbums:settings['audio.disabledAlbums']===undefined?a.disabledAlbums:list(settings['audio.disabledAlbums']) }),
    genres:list(settings.genres??settings['preferences.genres']),artists:list(settings.artists??settings['preferences.artists']) };
}
