/** Patch only Auralis-owned signing blocks; generated projects remain reproducible. */
function patchReleaseSigning(source) {
  if (source.includes('// AURALIS_RELEASE_SIGNING_V2')) return source;
  let contents = source.replace(/\s*\/\/ AURALIS_RELEASE_SIGNING: credentials stay in the build environment\.\s*release \{[\s\S]*?\n        \}\s*/g, '\n');
  contents = contents.replace(/def hasReleaseKey = System\.getenv\('AURALIS_UPLOAD_STORE_FILE'\) != null[\s\S]*?signingConfig hasReleaseKey \? signingConfigs\.release : signingConfigs\.debug/g, 'signingConfig signingConfigs.debug');
  if (!contents.includes('signingConfigs {')) throw new Error('Auralis: missing Android signingConfigs template');
  contents = contents.replace('signingConfigs {', `signingConfigs {
        // AURALIS_RELEASE_SIGNING_V2
        release {
            if (System.getenv('AURALIS_UPLOAD_STORE_FILE')) {
                storeFile file(System.getenv('AURALIS_UPLOAD_STORE_FILE'))
                storePassword System.getenv('AURALIS_UPLOAD_STORE_PASSWORD')
                keyAlias System.getenv('AURALIS_UPLOAD_KEY_ALIAS')
                keyPassword System.getenv('AURALIS_UPLOAD_KEY_PASSWORD')
            }
        }
`);
  const start = contents.indexOf('release {', contents.indexOf('buildTypes {'));
  if (start < 0 || !contents.slice(start).includes('signingConfig signingConfigs.debug')) throw new Error('Auralis: unsupported Android release template');
  contents = contents.slice(0, start) + contents.slice(start).replace('signingConfig signingConfigs.debug', `signingConfig System.getenv('AURALIS_TEST_APK') == '1' && System.getenv('AURALIS_STORE_RELEASE') != '1' ? signingConfigs.debug : signingConfigs.release`);
  return contents + `
// AURALIS_RELEASE_SIGNING_CHECK: EAS injects its credentials before task execution.
gradle.taskGraph.whenReady { graph ->
    if (graph.allTasks.any { it.name.toLowerCase().contains('release') }) {
        def testBuild = System.getenv('AURALIS_TEST_APK') == '1'
        def storeBuild = System.getenv('AURALIS_STORE_RELEASE') == '1'
        if (storeBuild && testBuild) throw new GradleException('Store builds cannot use test signing.')
        def key = android.buildTypes.release.signingConfig
        if (!testBuild && (key == null || key.storeFile == null || !key.storeFile.isFile() || !key.storePassword || !key.keyAlias || !key.keyPassword || key.keyAlias == 'androiddebugkey')) {
            throw new GradleException('Release requires a production signing key. Configure EAS credentials or AURALIS_UPLOAD_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD. AURALIS_TEST_APK=1 is only for local tests.')
        }
    }
}
`;
}
module.exports = { patchReleaseSigning };
