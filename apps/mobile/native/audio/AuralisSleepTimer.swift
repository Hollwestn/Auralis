import Foundation
import AVFoundation

final class AuralisSleepTimer {
  private var timer: DispatchSourceTimer?
  private weak var player: AVPlayer?
  private var originalVolume: Float?
  func set(_ seconds: Double, player: AVPlayer) {
    cancel()
    guard seconds.isFinite, seconds > 0, seconds <= 86400 else { return }
    self.player = player
    let deadline = ProcessInfo.processInfo.systemUptime + seconds
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now(), repeating: .milliseconds(200))
    timer.setEventHandler { [weak self] in
      guard let self, let player = self.player else { return }
      let remaining = deadline - ProcessInfo.processInfo.systemUptime
      if remaining <= 0 { player.pause(); self.cancel(); return }
      if remaining <= 10 {
        if self.originalVolume == nil { self.originalVolume = player.volume }
        player.volume = (self.originalVolume ?? 1) * Float(max(0, min(1, remaining / 10)))
      }
    }
    self.timer = timer
    timer.resume()
  }
  func cancel() { timer?.cancel(); timer = nil; if let originalVolume { player?.volume = originalVolume }; originalVolume = nil; player = nil }
  deinit { timer?.cancel() }
}
