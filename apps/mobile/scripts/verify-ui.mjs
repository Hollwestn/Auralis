import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const { chromium } = await import(pathToFileURL(path.resolve(process.argv[2])).href);
const backend = process.env.AURALIS_QA_SERVER || 'http://localhost:18080';
const directory = path.resolve(process.env.AURALIS_QA_WEB_DIR || 'dist/web');
const output = path.resolve('../../docs/verification');
const email = `mobile-qa-${randomUUID()}@auralis.invalid`;
const password = `QA-only-${randomUUID()}`;
const registered = await fetch(`${backend}/api/v1/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, displayName: 'Проверка интерфейса' }) });
if (!registered.ok) throw new Error(`QA registration: ${registered.status}`);
const session = await registered.json();
const fixture=new FormData();fixture.set('title','Reference video QA');fixture.set('artist','Auralis QA');fixture.set('file',new Blob([await fs.readFile('../../.runtime/video-gpu/signal.wav')],{type:'audio/wav'}),'reference-qa.wav');
const uploaded=await fetch(`${backend}/api/v1/catalog/upload`,{method:'POST',headers:{Authorization:`Bearer ${session.token}`},body:fixture});
if(!uploaded.ok)throw new Error(`QA music upload: ${uploaded.status}`);
const checks = []; const errors = [];
const server = http.createServer(async (request, response) => {
  try {
    if (request.url.startsWith('/api/v1/')) {
      const headers = { ...request.headers }; delete headers.host; delete headers['content-length'];
      // Web's HTMLAudioElement cannot send expo-audio's native custom headers.
      // Only this isolated QA proxy authenticates the ephemeral account's media stream.
      if(!headers.authorization&&/^\/api\/v1\/tracks\/[^/]+\/stream/.test(request.url))headers.authorization=`Bearer ${session.token}`;
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(backend + request.url, { method: request.method, headers, body: chunks.length ? Buffer.concat(chunks) : undefined });
      const reply={'Content-Type':upstream.headers.get('Content-Type')||'application/json'};for(const key of ['content-range','accept-ranges'])if(upstream.headers.has(key))reply[key]=upstream.headers.get(key);
      response.writeHead(upstream.status,reply); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    const requested = path.resolve(directory, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname));
    if (path.relative(directory, requested).startsWith('..')) { response.writeHead(403); response.end(); return; }
    let filename = requested; let stat; try { stat = await fs.stat(filename); } catch { filename = path.join(directory, 'index.html'); }
    if (stat?.isDirectory()) filename = path.join(directory, 'index.html');
    const types = { '.html':'text/html', '.js':'application/javascript', '.ttf':'font/ttf', '.png':'image/png', '.mp4':'video/mp4', '.json':'application/json', '.css':'text/css' };
    response.writeHead(200, { 'Content-Type': types[path.extname(filename)] || 'application/octet-stream' }); response.end(await fs.readFile(filename));
  } catch { response.writeHead(500); response.end('QA preview server error'); }
});
await new Promise(resolve => server.listen(18777, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.argv[3], headless: true });
try {
  await fs.mkdir(output, { recursive: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:18777', { waitUntil: 'networkidle' });
  await page.getByRole('button', {name:'Пропустить заставку'}).waitFor();
  await page.waitForTimeout(700);await page.screenshot({path:path.join(output,'mobile-intro.png')});
  await page.getByRole('button', {name:'Пропустить заставку'}).waitFor({state:'hidden'});checks.push('cinematic launch automatically opens login');
  if(await page.locator('canvas').count())throw new Error('Login must not create a GL surface');checks.push('login opens without a GL surface');
  await page.getByPlaceholder('https://music.example.com').fill('http://127.0.0.1:18777');
  await page.locator('input').nth(1).fill(email); await page.locator('input').nth(2).fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await page.getByText('Поймайте свой ритм.', { exact: true }).waitFor({ timeout: 20000 });
  await page.waitForTimeout(500); await page.screenshot({ path: path.join(output,'mobile-home.png') }); checks.push('real account login and home rendered');
  const visual=page.locator('[aria-label^="GPU-визуализация: Black Hole"] canvas');
  const idleBefore=await visual.screenshot();await page.waitForTimeout(900);const idleAfter=await visual.screenshot();
  if(idleBefore.equals(idleAfter))throw new Error('Idle Black Hole did not move');checks.push('Black Hole animates before playback');
  await page.getByRole('button',{name:'Слушать Reference video QA, Auralis QA',exact:true}).click();
  await page.getByRole('button',{name:'Пауза',exact:true}).waitFor();
  if(await page.locator('video').count())throw new Error('Black Hole still uses a video element');
  await page.waitForTimeout(1200);await page.screenshot({path:path.join(output,'mobile-model-playing.png')});
  console.log(await page.locator('[aria-label^="GPU-визуализация: Black Hole"] canvas').evaluate(c=>{const gl=c.getContext('webgl2')||c.getContext('webgl');return {width:c.width,height:c.height,bounds:c.getBoundingClientRect().toJSON(),viewport:gl?[...gl.getParameter(gl.VIEWPORT)]:null,error:gl?.getError(),program:!!gl?.getParameter(gl.CURRENT_PROGRAM)};}));
  await page.getByRole('button',{name:'Пауза',exact:true}).click();checks.push('server music plays; Black Hole has no video element');
  await page.getByText('Найти', { exact: true }).click(); await page.getByPlaceholder('Трек, исполнитель, альбом, жанр').fill('never-existing-search');
  await page.getByText('Пока ничего', { exact: true }).waitFor(); await page.waitForTimeout(350); await page.screenshot({ path: path.join(output,'mobile-search.png') }); checks.push('search screen and empty result rendered');
  await page.getByText('Моя музыка', { exact: true }).last().click();
  await page.getByRole('button', { name: 'Управлять плейлистами' }).click(); await page.getByPlaceholder('Музыка для вечера').fill('QA плейлист');
  await page.getByRole('button', { name: 'Создать плейлист', exact: true }).click();
  await page.getByText('QA плейлист', { exact: true }).first().waitFor(); await page.getByRole('button', { name: 'Готово', exact: true }).click();
  const playlists = await fetch(`${backend}/api/v1/playlists`, { headers: { Authorization: `Bearer ${session.token}` } }).then(response => response.json());
  if (!playlists.some(item => item.name === 'QA плейлист')) throw new Error('UI playlist was not created on the real server');
  await page.getByRole('button', { name: 'Готово', exact: true }).waitFor({ state: 'hidden' }); await page.waitForTimeout(350); await page.screenshot({ path: path.join(output,'mobile-library.png') }); checks.push('playlist created through UI and confirmed by real server');
  await page.getByText('Настройки', { exact: true }).last().click();
  for (const [theme, wire] of [['ObsidianYellow','OBSIDIAN_YELLOW'],['Monochrome','MONOCHROME'],['CrimsonNoir','CRIMSON_NOIR'],['DeepViolet','DEEP_VIOLET'],['ArcticSilver','ARCTIC_SILVER'],['Jade','JADE'],['Amber','AMBER'],['Azure','AZURE'],['ObsidianYellow','OBSIDIAN_YELLOW']]) {
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/api/v1/sync') && response.request().method() === 'PUT' && response.ok()), page.getByRole('button',{name:`Тема ${theme}`,exact:true}).click()]);
    const synced = await fetch(`${backend}/api/v1/sync`, { headers:{Authorization:`Bearer ${session.token}`} }).then(response=>response.json());
    if(synced.settings.theme !== wire) throw new Error(`Theme did not persist: ${theme}`);
  }
  await page.getByText('Скорость без музыки · 100%',{exact:true}).scrollIntoViewIfNeeded();
  await page.getByText('Скорость при воспроизведении · 75%',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Сбросить скорость',exact:true}).click();
  const speedSync=await fetch(`${backend}/api/v1/sync`,{headers:{Authorization:`Bearer ${session.token}`}}).then(response=>response.json());
  if(speedSync.settings.visualIdleSpeed!==1||speedSync.settings.visualPlayingSpeed!==.75)throw new Error('Default speeds failed to synchronize');
  checks.push('independent speed controls show 100% idle / 75% playing and persist defaults');
  await page.screenshot({path:path.join(output,'mobile-settings.png')}); checks.push('all eight themes changed through UI and persisted via sync');
  const visualBackground = page.getByRole('switch', { name:'Визуальный фон главной', exact:true });
  await visualBackground.scrollIntoViewIfNeeded();
  await Promise.all([page.waitForResponse(response => response.url().endsWith('/api/v1/sync') && response.request().method() === 'PUT' && response.ok()), visualBackground.click()]);
  const backgroundSync = await fetch(`${backend}/api/v1/sync`, { headers:{Authorization:`Bearer ${session.token}`} }).then(response=>response.json());
  if (backgroundSync.settings.backgroundEnabled !== false || backgroundSync.settings.backgroundAudioEnabled !== true) throw new Error('Visual background toggle changed background audio preference');
  await page.waitForTimeout(350);
  if (await visualBackground.isChecked()) throw new Error('Visual background switch did not settle to disabled');
  await page.screenshot({path:path.join(output,'mobile-settings-background.png')}); checks.push('visual background is independent of background audio in shared settings');
  if(errors.length) throw new Error(`Browser runtime errors: ${errors.join(' | ')}`);
  await fs.writeFile(path.join(output,'mobile-ui.json'), JSON.stringify({ platform:'React Native Web preview / Edge, 390x844', realBackend:backend, checks, errors, nativeDeviceTested:false },null,2));
  console.log(JSON.stringify({ checks, errors }));
} catch (failure) {
  for (const page of browser.contexts().flatMap(context=>context.pages())) {console.error(await page.evaluate(()=>({videos:[...document.querySelectorAll('video')].map(v=>({src:v.currentSrc,paused:v.paused,time:v.currentTime,width:v.videoWidth,height:v.videoHeight,ready:v.readyState,error:v.error?.message})),audios:[...document.querySelectorAll('audio')].map(v=>({paused:v.paused,time:v.currentTime,error:v.error?.message})),reduced:matchMedia('(prefers-reduced-motion: reduce)').matches})));await page.screenshot({path:path.join(output,'mobile-ui-failure.png')}).catch(()=>{});}
  console.error(JSON.stringify({ checks, errors })); throw failure;
} finally {
  await browser.close(); await new Promise(resolve=>server.close(resolve));
  await fetch(`${backend}/api/v1/account`, { method:'DELETE', headers:{Authorization:`Bearer ${session.token}`} });
}
