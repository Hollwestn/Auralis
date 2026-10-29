/* Reproducible, version-checked extension of Expo Audio's native playlist. */
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const manifest = require(path.join(root, 'package.json'));
for (const platform of ['android', 'ios']) {
  if (!manifest.expo?.autolinking?.[platform]?.buildFromSource?.includes('expo-audio')) {
    throw new Error(`Auralis PCM requires expo.autolinking.${platform}.buildFromSource to include expo-audio; a precompiled binary omits our source patch.`);
  }
}
const audio = path.dirname(require.resolve('expo-audio/package.json'));
if (require(path.join(audio, 'package.json')).version !== '58.0.9') throw new Error('Review Auralis PCM patch before upgrading expo-audio beyond 58.0.9');
function edit(relative, before, after) {
  const filename = path.join(audio, relative);
  let value = fs.readFileSync(filename, 'utf8').replace(/\r\n/g, '\n');
  if (value.includes(after)) return;
  if (!value.includes(before)) throw new Error(`Auralis audio patch anchor changed: ${relative}`);
  fs.writeFileSync(filename, value.replace(before, after));
}
const kotlin = 'android/src/main/java/expo/modules/audio/';
// Migrate the early 0.9 hook placement without disturbing the versioned spectrum anchor.
{
  const filename=path.join(audio,kotlin,'AudioPlaylist.kt');
  const value=fs.readFileSync(filename,'utf8').replace(/\r\n/g,'\n');
  const migrated=value.replace('    auralisSleep.onStart = { auralisTransitions.cancelFade() }\n    installPlayerListeners()', '    installPlayerListeners()');
  if(migrated!==value)fs.writeFileSync(filename,migrated);
}
fs.copyFileSync(path.join(root, 'native/audio/AuralisPcmTap.kt'), path.join(audio, kotlin, 'AuralisPcmTap.kt'));
const testDirectory = path.join(audio, 'android/src/test/java/expo/modules/audio');
fs.mkdirSync(testDirectory, { recursive: true });
fs.copyFileSync(path.join(root, 'native/audio/AuralisPcmTapTest.kt'), path.join(testDirectory, 'AuralisPcmTapTest.kt'));
fs.copyFileSync(path.join(root, 'native/audio/AuralisSpectrumTap.swift'), path.join(audio, 'ios/AuralisSpectrumTap.swift'));
fs.copyFileSync(path.join(root, 'native/audio/AuralisLocalMetadata.kt'), path.join(audio, kotlin, 'AuralisLocalMetadata.kt'));
fs.copyFileSync(path.join(root, 'native/audio/AuralisLocalMetadata.swift'), path.join(audio, 'ios/AuralisLocalMetadata.swift'));
fs.copyFileSync(path.join(root, 'native/audio/AuralisSleepTimer.kt'), path.join(audio, kotlin, 'AuralisSleepTimer.kt'));
fs.copyFileSync(path.join(root, 'native/audio/AuralisSleepTimer.swift'), path.join(audio, 'ios/AuralisSleepTimer.swift'));
edit(kotlin + 'AudioPlaylist.kt', '  private var currentRate = 1f', '  private var currentRate = 1f\n  val auralisSleep = AuralisSleepTimer(ref)');
edit(kotlin + 'AudioModule.kt', '    Name("ExpoAudio")', '    Name("ExpoAudio")\n    AsyncFunction("readAuralisMetadata") { uri: String, coverUri: String -> AuralisLocalMetadata.read(context, uri, coverUri) }');
edit('ios/AudioModule.swift', '    Name("ExpoAudio")', `    Name("ExpoAudio")
    AsyncFunction("readAuralisMetadata") { (uri: String, coverUri: String) async throws -> [String: Any] in
      return try await AuralisLocalMetadata.read(uri, coverUri)
    }`);
