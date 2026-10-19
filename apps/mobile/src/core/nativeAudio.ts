import type { AudioPlaylist } from 'expo-audio';

// Native SharedObjects are imperative audio handles, separate from React state.
export function setNativeVolume(player: AudioPlaylist, volume: number) { player.volume = Math.max(0, Math.min(1, volume)); }
export function setNativeRate(player: AudioPlaylist, rate: number) { player.playbackRate = rate; }
export function setNativeLoop(player: AudioPlaylist, loop: boolean) { player.loop = loop ? 'single' : 'none'; }
export function setNativeRepeat(player: AudioPlaylist, repeat: 'OFF' | 'ALL' | 'ONE') { player.loop = repeat === 'ONE' ? 'single' : repeat === 'ALL' ? 'all' : 'none'; }
