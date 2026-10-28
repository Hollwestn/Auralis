const { withAppBuildGradle, withGradleProperties, withProjectBuildGradle, withDangerousMod } = require('expo/config-plugins');
const fs = require('node:fs');
const path = require('node:path');

module.exports = function withAuralisAndroid(config) {
  config = withDangerousMod(config, ['android', result => {
    const rules = path.join(result.modRequest.platformProjectRoot, 'app', 'proguard-rules.pro');
    let contents = fs.existsSync(rules) ? fs.readFileSync(rules, 'utf8') : '';
    // EXGLJniApi.cpp looks up GLContext.flush() by name through JNI. R8 cannot
    // discover that edge, and otherwise removes/renames it in release APKs.
    if (!contents.includes('# AURALIS_EXGL_JNI')) contents += '\n# AURALIS_EXGL_JNI\n-keep class expo.modules.gl.GLContext { *; }\n-keep class expo.modules.gl.cpp.EXGL { *; }\n';
    // Expo's view factory reflects over constructors. GLView extends TextureView,
    // bypassing Expo's consumer rule for the ExpoView marker interface.
    if (!contents.includes('# AURALIS_EXPO_VIEW_CONSTRUCTORS')) contents += `
# AURALIS_EXPO_VIEW_CONSTRUCTORS
-keep class expo.modules.gl.GLView { *; }
-keepclassmembers class expo.modules.** extends android.view.View {
  public <init>(android.content.Context);
  public <init>(android.content.Context, expo.modules.kotlin.AppContext);
}
`;
    fs.writeFileSync(rules, contents);
    return result;
  }]);
  config = withGradleProperties(config, result => {
    // Java handles Unicode; Windows CMake/Ninja still need a short ASCII build path.
    if (!result.modResults.some(item => item.type === 'property' && item.key === 'android.overridePathCheck')) result.modResults.push({ type: 'property', key: 'android.overridePathCheck', value: 'true' });
    return result;
  });
  config = withProjectBuildGradle(config, result => {
    if (!result.modResults.contents.includes('// AURALIS_CMAKE_PATHS')) result.modResults.contents += `
// AURALIS_CMAKE_PATHS: hash object paths before Ninja reaches Windows path limits.
subprojects { nativeProject ->
    ['com.android.library', 'com.android.application'].each { pluginId ->
        nativeProject.plugins.withId(pluginId) {
            nativeProject.android.defaultConfig.externalNativeBuild.cmake.arguments.add('-DCMAKE_OBJECT_PATH_MAX=240')
        }
    }
}
`;
    result.modResults.contents = result.modResults.contents.replace(/-DCMAKE_OBJECT_PATH_MAX=\d+/g, '-DCMAKE_OBJECT_PATH_MAX=240');
    return result;
  });
  return withAppBuildGradle(config, result => {
    result.modResults.contents = require('./release-signing.cjs').patchReleaseSigning(result.modResults.contents);
    return result;
  });
};
