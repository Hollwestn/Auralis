// Export the established logo on an opaque background for App Store.
const { generateImageAsync } = require('@expo/image-utils');
const fs = require('node:fs');
const path = require('node:path');
async function main() {
  const projectRoot = path.resolve(__dirname, '..');
  const result = await generateImageAsync({ projectRoot }, {
    src: path.join(projectRoot, 'assets/auralis-icon.png'),
    width: 1024, height: 1024, resizeMode: 'contain', backgroundColor: '#0B0B0E', removeTransparency: true,
  });
  fs.writeFileSync(path.join(projectRoot, 'assets/auralis-icon-ios.png'), result.source);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
