import type { ExpoConfig } from 'expo/config';
import { releaseSettings } from './config/release.cjs';

const localHttp = process.env.AURALIS_ALLOW_HTTP === '1';
const release = releaseSettings(process.env);
const config: ExpoConfig = {
  name: 'Auralis', slug: 'auralis', scheme: 'auralis', version: '0.8.0',
  orientation: 'portrait', userInterfaceStyle: 'dark', icon: './assets/auralis-icon.png',
  ios: {
    bundleIdentifier: release.bundleId, buildNumber: '13', supportsTablet: true, icon: './assets/auralis-icon-ios.png',
    privacyManifests: {
      NSPrivacyTracking: false,
      NSPrivacyAccessedAPITypes: [{ NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategorySystemBootTime', NSPrivacyAccessedAPITypeReasons: ['35F9.1'] }],
    },
    infoPlist: {
      NSLocalNetworkUsageDescription: 'Auralis подключается к вашему музыкальному серверу в локальной сети.',
      NSAppTransportSecurity: localHttp ? { NSAllowsArbitraryLoads: true, NSAllowsLocalNetworking: true } : { NSAllowsLocalNetworking: !release.store },
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  android: {
    package: release.bundleId, versionCode: 13,
    adaptiveIcon: { foregroundImage: './assets/auralis-icon.png', backgroundColor: '#0B0B0E' },
    permissions: ['android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'],
    blockedPermissions: ['android.permission.RECORD_AUDIO', 'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.SYSTEM_ALERT_WINDOW'],
  },
  extra: { legal: { supportEmail: release.email, privacyUrl: release.privacyUrl, deletionUrl: release.deletionUrl } },
  plugins: [
    'expo-router', ['expo-splash-screen', { backgroundColor: '#050609', image: './assets/auralis-icon.png', imageWidth: 100, resizeMode: 'contain' }], ['expo-secure-store', { configureAndroidBackup: true }],
    ['expo-audio', { microphonePermission: false, recordAudioAndroid: false, enableBackgroundPlayback: true, enableBackgroundRecording: false }],
    ['expo-build-properties', { android: { usesCleartextTraffic: localHttp, targetSdkVersion: 36, compileSdkVersion: 37 } }],
    './plugins/withAuralisAndroid.cjs',
  ],
};
export default config;
