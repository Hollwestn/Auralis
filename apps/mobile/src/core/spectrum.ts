export type SpectrumFrame = { bands: number[]; rms: number; peak: number; sampleRate: number; windowSize: number };
export const audioTelemetry = { bands: new Float32Array(32), rms: 0, peak: 0, bass: 0, mid: 0, high: 0, flash: 0, sampleRate: 0, receivedAt: 0, supported: false };
const smoothed = [0,0,0]; let lastRead=0;
export function acceptSpectrum(frame: SpectrumFrame) {
  if (frame.bands.length !== 32 || !Number.isFinite(frame.rms) || frame.bands.some(value => !Number.isFinite(value))) return;
  const now=Date.now(),previousBass=audioTelemetry.bass,previousRms=audioTelemetry.rms;
  const elapsed=audioTelemetry.receivedAt?Math.max(0,now-audioTelemetry.receivedAt):1000;
  let flux=0;
  for (let index = 0; index < 32; index++) {
    const next=Math.min(1, Math.max(0, frame.bands[index]));
    flux+=Math.max(0,next-audioTelemetry.bands[index]);audioTelemetry.bands[index]=next;
  }
  audioTelemetry.rms = frame.rms; audioTelemetry.peak = frame.peak; audioTelemetry.sampleRate = frame.sampleRate; audioTelemetry.receivedAt = Date.now();
  const mean = (from: number, to: number) => audioTelemetry.bands.slice(from, to).reduce((sum, value) => sum + value, 0) / (to - from);
  audioTelemetry.bass = Math.pow(Math.max(...audioTelemetry.bands.slice(0,12)),.72); audioTelemetry.mid = mean(12, 24); audioTelemetry.high = mean(24, 32);
  const attack=Math.min(1,Math.max(0,flux/32-.008)*6+Math.max(0,audioTelemetry.bass-previousBass-.025)*1.5+Math.max(0,frame.rms-previousRms-.015)*2.5);
  audioTelemetry.flash=Math.max(audioTelemetry.flash*Math.exp(-elapsed/180),attack);
}
export function clearSpectrum() { audioTelemetry.bands.fill(0); audioTelemetry.rms = 0; audioTelemetry.peak = 0; audioTelemetry.bass = 0; audioTelemetry.mid = 0; audioTelemetry.high = 0; audioTelemetry.flash=0; audioTelemetry.receivedAt = 0;smoothed.fill(0);lastRead=0; }
export function readSpectrumUniforms(now = Date.now(), playing = true) {
  const age = now - audioTelemetry.receivedAt;
  const release = playing && age < 500 ? Math.pow(Math.max(0, 1 - Math.max(0, age - 80) / 420), 2) : 0;
  const dt=lastRead?Math.max(0,Math.min(.1,(now-lastRead)/1000)):.033;lastRead=now;
  const features=[audioTelemetry.bass,audioTelemetry.mid,audioTelemetry.high];
  for(let i=0;i<3;i++){const target=features[i]*release;smoothed[i]+=(target-smoothed[i])*(1-Math.exp(-dt/(target>smoothed[i]?.055:.28)));}
  if(!release) smoothed.fill(0);
  return { bands: Float32Array.from(audioTelemetry.bands, value => value * release), levels: [...smoothed, audioTelemetry.rms * release], flash: playing&&age<500?audioTelemetry.flash*Math.exp(-Math.max(0,age)/180):0 };
}
