module.exports = function patchQueue({ edit, audio, root, kotlin }) {
  const fs = require('node:fs'), path = require('node:path');
  const android = path.join(audio,kotlin,'AudioPlaylist.kt');
  let code=fs.readFileSync(android,'utf8').replace(/\r\n/g,'\n');
  if(!code.includes('// AURALIS_QUEUE_EDITS')){
    const method=`  // AURALIS_QUEUE_EDITS
  fun moveAuralisQueue(from: Int, to: Int) {
    if (from !in sources.indices || to !in sources.indices || from == to) return
    val current = ref.currentMediaItemIndex
    previousMediaItemIndex = if (current == from) to else if (from < current && to >= current) current - 1 else if (from > current && to <= current) current + 1 else current
    sources.add(to, sources.removeAt(from))
    ref.moveMediaItem(from, to)
    sendStatusUpdate()
  }
`;
    code=code.replace('  fun next() {',method+'\n  fun next() {');fs.writeFileSync(android,code);
  }
  const androidSleep='      AsyncFunction("setAuralisSleepTimer") { playlist: AudioPlaylist, seconds: Double -> playlist.auralisSleep.set(seconds) }.runOnQueue(Queues.MAIN)';
  edit(kotlin+'AudioModule.kt',androidSleep,androidSleep+`\n      Function("moveAuralisQueue") { playlist: AudioPlaylist, from: Int, to: Int -> runOnMain { playlist.moveAuralisQueue(from, to) } }`);
  const swift=path.join(audio,'ios/AudioPlaylist.swift');code=fs.readFileSync(swift,'utf8').replace(/\r\n/g,'\n');
  if(!code.includes('// AURALIS_QUEUE_EDITS')){
    const start=code.indexOf('  func insert(source: AudioSource, at index: Int) {'),end=code.indexOf('  func clear() {',start);
    if(start<0||end<0)throw new Error('Auralis: iOS queue template changed');
    const methods=fs.readFileSync(path.join(root,'native/audio/AuralisQueue.swift.inc'),'utf8');
    code=code.slice(0,start)+methods+'\n'+code.slice(end);fs.writeFileSync(swift,code);
  }
  const swiftSleep=`      AsyncFunction("setAuralisSleepTimer") { (playlist: AudioPlaylist, seconds: Double) in
        playlist.auralisSleep.set(seconds, player: playlist.ref)
      }.runOnQueue(.main)`;
  edit('ios/AudioModule.swift',swiftSleep,swiftSleep+`\n      Function("moveAuralisQueue") { (playlist: AudioPlaylist, from: Int, to: Int) in playlist.moveAuralisQueue(from, to) }`);
};
