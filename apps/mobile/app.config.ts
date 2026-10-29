import type { ExpoConfig } from 'expo/config';

const localHttp = process.env.AURALIS_ALLOW_HTTP === '1';
const config: ExpoConfig = {
  name: 'Auralis', slug: 'auralis', scheme: 'auralis', version: '0.7.0',
  orientation: 'portrait', userInterfaceStyle: 'dark', icon: './assets/auralis-icon.png',
  ios: {
    bundleIdentifier: 'io.auralis.mobile', buildNumber: '12', supportsTablet: true,
    infoPlist: {
      NSLocalNetworkUsageDescription: 'Auralis подключается к вашему музыкальному серверу в локальной сети.',
      NSAppTransportSecurity: localHttp ? { NSAllowsArbitraryLoads: true, NSAllowsLocalNetworking: true } : { NSAllowsLocalNetworking: true },
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  android: {
    package: 'io.auralis.mobile', versionCode: 12,
    adaptiveIcon: { foregroundImage: './assets/auralis-icon.png', backgroundColor: '#0B0B0E' },
    permissions: ['android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'],
    blockedPermissions: ['android.permission.RECORD_AUDIO', 'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.SYSTEM_ALERT_WINDOW'],
  },
  plugins: [
    'expo-router', ['expo-splash-screen', { backgroundColor: '#050609', image: './assets/auralis-icon.png', imageWidth: 100, resizeMode: 'contain' }], ['expo-secure-store', { configureAndroidBackup: true }],
    ['expo-audio', { microphonePermission: false, recordAudioAndroid: false, enableBackgroundPlayback: true, enableBackgroundRecording: false }],
    ['expo-build-properties', { android: { usesCleartextTraffic: localHttp } }],
    './plugins/withAuralisAndroid.cjs',
  ],
};
export default config;
