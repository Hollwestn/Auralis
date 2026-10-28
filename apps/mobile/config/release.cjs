/** Build-time checks keep store binaries distinct from local HTTP/debug builds. */
function publicHttps(value, name) {
  let url;
  try { url = new URL(value || ''); } catch { throw new Error(`Set ${name} to a public HTTPS page before a store build.`); }
  const host = url.hostname.toLowerCase();
  if (url.protocol !== 'https:' || url.username || url.password || host.includes(':') || /^(localhost$|0\.|127\.|10\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host) || /(^|\.)(local|localhost|example\.(com|org|net)|invalid|test)$/.test(host)) throw new Error(`${name} must be a real public HTTPS page (use a public DNS hostname).`);
  return url.href;
}
/** @param {Record<string, string | undefined>} env */
function releaseSettings(env) {
  const store = env.AURALIS_STORE_RELEASE === '1';
  if (store && (env.AURALIS_ALLOW_HTTP === '1' || env.AURALIS_TEST_APK === '1')) throw new Error('Store builds cannot enable local HTTP or test signing.');
  const email = env.AURALIS_SUPPORT_EMAIL || 'auralishelp@mail.ru';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Error('Invalid support email.');
  return {
    store,
    email,
    privacyUrl: store ? publicHttps(env.AURALIS_PRIVACY_URL, 'AURALIS_PRIVACY_URL') : env.AURALIS_PRIVACY_URL || '',
    deletionUrl: store ? publicHttps(env.AURALIS_DELETION_URL, 'AURALIS_DELETION_URL') : env.AURALIS_DELETION_URL || '',
    bundleId: env.AURALIS_BUNDLE_IDENTIFIER || 'io.auralis.mobile',
  };
}
module.exports = { releaseSettings };
