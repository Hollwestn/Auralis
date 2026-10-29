import { Buffer } from 'node:buffer';
import test from 'node:test';
import assert from 'node:assert/strict';
import { exportBackupSettings,importBackupSettings } from '../src/core/backupSettings.ts';
import { decodePairing } from '../src/core/peerProtocol.ts';
import { FrameBudget,budgetScale,budgetFps } from '../src/visuals/budget.ts';
import { themes,palette } from '../src/core/theme.ts';

test('archive settings survive Desktop string values and phone export without accounts',()=>{
 const imported=importBackupSettings({theme:'CRIMSON_NOIR','visual.mode':'PHOTON_TUNNEL','visual.quality':'HIGH','visual.adaptive':'false','visual.idleSpeed':'0.5','visual.playingSpeed':'0.75','animations.enabled':'false','audio.normalization':'true','audio.targetLufs':'-18','audio.disabledAlbums':'["a\\nb"]','preferences.genres':'ambient,jazz'});
 assert.equal(imported.theme,'CrimsonNoir');assert.equal(imported.visuals.mode,'Photon Tunnel');assert.equal(imported.visuals.adaptive,false);assert.equal(imported.experience.animationsEnabled,false);assert.equal(imported.audioPreferences.normalization,true);assert.deepEqual(imported.audioPreferences.disabledAlbums,['a\nb']);
 const exported=exportBackupSettings({...imported,token:'never export',server:'https://private.example'}, {genres:['ambient','jazz'],artists:['Artist']});
 assert.equal(exported.theme,'CRIMSON_NOIR');assert.equal(exported['visual.mode'],'PHOTON_TUNNEL');assert.equal(exported.token,undefined);assert.equal(exported.server,undefined);const again=importBackupSettings(exported);assert.equal(again.audioPreferences.targetLufs,-18);assert.equal(again.visuals.idleSpeed,.5);assert.deepEqual(again.genres,['ambient','jazz']);
});
test('pairing accepts only fresh, pinned private IPv4 sessions',()=>{
 const now=Date.now(),info={v:1,host:'192.168.1.3',port:53000,certificate:'a'.repeat(64),secret:'x'.repeat(43),expires:new Date(now+60000).toISOString()};
 const encode=v=>'auralis-pair:'+Buffer.from(JSON.stringify(v)).toString('base64url');assert.equal(decodePairing(encode(info),now).host,info.host);
 for(const patch of [{host:'example.com'},{host:'8.8.8.8'},{host:'127.0.0.1'},{host:'192.168.01.3'},{certificate:''},{secret:'x'},{port:65536},{expires:new Date(now-1).toISOString()},{expires:new Date(now+3600000).toISOString()}])assert.throws(()=>decodePairing(encode({...info,...patch}),now));
});
test('graphics respond to sustained load with hysteresis and thermal constraints',()=>{
 const budget=new FrameBudget();for(let n=0;n<50;n++)budget.sample(50,50,60,n*100);assert.ok(budget.level>=2);const degraded=budget.level;
 for(let n=50;n<65;n++)budget.sample(1,1,60,n*100);assert.equal(budget.level,degraded);for(let n=65;n<250;n++)budget.sample(1,1,60,n*100);assert.ok(budget.level<degraded);
 budget.sample(1,1,60,26000,true,0);assert.ok(budget.level>=2);budget.sample(1,1,60,26001,false,6);assert.equal(budget.level,3);assert.equal(budgetFps(60,3),15);assert.equal(budgetScale[0],1);
});
const luminance=hex=>hex.slice(1).match(/../g).map(s=>parseInt(s,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0);
const contrast=(a,b)=>{const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
test('approved palettes keep readable normal text and accent button labels',()=>{
 for(const theme of Object.values(themes)){assert.ok(contrast(theme.accent,'#0C191E')>=4.5);assert.ok(contrast(theme.accent,palette.surface)>=4.5);}
 for(const background of [palette.background,palette.surface,palette.raised]){assert.ok(contrast(palette.text,background)>=4.5);assert.ok(contrast(palette.muted,background)>=4.5);}
});
