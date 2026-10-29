module.exports=function({edit,audio,root,kotlin}){
 const fs=require('node:fs'),path=require('node:path');
 for(const name of ['AuralisLoudness.kt','AuralisLoudnessMeter.java'])fs.copyFileSync(path.join(root,'native/audio',name),path.join(audio,kotlin,name));
 fs.copyFileSync(path.join(root,'native/audio/AuralisLoudness.swift'),path.join(audio,'ios/AuralisLoudness.swift'));
 const android='    AsyncFunction("readAuralisMetadata") { uri: String, coverUri: String -> AuralisLocalMetadata.read(context, uri, coverUri) }';
 edit(kotlin+'AudioModule.kt',android,android+'\n    AsyncFunction("analyzeAuralisLoudness") { uri: String -> AuralisLoudness.analyze(context, uri) }');
 const analyze='    AsyncFunction("analyzeAuralisLoudness") { uri: String -> AuralisLoudness.analyze(context, uri) }';
 edit(kotlin+'AudioModule.kt',analyze,analyze+`\n    Function("getAuralisDeviceBudget") { val power=context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager; mapOf("lowPower" to power.isPowerSaveMode, "thermal" to if(android.os.Build.VERSION.SDK_INT>=29) power.currentThermalStatus else 0) }`);
 const swift=`    AsyncFunction("readAuralisMetadata") { (uri: String, coverUri: String) async throws -> [String: Any] in
      return try await AuralisLocalMetadata.read(uri, coverUri)
    }`;
 edit('ios/AudioModule.swift',swift,swift+`\n    AsyncFunction("analyzeAuralisLoudness") { (uri: String) async throws -> [String: Double] in return try await AuralisLoudness.analyze(uri) }`);
 const swiftAnalyze='    AsyncFunction("analyzeAuralisLoudness") { (uri: String) async throws -> [String: Double] in return try await AuralisLoudness.analyze(uri) }';
 edit('ios/AudioModule.swift',swiftAnalyze,swiftAnalyze+`\n    Function("getAuralisDeviceBudget") { () -> [String: Any] in return ["lowPower": ProcessInfo.processInfo.isLowPowerModeEnabled, "thermal": ProcessInfo.processInfo.thermalState.rawValue * 2] }`);
 edit('ios/AudioTapProcessor.h','@property (nonatomic, assign) double sampleRate;','@property (nonatomic, assign) double sampleRate;\n@property (atomic, assign) float auralisGain;');
 edit('ios/AudioTapProcessor.m','  Boolean isNonInterleaved;','  Boolean isNonInterleaved;\n  float auralisCurrentGain;');
 edit('ios/AudioTapProcessor.m','    _audioProcessingTap = NULL;','    _audioProcessingTap = NULL;\n    _auralisGain = 1.0f;');
 edit('ios/AudioTapProcessor.m','  processor.sampleRate = processingFormat->mSampleRate;','  processor.sampleRate = processingFormat->mSampleRate;\n  context->auralisCurrentGain = processor.auralisGain;');
 const anchor='  SampleBufferCallback callback = processor.sampleBufferCallback;';
 edit('ios/AudioTapProcessor.m',anchor,`  // AURALIS_PCM_GAIN: apply to every interleaved/noninterleaved float buffer.
  if (context->supportedTapProcessingFormat && bufferListInOut) {
    const float target = fmaxf(0.0f, fminf(3.981071706f, processor.auralisGain));
    const float smoothing = 1.0f - expf(-1.0f / (fmax(8000.0, processor.sampleRate) * 0.025f));
    const float start = context->auralisCurrentGain;
    float finalGain = start;
    for (UInt32 bufferIndex = 0; bufferIndex < bufferListInOut->mNumberBuffers; bufferIndex++) {
      AudioBuffer *buffer = &bufferListInOut->mBuffers[bufferIndex];
      float *values = (float *)buffer->mData;
      if (!values) continue;
      const UInt32 channels = MAX(1, buffer->mNumberChannels);
      const UInt32 frames = MIN(*numberFramesOut, buffer->mDataByteSize / sizeof(float) / channels);
      float gain = start;
      for (UInt32 frame = 0; frame < frames; frame++) {
        gain += (target - gain) * smoothing;
        if (fabsf(gain - 1.0f) > 0.000001f) for (UInt32 channel = 0; channel < channels; channel++) {
          const UInt32 index = frame * channels + channel;
          values[index] = fmaxf(-0.999969f, fminf(0.999969f, values[index] * gain));
        }
      }
      finalGain = gain;
    }
    context->auralisCurrentGain = finalGain;
  }
`+anchor);
};
