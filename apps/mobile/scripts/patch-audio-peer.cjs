module.exports=function({edit,audio,root,kotlin}){
 const fs=require('node:fs'),path=require('node:path');
 fs.copyFileSync(path.join(root,'native/audio/AuralisPeer.kt'),path.join(audio,kotlin,'AuralisPeer.kt'));
 fs.copyFileSync(path.join(root,'native/audio/AuralisPeer.swift'),path.join(audio,'ios/AuralisPeer.swift'));
 const android='    Function("getAuralisDeviceBudget") { val power=context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager; mapOf("lowPower" to power.isPowerSaveMode, "thermal" to if(android.os.Build.VERSION.SDK_INT>=29) power.currentThermalStatus else 0) }';
 edit(kotlin+'AudioModule.kt',android,android+`\n    AsyncFunction("auralisPeerRequest") { descriptor: String, method: String, path: String, nonce: String, payload: String, source: String, destination: String -> AuralisPeer.request(context, descriptor, method, path, nonce, payload, source, destination) }`);
 const swift='    Function("getAuralisDeviceBudget") { () -> [String: Any] in return ["lowPower": ProcessInfo.processInfo.isLowPowerModeEnabled, "thermal": ProcessInfo.processInfo.thermalState.rawValue * 2] }';
 edit('ios/AudioModule.swift',swift,swift+`\n    AsyncFunction("auralisPeerRequest") { (descriptor: String, method: String, path: String, nonce: String, payload: String, source: String, destination: String) async throws -> [String: Any] in
      return try await AuralisPeer.request(descriptor, method: method, path: path, nonce: nonce, payload: payload, source: source, destination: destination)
    }`);
};