edit(kotlin + 'AudioPlaylist.kt', 'player = ExoPlayer.Builder(context)', 'player = ExoPlayer.Builder(context, AuralisRenderersFactory(context))');
edit(kotlin + 'AudioPlaylist.kt', '  init {\n    installPlayerListeners()', '  init {\n    AuralisPcmBus.onFrame = { frame -> playerScope.launch { emit("spectrumUpdate", frame) } }\n    installPlayerListeners()');
edit(kotlin + 'AudioPlaylist.kt', 'import java.lang.ref.WeakReference', 'import java.lang.ref.WeakReference\nimport kotlinx.coroutines.launch');
edit(kotlin + 'AudioPlaylist.kt', '  override fun releasePlayer() {', '  override fun releasePlayer() {\n    AuralisPcmBus.enabled = false\n    AuralisPcmBus.onFrame = null');
edit(kotlin + 'AudioModule.kt', '    Class(AudioPlaylist::class) {', '    Class(AudioPlaylist::class) {\n      Function("setSpectrumEnabled") { _: AudioPlaylist, enabled: Boolean -> AuralisPcmBus.enabled = enabled }');
edit(kotlin + 'AudioModule.kt', '          val playlist = AudioPlaylist(', '          val auralisHeaders = ConcurrentHashMap<String, Map<String, String>>()\n          val playlist = AudioPlaylist(');
edit(kotlin + 'AudioModule.kt', '            DefaultDataSource.Factory(context)\n          )', '            androidx.media3.datasource.ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->\n              spec.withRequestHeaders(auralisHeaders[spec.uri.toString()] ?: emptyMap())\n            }\n          )');
edit(kotlin + 'AudioModule.kt', '          playlist.setMediaItemCreator { source ->\n            source.uri?.let { uriString ->', '          playlist.setMediaItemCreator { source ->\n            source.uri?.let { uriString ->\n              auralisHeaders[uriString] = source.headers ?: emptyMap()');
edit('ios/AudioPlaylist.swift', '  private var previousTrackIndex = 0', '  private var previousTrackIndex = 0\n  let auralisSpectrum = AuralisSpectrumTap()');
edit('ios/AudioPlaylist.swift', '  func updateStatus(with dict: [String: Any]) {', '  func updateStatus(with dict: [String: Any]) {\n    auralisSpectrum.refresh(player: ref)');
edit('ios/AudioPlaylist.swift', '  public override func sharedObjectWillRelease() {', '  public override func sharedObjectWillRelease() {\n    auralisSpectrum.enabled = false\n    auralisSpectrum.invalidate()');
edit('ios/AudioModule.swift', '    Class(AudioPlaylist.self) {', `    Class(AudioPlaylist.self) {
      Function("setSpectrumEnabled") { (playlist: AudioPlaylist, enabled: Bool) in
        playlist.auralisSpectrum.enabled = enabled
        playlist.auralisSpectrum.onFrame = { [weak playlist] frame in
          DispatchQueue.main.async { playlist?.emit(event: "spectrumUpdate", payload: frame) }
        }
        playlist.auralisSpectrum.refresh(player: playlist.ref)
      }`);
edit('ios/AudioTapProcessor.h', '@property (nonatomic, readonly) BOOL isTapInstalled;', '@property (nonatomic, readonly) BOOL isTapInstalled;\n@property (nonatomic, assign) double sampleRate;');
edit('ios/AudioTapProcessor.m', '  context->supportedTapProcessingFormat = true;', '  context->supportedTapProcessingFormat = processingFormat->mFormatID == kAudioFormatLinearPCM && (processingFormat->mFormatFlags & kAudioFormatFlagIsFloat) != 0;\n  AudioTapProcessor *processor = (__bridge AudioTapProcessor *)context->self;\n  processor.sampleRate = processingFormat->mSampleRate;');
edit('ios/AudioTapProcessor.m', '  if (callback && bufferListInOut', '  if (context->supportedTapProcessingFormat && callback && bufferListInOut');
edit(kotlin + 'AudioPlaylist.kt', '    AuralisPcmBus.onFrame = null', '    AuralisPcmBus.onFrame = null\n    auralisSleep.cancel()');
edit(kotlin + 'AudioModule.kt', '      Function("setSpectrumEnabled") { _: AudioPlaylist, enabled: Boolean -> AuralisPcmBus.enabled = enabled }', '      Function("setSpectrumEnabled") { _: AudioPlaylist, enabled: Boolean -> AuralisPcmBus.enabled = enabled }\n      AsyncFunction("setAuralisSleepTimer") { playlist: AudioPlaylist, seconds: Double -> playlist.auralisSleep.set(seconds) }.runOnQueue(Queues.MAIN)');
edit('ios/AudioPlaylist.swift', '  let auralisSpectrum = AuralisSpectrumTap()', '  let auralisSpectrum = AuralisSpectrumTap()\n  let auralisSleep = AuralisSleepTimer()');
edit('ios/AudioPlaylist.swift', '    auralisSpectrum.invalidate()', '    auralisSpectrum.invalidate()\n    auralisSleep.cancel()');
edit('ios/AudioModule.swift', '        playlist.auralisSpectrum.refresh(player: playlist.ref)\n      }', `        playlist.auralisSpectrum.refresh(player: playlist.ref)
      }
      AsyncFunction("setAuralisSleepTimer") { (playlist: AudioPlaylist, seconds: Double) in
        playlist.auralisSleep.set(seconds, player: playlist.ref)
      }.runOnQueue(.main)`);
require('./patch-audio-queue.cjs')({ edit, audio, root, kotlin });
require('./patch-audio-loudness.cjs')({ edit, audio, root, kotlin });
require('./patch-audio-transitions.cjs')({ edit, audio, root, kotlin });
require('./patch-audio-peer.cjs')({ edit, audio, root, kotlin });
require('./patch-audio-storage.cjs')({ edit, audio, root, kotlin });
console.log('Auralis: PCM, metadata, sleep timer and continuous queue edits patched (Expo Audio 58.0.9).');
