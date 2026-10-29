import Foundation
import AVFoundation

enum AuralisLoudness {
  static func analyze(_ uri: String) async throws -> [String: Double] {
    guard let url = URL(string: uri), url.isFileURL else { throw NSError(domain: "Auralis", code: 1, userInfo: [NSLocalizedDescriptionKey: "Select a local audio file"]) }
    return try await Task.detached(priority: .utility) {
      let asset = AVURLAsset(url: url)
      guard let track = try await asset.loadTracks(withMediaType: .audio).first else { throw NSError(domain: "Auralis", code: 2) }
      let reader = try AVAssetReader(asset: asset)
      let output = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 48000, AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true, AVLinearPCMIsNonInterleaved: false])
      reader.add(output); reader.startReading()
      let meter = AuralisLoudnessMeter()
      while let sample = output.copyNextSampleBuffer() {
        if Task.isCancelled { reader.cancelReading(); throw CancellationError() }
        guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
        var length = 0; var data: UnsafeMutablePointer<Int8>?
        if CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &data) == kCMBlockBufferNoErr, let data {
          data.withMemoryRebound(to: Float.self, capacity: length / 4) { samples in
            for index in stride(from: 0, to: length / 4 - 1, by: 2) { meter.accept(Double(samples[index]), Double(samples[index + 1])) }
          }
        }
      }
      if reader.status == .failed { throw reader.error ?? NSError(domain: "Auralis", code: 3) }
      return meter.result()
    }.value
  }
}
private final class AuralisLoudnessMeter {
  private final class Filter {
    let c: [Double]; var z1 = 0.0, z2 = 0.0
    init(_ values: [Double]) { c = values }
    func apply(_ x: Double) -> Double { let y=c[0]*x+z1;z1=c[1]*x-c[3]*y+z2;z2=c[2]*x-c[4]*y;return y }
  }
  private var shelves: [Filter], highpasses: [Filter]
  private var ring=[Double](repeating:0,count:19200), blocks=[Double](), index=0, frames=0
  private var sum=0.0, all=0.0, peak=0.0
  init() {
    let k=tan(Double.pi*1681.974450955533/48000),q=0.7071752369554196,vh=pow(10,3.999843853973347/20),vb=pow(vh,0.4996667741545416),a=1+k/q+k*k
    let shelf=[(vh+vb*k/q+k*k)/a,2*(k*k-vh)/a,(vh-vb*k/q+k*k)/a,2*(k*k-1)/a,(1-k/q+k*k)/a]
    let h=tan(Double.pi*38.13547087602444/48000),r=0.5003270373238773,d=1+h/r+h*h
    let high=[1.0,-2.0,1.0,2*(h*h-1)/d,(1-h/r+h*h)/d]
    shelves=[Filter(shelf),Filter(shelf)];highpasses=[Filter(high),Filter(high)]
  }
  func accept(_ left: Double, _ right: Double) {
    let l=left.isFinite ? left : 0, r=right.isFinite ? right : 0
    peak=max(peak,max(abs(l),abs(r)));let a=highpasses[0].apply(shelves[0].apply(l)),b=highpasses[1].apply(shelves[1].apply(r)),energy=a*a+b*b
    all+=energy;sum+=energy-ring[index];ring[index]=energy;index=(index+1)%19200;frames+=1
    if frames>=19200 && (frames-19200)%4800==0 { blocks.append(sum/19200) }
  }
  func result() -> [String: Double] {
    let values=blocks.isEmpty ? [all/Double(max(1,frames))] : blocks, absolute=pow(10,(-70+0.691)/10)
    let gated=values.filter{$0>=absolute}, mean=gated.reduce(0,+)/Double(max(1,gated.count)), final=values.filter{$0>=max(absolute,mean*0.1)}
    let integrated=final.isEmpty ? -120 : -0.691+10*log10(final.reduce(0,+)/Double(final.count))
    return ["loudnessLufs":integrated,"samplePeakDb":peak>0 ? max(-120,20*log10(peak)) : -120]
  }
}
