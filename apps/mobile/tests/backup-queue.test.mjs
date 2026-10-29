import test from 'node:test';
import assert from 'node:assert/strict';
import { validateManifest, mergeBackup, backupConflicts } from '../src/core/backupFormat.ts';
import { moveQueue, removeQueue, insertQueue } from '../src/core/queue.ts';
const hash='a'.repeat(64), entry={id:'desktop-uuid',contentHash:hash,title:'A',artist:'B',album:'C',genre:'D',durationSeconds:12};
const fixture=()=>({format:'auralis.backup',version:1,createdAt:'2026-10-28T00:00:00Z',tracks:[entry],favorites:['desktop-uuid'],playlists:[{id:'p',name:'My list',trackIds:['desktop-uuid']}],history:[{trackId:'desktop-uuid',playedSeconds:4,playedAt:'2026-10-28T00:00:00Z'}],settings:{theme:'DEEP_VIOLET',serverToken:'secret'},playback:{trackId:'desktop-uuid',queue:['desktop-uuid','desktop-uuid'],queueIndex:1,positionSeconds:3}});
const empty=()=>({tracks:[],favorites:[],playlists:[],history:[],preferences:{genres:[],artists:[]}});
test('portable metadata maps Desktop ids to local hashes, retains missing files and duplicate queue slots',()=>{
 const m=validateManifest(fixture());assert.equal(m.settings.serverToken,undefined);const r=mergeBackup(empty(),[],{},m,{},true,()=> 'new-id');assert.equal(r.library.tracks[0].missing,true);assert.equal(r.library.favorites[0].id,`local-${hash}`);assert.equal(r.library.playlists[0].trackIds[0],`local-${hash}`);assert.equal(r.state.playback.queueIndex,1);assert.equal(r.state.playback.queue.length,2);
 const again=mergeBackup(r.library,r.downloads,r.state,m,{},false,()=> 'new-id');assert.equal(again.library.tracks.length,1);assert.equal(again.library.history.length,1);assert.equal(again.library.playlists.length,1);
});
test('backup rejects invalid references, unsafe asset paths and newer formats before restore',()=>{
 for(const mutate of [m=>m.version=2,m=>m.favorites=['unknown'],m=>m.tracks[0]={...m.tracks[0],durationSeconds:-1},m=>m.tracks[0]={...m.tracks[0],audio:{entry:'../secret',sha256:hash,size:1}},m=>m.history[0].playedAt='bad']){const m=fixture();mutate(m);assert.throws(()=>validateManifest(m));}
});
test('queue transformations preserve the selected occurrence even when tracks are duplicated',()=>{
 const s={items:['a','b','a','c'],index:2};assert.deepEqual(moveQueue(s,0,3),{items:['b','a','c','a'],index:1});assert.deepEqual(moveQueue(s,2,0),{items:['a','a','b','c'],index:0});assert.deepEqual(removeQueue(s,0),{items:['b','a','c'],index:1});assert.deepEqual(insertQueue(s,'x',1),{items:['a','x','b','a','c'],index:3});assert.deepEqual(removeQueue({items:['a'],index:0},0),{items:[],index:-1});assert.throws(()=>moveQueue(s,10,0));
});

test('conflicting metadata and playlist changes are shown before applying a transfer',()=>{
 const manifest=validateManifest(fixture()),local=mergeBackup(empty(),[],{},manifest,{},true,()=> 'uuid').library;local.tracks[0].title='My correction';local.playlists[0].trackIds=[];
 const differences=backupConflicts(local,manifest);assert.ok(differences.some(d=>d.local.includes('My correction')&&d.incoming==='A'));assert.ok(differences.some(d=>d.key.startsWith('playlist:')));assert.equal(local.tracks[0].title,'My correction');
});
