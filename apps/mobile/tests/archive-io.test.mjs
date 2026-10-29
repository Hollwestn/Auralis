import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL,fileURLToPath } from 'node:url';
import { createHash,randomUUID } from 'node:crypto';
import { registerHooks } from 'node:module';
import { zipSync,unzipSync } from 'fflate';

// Exercise the real archive streaming and restore journal with a native filesystem adapter.
const root=fs.mkdtempSync(path.join(os.tmpdir(),'auralis-archive-'));
const filename=value=>typeof value==='string'?(value.startsWith('file:')?fileURLToPath(value):value):fileURLToPath(value.uri);
class Directory {
 constructor(...parts){this.uri=pathToFileURL(path.join(...parts.map(filename))).href;}
 get exists(){return fs.existsSync(filename(this));}
 create(){fs.mkdirSync(filename(this),{recursive:true});}
}
class File {
 constructor(...parts){this.uri=pathToFileURL(path.join(...parts.map(filename))).href;}
 get exists(){return fs.existsSync(filename(this));} get size(){return fs.statSync(filename(this)).size;}
 get name(){return path.basename(filename(this));} get parentDirectory(){return new Directory(path.dirname(filename(this)));}
 create(){fs.writeFileSync(filename(this),'');} delete(){fs.unlinkSync(filename(this));} writeSync(value){fs.writeFileSync(filename(this),value);}
 text(){return Promise.resolve(fs.readFileSync(filename(this),'utf8'));}
 async copy(target){fs.copyFileSync(filename(this),filename(target));}
 moveSync(target){fs.renameSync(filename(this),filename(target));this.uri=target.uri;}
 async digest(){return createHash('sha256').update(fs.readFileSync(filename(this))).digest('hex');}
 open(mode){const handle=fs.openSync(filename(this),mode==='read'?'r':'r+');return {offset:0,get size(){return fs.fstatSync(handle).size;},async readBytes(count){const bytes=new Uint8Array(Math.min(count,this.size-this.offset));fs.readSync(handle,bytes,0,bytes.length,this.offset);this.offset+=bytes.length;return bytes;},writeBytesSync(bytes){fs.writeSync(handle,bytes,0,bytes.length,this.offset);this.offset+=bytes.length;},close(){fs.closeSync(handle);}};}
}
let failAfter=-1;
const bridge={writeAuralisJsonAtomic(uri,value){if(failAfter===0){failAfter=-1;throw new Error('simulated interrupted write');}if(failAfter>0)failAfter--;const target=fileURLToPath(uri),temporary=target+'.native-new';fs.writeFileSync(temporary,value);fs.renameSync(temporary,target);}};
globalThis.__auralisArchiveAdapter={File,Directory,FileMode:{ReadOnly:'read'},Paths:{cache:new Directory(root),document:new Directory(root)},randomUUID,requireNativeModule:()=>bridge,WHEN_UNLOCKED_THIS_DEVICE_ONLY:1};
registerHooks({resolve(specifier,context,next){
 if(['expo','expo-file-system','expo-crypto','expo-secure-store'].includes(specifier))return {url:'data:text/javascript,'+encodeURIComponent('const a=globalThis.__auralisArchiveAdapter;export const {File,Directory,FileMode,Paths,randomUUID,requireNativeModule,WHEN_UNLOCKED_THIS_DEVICE_ONLY}=a;export async function getItemAsync(){return null;}export async function setItemAsync(){}export async function deleteItemAsync(){}'),shortCircuit:true};
 if(specifier.startsWith('.')&&!path.extname(specifier)&&context.parentURL?.includes('/src/'))return next(specifier+'.ts',context);
 return next(specifier,context);
}});
const {createBackup,inspectBackup,extractBackup}=await import('../src/core/backupIO.ts');
const {storage}=await import('../src/core/storage.ts');
const track={id:'local-'+'a'.repeat(64),title:'Music',artist:'Artist',album:'Album',genre:'Ambient',durationSeconds:8,mimeType:'audio/mpeg',sizeBytes:9,source:'LOCAL',coverUrl:null};
const library={tracks:[track],favorites:[track],playlists:[{id:'p',name:'My playlist',trackIds:[track.id]}],history:[],preferences:{genres:['Ambient'],artists:[]}};
test('streaming backup includes optional audio, validates checksums and rejects corrupt or extra files',async()=>{
 const audio=new File(root,'song.mp3');audio.create();audio.writeSync('an actual byte stream '.repeat(2000));
 const archive=await createBackup(library,[{track,uri:audio.uri}],{theme:'DeepViolet',token:'secret'},true),preview=await inspectBackup(archive,library);
 assert.equal(preview.tracks,1);assert.equal(preview.audioBytes,audio.size);assert.equal(preview.manifest.settings.token,undefined);
 const directory=new Directory(root,'restored');const extracted=await extractBackup(preview,directory);assert.deepEqual(fs.readFileSync(filename(extracted[preview.manifest.tracks[0].audio.entry])),fs.readFileSync(filename(audio)));
 const entries=unzipSync(fs.readFileSync(filename(archive))),audioName=preview.manifest.tracks[0].audio.entry;entries[audioName][0]^=1;
 const corrupt=new File(root,'corrupt.auralis');corrupt.writeSync(zipSync(entries));const bad=await inspectBackup(corrupt,library);await assert.rejects(()=>extractBackup(bad,new Directory(root,'bad')),/сумма/);assert.equal(fs.readdirSync(path.join(root,'bad')).length,0);
 entries['unexpected.txt']=new Uint8Array([1]);const extra=new File(root,'extra.auralis');extra.writeSync(zipSync(entries));await assert.rejects(()=>extractBackup({...preview,file:extra},new Directory(root,'extra')),/неизвестный/);
 const small=await createBackup(library,[{track,uri:audio.uri}],{},false);const metadata=await inspectBackup(small,library);assert.equal(metadata.missingAudio,1);assert.equal(metadata.audioBytes,0);
});
test('interrupted multi-file restore is recovered from a complete journal on next activation',async()=>{
 const session={server:'local://auralis',user:{id:'journal-test'}},state={theme:'Jade',playback:{queue:[track.id]}};
 failAfter=1;assert.throws(()=>storage.commitRestore(session,library,[],state),/interrupted/);
 await storage.recoverRestore(session);assert.equal((await storage.library(session)).tracks[0].title,'Music');assert.deepEqual(await storage.localState(session),state);assert.deepEqual(await storage.downloads(session),[]);
});
test.after(()=>{fs.rmSync(root,{recursive:true,force:true});delete globalThis.__auralisArchiveAdapter;});
