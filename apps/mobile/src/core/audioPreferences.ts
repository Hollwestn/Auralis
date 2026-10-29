export type AudioPreferences = { gapless: boolean; crossfadeSeconds: number; normalization: boolean; targetLufs: number; disabledAlbums: string[] };
export const defaultAudioPreferences: AudioPreferences = { gapless: true, crossfadeSeconds: 0, normalization: false, targetLufs: -16, disabledAlbums: [] };
export function normalizeAudioPreferences(value: unknown): AudioPreferences {
  const data=value&&typeof value==='object'?value as Partial<AudioPreferences>:{};
  return {gapless:typeof data.gapless==='boolean'?data.gapless:true,crossfadeSeconds:Number.isFinite(data.crossfadeSeconds)?Math.max(0,Math.min(12,Number(data.crossfadeSeconds))):0,normalization:data.normalization===true,targetLufs:Number.isFinite(data.targetLufs)?Math.max(-23,Math.min(-12,Number(data.targetLufs))):-16,disabledAlbums:Array.isArray(data.disabledAlbums)?data.disabledAlbums.filter((a):a is string=>typeof a==='string').slice(0,10000):[]};
}
export const albumKey=(track:{artist:string;album:string})=>`${track.artist.trim().toLowerCase()}\n${track.album.trim().toLowerCase()}`;
export function normalizationGainDb(lufs:number|undefined,peak:number|undefined,target=-16){if(lufs===undefined||peak===undefined||!Number.isFinite(lufs)||!Number.isFinite(peak)||lufs< -70)return 0;return Math.max(-24,Math.min(12,target-lufs,-2-peak));}
