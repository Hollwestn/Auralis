module.exports=function({edit,audio,root,kotlin}){
 const fs=require('node:fs'),path=require('node:path');
 fs.copyFileSync(path.join(root,'native/audio/AuralisTransitions.kt'),path.join(audio,kotlin,'AuralisTransitions.kt'));
 fs.copyFileSync(path.join(root,'native/audio/AuralisTransitions.swift'),path.join(audio,'ios/AuralisTransitions.swift'));
 const android='  val auralisSleep = AuralisSleepTimer(ref)';
 edit(kotlin+'AudioPlaylist.kt',android,android+`
  val auralisTransitions = AuralisTransitions(context, ref, dataSourceFactory, { index -> sources.getOrNull(index)?.let { createMediaItemForSource?.invoke(it) } }, { auralisSleep.active })`);
 edit(kotlin+'AudioPlaylist.kt','    startUpdating()','    startUpdating()\n    auralisSleep.onStart = { auralisTransitions.cancelFade() }');
 edit(kotlin+'AudioPlaylist.kt','    auralisSleep.cancel()','    auralisSleep.cancel()\n    auralisTransitions.close()');
 const move='      Function("moveAuralisQueue") { playlist: AudioPlaylist, from: Int, to: Int -> runOnMain { playlist.moveAuralisQueue(from, to) } }';
 edit(kotlin+'AudioModule.kt',move,move+`
      Function("setAuralisAudioOptions") { playlist: AudioPlaylist, seconds: Double, gains: List<Double>, blocked: List<Boolean> -> runOnMain { playlist.auralisTransitions.configure(seconds, gains, blocked) } }
      Function("setAuralisVolume") { playlist: AudioPlaylist, volume: Double -> runOnMain { playlist.auralisTransitions.volume(volume.toFloat()) } }`);
 const swift='  let auralisSleep = AuralisSleepTimer()';
 edit('ios/AudioPlaylist.swift',swift,swift+`
  lazy var auralisTransitions = AuralisTransitions(player: ref, spectrum: auralisSpectrum,
    source: { [weak self] index in guard let self, self.sources.indices.contains(index) else { return nil }; return AudioUtils.createAVPlayerItem(from: self.sources[index]) },
    index: { [weak self] in self?.currentTrackIndex ?? 0 },
    nextIndex: { [weak self] in guard let self, !self.sources.isEmpty else { return nil }; let next=self.currentTrackIndex+1; return next<self.sources.count ? next : self.loopMode == .all ? 0 : nil },
    sleeping: { [weak self] in self?.auralisSleep.active != false || self?.loopMode == .single })`);
 edit('ios/AudioPlaylist.swift','    setupPublisher()','    auralisSleep.onStart = { [weak self] in self?.auralisTransitions.cancelFade() }\n    setupPublisher()');
 edit('ios/AudioPlaylist.swift','    auralisSleep.cancel()','    auralisSleep.cancel()\n    auralisTransitions.close()');
 edit('ios/AudioPlaylist.swift','    sources = updated','    auralisTransitions.cancelFade()\n    sources = updated');
 const swiftMove='      Function("moveAuralisQueue") { (playlist: AudioPlaylist, from: Int, to: Int) in playlist.moveAuralisQueue(from, to) }';
 edit('ios/AudioModule.swift',swiftMove,swiftMove+`
      Function("setAuralisAudioOptions") { (playlist: AudioPlaylist, seconds: Double, gains: [Double], blocked: [Bool]) in playlist.auralisTransitions.configure(seconds, gains: gains, blocked: blocked) }
      Function("setAuralisVolume") { (playlist: AudioPlaylist, volume: Double) in playlist.auralisTransitions.volume(volume) }`);
 edit('ios/AudioTapProcessor.m','#import <os/lock.h>','#import <os/lock.h>\n#import <math.h>');
};
