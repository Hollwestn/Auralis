import test from 'node:test';
import assert from 'node:assert/strict';
import { visualModeKeys, visualModes, fragmentShader, normalizeVisualMode, normalizeVisualPreferences } from '../src/visuals/shaders.ts';
import { acceptSpectrum, audioTelemetry, clearSpectrum, readSpectrumUniforms } from '../src/core/spectrum.ts';
import { normalizeTheme, themeToWire } from '../src/core/theme.ts';

test('all eight required visual identities and themes have stable cross-device keys', () => {
  assert.deepEqual(visualModeKeys, ['BLACK_HOLE','NEBULA_DRIFT','RESONANCE','PHOTON_TUNNEL','PRISM_BLOOM','MAGNETIC_FIELD','OCEAN_CAUSTICS','FRACTAL_CATHEDRAL']);
  assert.equal(visualModes.length, 8);
  assert.equal(normalizeTheme('OBSIDIAN_YELLOW'), 'ObsidianYellow');
  assert.equal(normalizeTheme('AZURE'), 'Azure');
  assert.equal(themeToWire('CrimsonNoir'), 'CRIMSON_NOIR');
  assert(!fragmentShader.includes('vec3 liquidCore('));
});
test('shader audio uniforms are measured-frame values and become zero after stale data or pause', () => {
  clearSpectrum(); assert(readSpectrumUniforms().bands.every(value => value === 0));
  acceptSpectrum({ bands: Array(32).fill(.5), rms: .25, peak: .75, sampleRate: 48000, windowSize: 1024 });
  const received = audioTelemetry.receivedAt;
  const active = readSpectrumUniforms(received + 20);
  assert.equal(active.bands[4], .5); assert.equal(active.levels[3], .25);
  const releasing = readSpectrumUniforms(received + 250);
  assert(releasing.bands[4] > 0 && releasing.bands[4] < .5);
  assert(readSpectrumUniforms(received + 501).bands.every(value => value === 0));
  assert(readSpectrumUniforms(received + 20, false).levels.every(value => value === 0));
  acceptSpectrum({ bands: Array(32).fill(Number.NaN), rms: .3, peak: .4, sampleRate: 48000, windowSize: 1024 });
  assert.equal(audioTelemetry.rms, .25);
  clearSpectrum();
});
test('one narrow bass peak survives aggregation and is smoothed instead of jumping',()=>{
  clearSpectrum();const bands=Array(32).fill(0);bands[4]=.6;
  acceptSpectrum({bands,rms:.2,peak:.6,sampleRate:48000,windowSize:1024});
  const now=audioTelemetry.receivedAt;
  const first=readSpectrumUniforms(now).levels[0];
  const second=readSpectrumUniforms(now+33).levels[0];
  assert(first>.2&&first<.7);assert(second>first&&second-first<.3);
  assert(audioTelemetry.bass>.65,'a single bass band must not be averaged with silent low bands');
  assert(readSpectrumUniforms(now+501).levels.every(value=>value===0));clearSpectrum();
});

test('audio attacks flash once and decay during a sustained tone, with no clock-generated pulses',()=>{
  const original=Date.now;let now=1000;Date.now=()=>now;
  try {
    clearSpectrum();assert.equal(readSpectrumUniforms(now).flash,0);
    const frame={bands:Array(32).fill(.5),rms:.25,peak:.75,sampleRate:48000,windowSize:1024};
    acceptSpectrum(frame);const attack=readSpectrumUniforms(now).flash;assert(attack>.5);
    now+=180;const faded=readSpectrumUniforms(now).flash;assert(faded>0&&faded<attack*.4);
    for(let i=0;i<20;i++){now+=50;acceptSpectrum(frame);}
    assert(readSpectrumUniforms(now).flash<.01,'held audio must not continuously retrigger flashes');
    now+=50;acceptSpectrum({...frame,bands:Array(32).fill(.9),rms:.5});assert(readSpectrumUniforms(now).flash>.4);
    assert.equal(readSpectrumUniforms(now,false).flash,0);
    assert.equal(readSpectrumUniforms(now+501).flash,0);clearSpectrum();
  } finally {Date.now=original;clearSpectrum();}
});

test('removed graphics migrate safely and invalid persisted values cannot break startup', () => {
  for (const old of ['Liquid Core','LIQUID_CORE','AMBIENT_FLOW','Reactive Album Art']) assert.equal(normalizeVisualMode(old), 'Black Hole');
  assert.equal(normalizeVisualMode('NEBULA_DRIFT'), 'Nebula Drift');
  assert.deepEqual(normalizeVisualPreferences({mode: 'broken', quality: 'invalid', intensity: NaN, enabled: 'yes', fps: 500}), {adaptive:true, mode:'Black Hole', quality:'Balanced', intensity:1, enabled:true, fps:30, idleSpeed:1, playingSpeed:.75});
});

test('speed settings persist through sync and preserve defaults for old devices', async () => {
  const {decodeSettings,encodeSettings,defaultExperience}=await import('../src/core/settings.ts');
  const {visualClockRate}=await import('../src/visuals/motion.ts');
  const defaults=normalizeVisualPreferences(null);
  assert.equal(visualClockRate(false, defaults.idleSpeed, defaults.playingSpeed), .18);
  assert.equal(visualClockRate(true, defaults.idleSpeed, defaults.playingSpeed), .75);
  const edited={...defaults,idleSpeed:1.5,playingSpeed:.5};
  const wire=encodeSettings('CrimsonNoir',edited,defaultExperience);
  const restored=decodeSettings(wire,'ObsidianYellow',defaults,defaultExperience).visuals;
  assert.equal(restored.idleSpeed,1.5);assert.equal(restored.playingSpeed,.5);
  assert.equal(visualClockRate(false,restored.idleSpeed,restored.playingSpeed),.27);
  assert.equal(visualClockRate(true,restored.idleSpeed,restored.playingSpeed),.5);
  const legacy=decodeSettings({},'ObsidianYellow',defaults,defaultExperience).visuals;
  assert.equal(legacy.idleSpeed,1);assert.equal(legacy.playingSpeed,.75);
  assert.equal(normalizeVisualPreferences({idleSpeed:NaN,playingSpeed:Infinity}).playingSpeed,.75);
  assert.equal(visualClockRate(false,0,1),0);assert.equal(visualClockRate(true,1,0),0);
});
