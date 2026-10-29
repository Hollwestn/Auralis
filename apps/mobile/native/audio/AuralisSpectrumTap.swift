import AVFoundation
import Foundation

/// Read-only post-effects samples from the app's AVQueuePlayer; microphone is never opened.
final class AuralisSpectrumTap {
  private weak var player: AVQueuePlayer?
  private weak var item: AVPlayerItem?
  private var processor: AudioTapProcessor?
  private var samples = [Double](repeating: 0, count: 1024)
  private var cursor = 0
  private var lastEmit = 0.0
  var gain = 1.0 { didSet { processor?.auralisGain = Float(gain) } }
  var enabled = false
  var onFrame: (([String: Any]) -> Void)?

  func refresh(player: AVQueuePlayer) {
    self.player = player
    guard (enabled || abs(gain - 1) > 0.000001), player.currentItem?.status == .readyToPlay else { if !enabled && abs(gain - 1) < 0.000001 { invalidate() }; return }
    if item === player.currentItem && processor?.isTapInstalled == true { return }
    invalidate()
    item = player.currentItem
    let tap = AudioTapProcessor(player: player)
    processor = tap
    tap.auralisGain = Float(gain)
    tap.sampleBufferCallback = { [weak self, weak tap] buffer, frames, _ in
      guard let self, let tap, self.enabled, let buffer = buffer?.pointee, let data = buffer.mData else { return }
      let channelCount = max(1, Int(buffer.mNumberChannels))
      let count = min(Int(frames), Int(buffer.mDataByteSize) / MemoryLayout<Float>.size / channelCount)
      let values = data.assumingMemoryBound(to: Float.self)
      for frame in 0..<count {
        var mono = 0.0
        for channel in 0..<channelCount { mono += Double(values[frame * channelCount + channel]) }
        self.samples[self.cursor] = mono / Double(channelCount)
        self.cursor += 1
        if self.cursor == 1024 { self.cursor = 0; self.analyze(sampleRate: tap.sampleRate) }
      }
    }
    _ = tap.installTap()
  }
  func invalidate() { processor?.invalidate(); processor = nil; item = nil }
  private func analyze(sampleRate: Double) {
    let now = CFAbsoluteTimeGetCurrent()
    guard now - lastEmit >= 0.033, sampleRate > 0 else { return }
    lastEmit = now
    var real = [Double](repeating: 0, count: 1024)
    var imaginary = [Double](repeating: 0, count: 1024)
    var energy = 0.0; var peak = 0.0
    for index in 0..<1024 { energy += samples[index] * samples[index]; peak = max(peak, abs(samples[index])); real[index] = samples[index] * (0.5 - 0.5 * cos(2 * .pi * Double(index) / 1023)) }
    var j = 0
    for index in 1..<1024 {
      var bit = 512
      while j & bit != 0 { j ^= bit; bit >>= 1 }
      j ^= bit
      if index < j { real.swapAt(index, j) }
    }
    var length = 2
    while length <= 1024 {
      let angle = -2 * Double.pi / Double(length)
      for start in stride(from: 0, to: 1024, by: length) {
        for k in 0..<(length / 2) {
          let a = start + k; let b = a + length / 2
          let wr = cos(angle * Double(k)); let wi = sin(angle * Double(k))
          let tr = wr * real[b] - wi * imaginary[b]; let ti = wr * imaginary[b] + wi * real[b]
          real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti; real[a] += tr; imaginary[a] += ti
        }
      }
      length *= 2
    }
    let limit = min(20000, sampleRate / 2)
    let bands: [Double] = (0..<32).map { band in
      let first = max(1, Int(20 * pow(limit / 20, Double(band) / 32) * 1024 / sampleRate))
      let end = min(512, max(first + 1, Int(20 * pow(limit / 20, Double(band + 1) / 32) * 1024 / sampleRate)))
      var magnitude = 0.0
      for bin in first..<end { magnitude = max(magnitude, hypot(real[bin], imaginary[bin]) * 4 / 1024) }
      return min(1, max(0, (20 * log10(max(1e-8, magnitude)) + 80) / 80))
    }
    onFrame?(["bands": bands, "rms": sqrt(energy / 1024), "peak": peak, "sampleRate": sampleRate, "windowSize": 1024])
  }
  deinit { invalidate() }
}
