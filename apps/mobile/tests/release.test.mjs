import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { releaseSettings } from '../config/release.cjs';
import { patchReleaseSigning } from '../plugins/release-signing.cjs';

const valid = { AURALIS_STORE_RELEASE: '1', AURALIS_PRIVACY_URL: 'https://hollwestn.github.io/Auralis/privacy.html', AURALIS_DELETION_URL: 'https://hollwestn.github.io/Auralis/delete-account.html' };
test('offline builds need no hosted page, store builds require public legal pages', () => {
  assert.equal(releaseSettings({}).email, 'auralishelp@mail.ru');
  assert.equal(releaseSettings(valid).store, true);
  assert.throws(() => releaseSettings({ AURALIS_STORE_RELEASE: '1' }));
  for (const url of ['http://website.org/privacy', 'https://localhost/privacy', 'https://127.0.0.1/', 'https://[::1]/', 'https://192.168.1.2/', 'https://172.20.1.1/', 'https://10.1.2.3/', 'https://foo.local/', 'https://example.com/', 'https://person:password@website.org/']) assert.throws(() => releaseSettings({ ...valid, AURALIS_PRIVACY_URL: url }));
});
test('test signing and private HTTP cannot be combined with store profiles', () => {
  for (const flag of ['AURALIS_TEST_APK', 'AURALIS_ALLOW_HTTP']) assert.throws(() => releaseSettings({ ...valid, [flag]: '1' }));
});
test('signing patch preserves debug builds and refreshes old generated projects', () => {
  const template = 'android {\n signingConfigs {\n debug { keyAlias "androiddebugkey" }\n }\n buildTypes {\n debug { signingConfig signingConfigs.debug }\n release {\n signingConfig signingConfigs.debug\n }\n }\n}';
  const patched = patchReleaseSigning(template);
  assert.equal(patchReleaseSigning(patched), patched);
  assert.match(patched, /debug \{ signingConfig signingConfigs.debug \}/);
  assert.match(patched, /gradle.taskGraph.whenReady/);
  assert.match(patched, /!key.storeFile.isFile\(\)/);
  assert.match(patched, /key.keyAlias == 'androiddebugkey'/);
  assert.match(patched, /Store builds cannot use test signing/);
  const old = template.replace('signingConfigs {', `signingConfigs {\n        // AURALIS_RELEASE_SIGNING: credentials stay in the build environment.\n        release {\n            if (System.getenv('AURALIS_UPLOAD_STORE_FILE')) {\n                storeFile file(System.getenv('AURALIS_UPLOAD_STORE_FILE'))\n            }\n        }\n`).replace('release {\n signingConfig signingConfigs.debug', `release {\n def hasReleaseKey = System.getenv('AURALIS_UPLOAD_STORE_FILE') != null\n def isTestApk = System.getenv('AURALIS_TEST_APK') == '1'\n if (!hasReleaseKey && !isTestApk) { throw new GradleException('old') }\n signingConfig hasReleaseKey ? signingConfigs.release : signingConfigs.debug`);
  assert.equal((patchReleaseSigning(old).match(/AURALIS_RELEASE_SIGNING_V2/g) || []).length, 1);
  assert.doesNotMatch(patchReleaseSigning(old), /def hasReleaseKey/);
  assert.throws(() => patchReleaseSigning('unsupported template'));
});
test('each store profile selects a signed store binary and expected format', () => {
  const eas = JSON.parse(readFileSync(new URL('../eas.json', import.meta.url)));
  for (const name of ['google-play', 'rustore', 'app-store']) {
    const profile = eas.build[name];
    assert.equal(profile.distribution, 'store');
    assert.equal(profile.credentialsSource, 'remote');
    assert.equal(profile.env.AURALIS_STORE_RELEASE, '1');
    assert.equal(profile.env.AURALIS_TEST_APK, '0');
  }
  assert.equal(eas.build['google-play'].android.buildType, 'app-bundle');
  assert.equal(eas.build.rustore.android.buildType, 'apk');
});
