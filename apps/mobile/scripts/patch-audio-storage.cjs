module.exports=function({edit,kotlin}){
 const android='    AsyncFunction("auralisPeerRequest") { descriptor: String, method: String, path: String, nonce: String, payload: String, source: String, destination: String -> AuralisPeer.request(context, descriptor, method, path, nonce, payload, source, destination) }';
 edit(kotlin+'AudioModule.kt',android,android+`
    Function("writeAuralisJsonAtomic") { uri: String, json: String ->
      val parsed=android.net.Uri.parse(uri)
      require(parsed.scheme=="file" && json.length<=64*1024*1024) { "Invalid library state" }
      val file=java.io.File(parsed.path ?: error("Missing file")).canonicalFile
      require(listOf(context.filesDir,context.cacheDir).any { file.path.startsWith(it.canonicalPath+java.io.File.separator) }) { "Expected an app-owned file" }
      val atomic=android.util.AtomicFile(file)
      val output=atomic.startWrite()
      try { output.write(json.toByteArray(Charsets.UTF_8));atomic.finishWrite(output) }
      catch(error: Throwable) { atomic.failWrite(output);throw error }
    }`);
 const swift=`    AsyncFunction("auralisPeerRequest") { (descriptor: String, method: String, path: String, nonce: String, payload: String, source: String, destination: String) async throws -> [String: Any] in
      return try await AuralisPeer.request(descriptor, method: method, path: path, nonce: nonce, payload: payload, source: source, destination: destination)
    }`;
 edit('ios/AudioModule.swift',swift,swift+`
    Function("writeAuralisJsonAtomic") { (uri: String, json: String) throws in
      let url=try AuralisPeer.owned(uri)
      guard let data=json.data(using:.utf8),data.count<=64*1024*1024 else { throw NSError(domain:"Auralis",code:1) }
      try data.write(to:url,options:.atomic)
    }`);
};
