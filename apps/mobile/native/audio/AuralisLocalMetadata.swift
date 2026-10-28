import Foundation
import AVFoundation
import ImageIO

enum AuralisLocalMetadata {
  private static func owned(_ value: String) throws -> URL {
    guard let url = URL(string: value), url.isFileURL else { throw NSError(domain: "Auralis", code: 1) }
    let resolved = url.resolvingSymlinksInPath().standardizedFileURL
    let roots = [FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0], FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]]
    guard roots.contains(where: { resolved.path.hasPrefix($0.resolvingSymlinksInPath().standardizedFileURL.path + "/") }) else { throw NSError(domain: "Auralis", code: 2) }
    return resolved
  }
  static func read(_ uri: String, _ coverUri: String) async throws -> [String: Any] {
    let source = try owned(uri), cover = try owned(coverUri)
    let asset = AVURLAsset(url: source)
    var result: [String: Any] = [:]
    let duration = try await asset.load(.duration).seconds
    if duration.isFinite && duration > 0 { result["durationSeconds"] = duration }
    for item in try await asset.load(.commonMetadata) {
      let name: String?
      switch item.commonKey {
      case .commonKeyTitle: name = "title"
      case .commonKeyArtist: name = "artist"
      case .commonKeyAlbumName: name = "album"
      default: name = nil
      }
      if let name, let text = try? await item.load(.stringValue), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { result[name] = String(text.prefix(1000)) }
      if item.commonKey == .commonKeyArtwork, let data = try? await item.load(.dataValue), data.count <= 16 * 1024 * 1024,
         let imageSource = CGImageSourceCreateWithData(data as CFData, nil),
         let image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 600, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) {
        try FileManager.default.createDirectory(at: cover.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let target = CGImageDestinationCreateWithURL(cover as CFURL, "public.png" as CFString, 1, nil) {
          CGImageDestinationAddImage(target, image, nil)
          if CGImageDestinationFinalize(target) { result["coverUrl"] = cover.absoluteString }
        }
      }
    }
    return result
  }
}
