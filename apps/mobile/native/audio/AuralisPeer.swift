import Foundation
import CryptoKit
import Security

final class AuralisPeerPin: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
 let fingerprint: String
 init(_ fingerprint: String) { self.fingerprint = fingerprint }
 func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
  guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = challenge.protectionSpace.serverTrust, let certificate = SecTrustGetCertificateAtIndex(trust, 0) else { completionHandler(.cancelAuthenticationChallenge, nil); return }
  let data = SecCertificateCopyData(certificate) as Data
  let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  guard digest == fingerprint else { completionHandler(.cancelAuthenticationChallenge, nil); return }
  SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
  SecTrustSetAnchorCertificatesOnly(trust, true)
  guard SecTrustEvaluateWithError(trust, nil) else { completionHandler(.cancelAuthenticationChallenge, nil); return }
  completionHandler(.useCredential, URLCredential(trust: trust))
 }
 func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
enum AuralisPeer {
 static func owned(_ text: String) throws -> URL {
  guard let url = URL(string: text), url.isFileURL else { throw NSError(domain: "Auralis", code: 1, userInfo: [NSLocalizedDescriptionKey:"Expected an app-owned file"]) }
  let path = url.resolvingSymlinksInPath().standardizedFileURL.path
  let roots = [FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!, FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!]
  guard roots.contains(where: { path.hasPrefix($0.resolvingSymlinksInPath().path + "/") }) else { throw NSError(domain:"Auralis",code:2) }
  return URL(fileURLWithPath:path)
 }
 static func request(_ descriptor: String, method: String, path: String, nonce: String, payload: String, source: String, destination: String) async throws -> [String: Any] {
  func invalid(_ message:String) -> NSError { NSError(domain:"Auralis",code:3,userInfo:[NSLocalizedDescriptionKey:message]) }
  guard let bytes=descriptor.data(using:.utf8),let info=try JSONSerialization.jsonObject(with:bytes) as? [String:Any],let host=info["host"] as? String,let port=info["port"] as? Int,let fingerprint=info["certificate"] as? String,let secret=info["secret"] as? String,let expiry=info["expires"] as? String else { throw invalid("Invalid pairing code") }
  let parts=host.split(separator:".").compactMap{Int($0)}
  guard parts.count==4,parts.allSatisfy({(0...255).contains($0)}),(parts[0]==10 || parts[0]==192 && parts[1]==168 || parts[0]==172 && (16...31).contains(parts[1])),(1...65535).contains(port),fingerprint.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil,secret.range(of:"^[A-Za-z0-9_-]{43}$",options:.regularExpression) != nil,nonce.range(of:"^[A-Za-z0-9-]{16,100}$",options:.regularExpression) != nil else {throw invalid("Only a scanned local Wi-Fi address is allowed")}
  let formatter=ISO8601DateFormatter();formatter.formatOptions=[.withInternetDateTime,.withFractionalSeconds];let simple=ISO8601DateFormatter()
  guard let until=formatter.date(from:expiry) ?? simple.date(from:expiry),until>Date(),until.timeIntervalSinceNow<1000 else {throw invalid("Pairing session expired")}
  guard ["GET","POST"].contains(method),["/pair","/bundle","/upload"].contains(path) || path.range(of:"^/pair/status/[a-f0-9-]{36}$",options:.regularExpression) != nil else {throw invalid("Unsupported endpoint")}
  var request=URLRequest(url:URL(string:"https://\(host):\(port)\(path)")!);request.httpMethod=method;request.setValue("Auralis "+secret,forHTTPHeaderField:"Authorization");request.setValue(nonce,forHTTPHeaderField:"X-Auralis-Device")
  let configuration=URLSessionConfiguration.ephemeral;configuration.timeoutIntervalForRequest=30;configuration.timeoutIntervalForResource=900
  let pin=AuralisPeerPin(fingerprint),session=URLSession(configuration:configuration,delegate:pin,delegateQueue:nil);defer{session.invalidateAndCancel()}
  if path=="/bundle" {
   let (temp,response)=try await session.download(for:request);defer{try? FileManager.default.removeItem(at:temp)}
   guard let response=response as? HTTPURLResponse,response.statusCode==200 else {throw invalid("No selected music is ready on the computer")}
   let target=try owned(destination),size=(try FileManager.default.attributesOfItem(atPath:temp.path)[.size] as? NSNumber)?.int64Value ?? 0
   guard size>0,size<=3_000_000_000 else {throw invalid("Archive exceeds 3 GB")}
   var digest=SHA256();let file=try FileHandle(forReadingFrom:temp);defer{try? file.close()};while let data=try file.read(upToCount:131072),!data.isEmpty{digest.update(data:data)}
   guard digest.finalize().map({String(format:"%02x",$0)}).joined()==response.value(forHTTPHeaderField:"X-Auralis-SHA256") else {throw invalid("Archive integrity check failed")}
   try FileManager.default.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true);try FileManager.default.moveItem(at:temp,to:target);return ["status":200,"body":"{}","uri":destination]
  }
  let data:Data,response:URLResponse
  if path=="/upload" {
   let file=try owned(source),size=(try FileManager.default.attributesOfItem(atPath:file.path)[.size] as? NSNumber)?.int64Value ?? 0
   guard size>0,size<=3_000_000_000 else {throw invalid("Archive exceeds 3 GB")};request.setValue("application/zip",forHTTPHeaderField:"Content-Type");request.setValue(String(size),forHTTPHeaderField:"Content-Length")
   (data,response)=try await session.upload(for:request,fromFile:file)
  } else {
   if method=="POST"{guard payload.utf8.count<=4096 else{throw invalid("Request too large")};request.httpBody=payload.data(using:.utf8);request.setValue("application/json",forHTTPHeaderField:"Content-Type")}
   (data,response)=try await session.data(for:request)
  }
  guard data.count<=65536,let response=response as? HTTPURLResponse else{throw invalid("Invalid response")}
  return ["status":response.statusCode,"body":String(data:data,encoding:.utf8) ?? "{}"]
 }
}
