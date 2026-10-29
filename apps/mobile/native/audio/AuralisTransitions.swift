import Foundation
import AVFoundation

/// AVQueuePlayer remains the transport owner. A preloaded native player overlaps the tail.
final class AuralisTransitions {
  private weak var player: AVQueuePlayer?
  private weak var spectrum: AuralisSpectrumTap?
  private let source: (Int) -> AVPlayerItem?
  private let index: () -> Int
  private let nextIndex: () -> Int?
  private let sleeping: () -> Bool
  private var timer: DispatchSourceTimer?
  private var shadow: AVQueuePlayer?
  private var shadowSpectrum: AuralisSpectrumTap?
  private var seconds=0.0, gains=[Double](), blocked=[Bool]()
  private var target = -1, previousIndex = -1
  private var start = -1.0, handover = -1.0
  private var baseVolume: Float = 1
  private var observers=[NSKeyValueObservation]()
  init(player: AVQueuePlayer, spectrum: AuralisSpectrumTap, source: @escaping (Int)->AVPlayerItem?, index: @escaping ()->Int, nextIndex: @escaping ()->Int?, sleeping: @escaping ()->Bool) {
    self.player=player;self.spectrum=spectrum;self.source=source;self.index=index;self.nextIndex=nextIndex;self.sleeping=sleeping
    observers.append(player.observe(\.currentItem, options:[.new]) { [weak self] _,_ in DispatchQueue.main.async { self?.changed() } })
    observers.append(player.observe(\.rate, options:[.new]) { [weak self] p,_ in DispatchQueue.main.async { if p.rate==0 { self?.cancelFade() };self?.schedule() } })
  }
  func configure(_ fade: Double, gains: [Double], blocked: [Bool]) {
    if abs(seconds-fade)>0.001 { cancelFade() }
    seconds=max(0,min(12,fade));self.gains=gains.map{ $0.isFinite ? max(-24,min(12,$0)) : 0 };self.blocked=blocked
    applyGain();schedule()
  }
  private func gain(_ index: Int)->Double { pow(10,(gains.indices.contains(index) ? gains[index] : 0)/20) }
  private func applyGain(){guard let player else{return};spectrum?.gain=gain(index());spectrum?.refresh(player:player)}
  private func schedule(){timer?.cancel();timer=nil;guard seconds>0,let player,player.rate>0 else{return};let tick=DispatchSource.makeTimerSource(queue:.main);tick.schedule(deadline:.now(),repeating:.milliseconds(20));tick.setEventHandler{[weak self] in self?.update()};timer=tick;tick.resume()}
  private func changed(){
    applyGain();let current=index();previousIndex=current
    if let player,let shadow,start>=0,current==target {player.volume=0;let time=shadow.currentTime();player.seek(to:time,toleranceBefore:.zero,toleranceAfter:.zero){[weak self] _ in DispatchQueue.main.async{self?.handover=ProcessInfo.processInfo.systemUptime}}}
    else {cancelFade()};schedule()
  }
  private func update(){
    guard let player,player.rate>0,!sleeping() else{cancelFade();return}
    if handover>=0,let shadow {
      guard player.currentItem?.status == .readyToPlay else{return}
      let mix=Float(min(1,max(0,(ProcessInfo.processInfo.systemUptime-handover)/0.08)))
      player.volume=baseVolume*mix;shadow.volume=baseVolume*(1-mix);if mix>=1{cancelFade()};return
    }
    if start<0 {baseVolume=player.volume}
    let current=index();guard let next=nextIndex() else{cancelFade();return}
    guard let item=player.currentItem else{return};let duration=item.duration.seconds,position=item.currentTime().seconds
    guard duration.isFinite,duration>0,position.isFinite else{return}
    if (blocked.indices.contains(current)&&blocked[current]) || (blocked.indices.contains(next)&&blocked[next]) {cancelFade();return}
    let fade=min(seconds,duration/2),remaining=duration-position
    if shadow==nil,fade>0,remaining<=fade+3,let source=source(next){
      target=next;let second=AVQueuePlayer(items:[source]);second.volume=0;shadow=second
      let tap=AuralisSpectrumTap();tap.gain=gain(next);shadowSpectrum=tap
    }
    guard let shadow,shadow.currentItem?.status == .readyToPlay else{return}
    shadowSpectrum?.refresh(player:shadow)
    if start<0,remaining<=fade,remaining>0.06 {start=position;shadow.playImmediately(atRate:player.rate)}
    if start>=0 {let mix=Float(min(1,max(0,(position-start)/max(0.01,duration-start))));player.volume=baseVolume*(1-mix);shadow.volume=baseVolume*mix}
  }
  func volume(_ value: Double){baseVolume=Float(min(1,max(0,value)));if start<0&&handover<0{player?.volume=baseVolume}}
  func cancelFade(){let active=shadow != nil || start>=0 || handover>=0;shadow?.pause();shadow?.removeAllItems();shadow=nil;shadowSpectrum?.invalidate();shadowSpectrum=nil;target = -1;start = -1;handover = -1;if active{player?.volume=baseVolume}}
  func close(){timer?.cancel();timer=nil;cancelFade();observers.removeAll();spectrum?.gain=1}
  deinit{timer?.cancel()}
}
