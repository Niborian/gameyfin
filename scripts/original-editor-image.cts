// #161 exact published-image UI fixture. Private stdin/stdout pipe only; no app patches.
// Supported Puppeteer APIs: Browser.createBrowserContext({downloadBehavior}) and
// DownloadBehavior policy=allowAndName (documented since25.12), native Dialog.accept.
const {createRequire} = require('node:module');
const {readFile, readdir, mkdir, stat} = require('node:fs/promises');
const puppeteer = createRequire('/home/pptruser/package.json')('puppeteer');
interface FixtureInput { origin: string; username: string; password: string; viewerPassword: string; providerName: string; }
interface SavedContent { id: number; name: string; required: boolean; defaultSelected: boolean; type: string; memberNames: string[]; }
interface SavedVariant { id: number; version: string; default: boolean; contents: SavedContent[]; }
interface SavedGame { id: number; variants: SavedVariant[]; }
const proof = {scope: 'published-2.4.6-synthetic-original-editor', stage: 'input', success: false,
  loginWithin15Seconds: false, nonDefaultPreserved: false, unrelatedDraftPreserved: false,
  adminProfileIdentityVerified: false, ordinaryProfileIdentityVerified: false,
  expectedNativeConfirmAccepted: false, immediateCanonicalCards: false, clickedExactDownload: false,
  ordinaryUserEditorAbsent: false, cleanupConfirmed: false,
  sandboxDisabledForIsolatedSyntheticAppOnly: true};
let browser: any;
let zip = '';
let failed = false;
let totalTimer: ReturnType<typeof setTimeout>;
async function privateInput(): Promise<FixtureInput> {
  process.stdin.setEncoding('utf8'); let text = '';
  for await (const part of process.stdin) { text += part; if (text.length > 2048) throw new Error('Input bound'); }
  const value: FixtureInput = JSON.parse(text);
  const origin = new URL(value.origin);
  if (process.getuid?.() === 0 || process.env.GAMEYFIN_ORIGINAL_EDITOR !== 'published-246-synthetic-only' ||
      origin.protocol !== 'http:' || origin.port !== '8080' || !/^172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$/.test(origin.hostname) ||
      origin.href !== value.origin + '/' || value.username !== 'fixture-admin' ||
      !/^[A-Za-z0-9_-]{32,64}$/.test(value.password) || !/^[A-Za-z0-9_-]{32,64}$/.test(value.viewerPassword) ||
      typeof value.providerName !== 'string' || value.providerName.length > 128) throw new Error('Fixture admission');
  return value;
}
async function clickText(page: any, text: string, scope = 'body'): Promise<void> {
  const handle = await page.evaluateHandle((label: string, selector: string) => {
    const roots = [...document.querySelectorAll(selector)];
    const candidates = roots.flatMap(root => [...root.querySelectorAll('button,[role="option"]')])
      .filter((node: any) => node.textContent?.trim() === label && !node.disabled && node.getBoundingClientRect().width > 0);
    if (candidates.length !== 1) throw new Error('Unique UI control');
    return candidates[0];
  }, text, scope);
  try { await handle.asElement().click(); } finally { await handle.dispose(); }
}
async function login(page: any, input: FixtureInput, username: string, password: string): Promise<void> {
  await page.goto(input.origin + '/login', {waitUntil: 'domcontentloaded', timeout: 15000});
  await page.waitForSelector('input[name="username"]', {timeout: 15000});
  await page.type('input[name="username"]', username);
  await page.type('input[name="password"]', password);
  const start = performance.now();
  const remaining = () => { const value = 15000 - (performance.now() - start); if (value <= 0) throw new Error('Normal form auth deadline'); return Math.floor(value); };
  const authenticatedProfile = page.waitForResponse(async (response: any) => {
    if (new URL(response.url()).origin !== input.origin || new URL(response.url()).pathname !== '/connect/UserEndpoint/getUserInfo' || response.status() !== 200) return false;
    try { const value = await response.json(); return value?.username === username && value.enabled === true && Number.isSafeInteger(value.id) && value.id > 0 && Array.isArray(value.roles); }
    catch { return false; }
  }, {timeout: 15000});
  void authenticatedProfile.catch(() => {});
  await page.click('button[type="submit"]');
  await authenticatedProfile;
  await page.waitForFunction(() => location.pathname === '/' && !!document.querySelector('nav button[aria-haspopup="menu"]') &&
    !document.querySelector('input[name="password"]'), {timeout: remaining()});
  const profile = await page.evaluateHandle(() => {
    const buttons = [...document.querySelectorAll('nav button[aria-haspopup="menu"]')];
    if (buttons.length !== 1) throw new Error('Unique profile trigger'); return buttons[0];
  });
  await profile.asElement().click(); await profile.dispose();
  await page.waitForFunction((name: string) => [...document.querySelectorAll('[role="menu"] p')].some(text => text.textContent?.trim() === `Signed in as ${name}`), {timeout: remaining()}, username);
  await page.keyboard.press('Escape');
  if (performance.now() - start > 15000) throw new Error('Normal form auth deadline');
}
async function run(): Promise<void> {
  const input = await privateInput();
  proof.stage = 'launch';
  // Explicit, narrow trust boundary: only fresh synthetic app data on one internal
  // Docker network, no personal profile, public content, credentials or host ports.
  // No capabilities/privileged sandbox workaround. This does not bless live usage.
  browser = await puppeteer.launch({headless: true, timeout: 10000, userDataDir: '/tmp/editor-profile',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-background-networking',
      '--disable-component-update', '--disable-sync', '--no-first-run']});
  await mkdir('/tmp/editor-downloads', {mode: 0o700});
  const context = await browser.createBrowserContext({downloadBehavior: {policy: 'allowAndName', downloadPath: '/tmp/editor-downloads'}});
  const page = await context.newPage();
  await page.setRequestInterception(true);
  page.on('request', (request: any) => {
    const url = new URL(request.url());
    const allowed = url.origin === input.origin || url.protocol === 'data:' || url.protocol === 'blob:' && url.origin === input.origin;
    void (allowed ? request.continue() : request.abort()).catch(() => { failed = true; });
  });
  let expectedDialog: string | undefined;
  const REPLACE = 'Replace the original broad BASE selection with these reviewed items? Only catalogue metadata changes; no files will be moved or deleted.';
  const DISCARD = 'Discard unsaved variant details before leaving this editor?';
  page.on('dialog', (dialog: any) => {
    if (dialog.type() !== 'confirm' || !expectedDialog || dialog.message() !== expectedDialog) {
      failed = true; void dialog.dismiss().catch(() => {}); return;
    }
    if (expectedDialog === REPLACE) proof.expectedNativeConfirmAccepted = true;
    expectedDialog = undefined;
    void dialog.accept().catch(() => { failed = true; });
  });
  proof.stage = 'normal-login';
  await login(page, input, input.username, input.password); proof.loginWithin15Seconds = true; proof.adminProfileIdentityVerified = true;
  proof.stage = 'editor';
  await page.goto(input.origin + '/game/34000', {waitUntil: 'domcontentloaded', timeout: 15000});
  await page.waitForFunction(() => document.body.textContent?.includes('Unofficial Gameyfin 2.4.6'), {timeout: 15000});
  // The original Version select, not a direct endpoint/state mutation.
  const version = await page.evaluateHandle(() => [...document.querySelectorAll('button[aria-haspopup="listbox"]')]
    .find(node => node.parentElement?.textContent?.includes('Version')));
  await version.asElement().click(); await version.dispose();
  await page.waitForSelector('[role="option"]', {timeout: 5000});
  await clickText(page, 'Normal 1.0 (latest)');
  await page.waitForFunction(() => document.body.textContent?.includes('Downloading: Normal 1.0'), {timeout: 5000});
  await clickText(page, 'Versions & content');
  await page.waitForSelector('[role="dialog"] input', {timeout: 5000});
  const split = await page.evaluateHandle(() => {
    let node: Element | null = [...document.querySelectorAll('[role="dialog"] p')].find(text => text.textContent === '/fixture/Normal 1.0') ?? null;
    while (node && ![...node.querySelectorAll('button')].some(button => button.textContent?.trim() === 'Split into base / extras')) node = node.parentElement;
    return [...(node?.querySelectorAll('button') ?? [])].find(button => button.textContent?.trim() === 'Split into base / extras');
  });
  await split.asElement().click(); await split.dispose();
  await page.waitForFunction(() => [...document.querySelectorAll('input')].some(input => input.value === '/fixture/Normal 1.0/Base'), {timeout: 5000});
  // Classify only the reviewed Base child; Patch remains optional/default-selected.
  const type = await page.evaluateHandle(() => {
    let node: Element | null = [...document.querySelectorAll('input')].find(input => input.value === '/fixture/Normal 1.0/Base') ?? null;
    while (node && ![...node.querySelectorAll('label')].some(label => label.textContent === 'Content type')) node = node.parentElement;
    return node?.querySelector('button[aria-haspopup="listbox"]');
  });
  await type.asElement().click(); await type.dispose();
  await clickText(page, 'BASE');
  const edition = await page.evaluateHandle(() => {
    const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
    return label?.htmlFor ? document.getElementById(label.htmlFor) : null;
  });
  await edition.asElement().type('Unrelated draft retained'); await edition.dispose();
  expectedDialog = REPLACE;
  const savedResponse = page.waitForResponse((response: any) => new URL(response.url()).pathname === '/connect/GameEndpoint/attachVariantContent' && response.status() === 200, {timeout: 10000});
  await clickText(page, 'Replace base selection', '[role="dialog"]');
  const saved: SavedGame = await (await savedResponse).json();
  const selected = saved.variants.find(variant => variant.id === 134001);
  const pinned = saved.variants.find(variant => variant.id === 134000);
  if (!selected || !pinned?.default || selected.default || selected.version !== '1.0' || selected.contents.some(content => content.id === 268000)) throw new Error('Canonical replacement');
  const base = selected.contents.find(content => content.type === 'BASE');
  const patch = selected.contents.find(content => content.type === 'PATCH');
  // DTO memberNames enumerates selected paths, not recursive directory members.
  // The two actual grouped Base files are proved from the clicked ZIP below.
  if (!base?.required || !patch?.defaultSelected || patch.required || base.memberNames.join(',') !== 'Base' || selected.contents.length !== 2) throw new Error('Exact canonical contents');
  proof.nonDefaultPreserved = true;
  await page.waitForFunction(() => {
    const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
    return label?.htmlFor && (document.getElementById(label.htmlFor) as HTMLInputElement)?.value === 'Unrelated draft retained';
  }, {timeout: 5000}); proof.unrelatedDraftPreserved = true;
  expectedDialog = DISCARD; await clickText(page, 'Close', '[role="dialog"]');
  await page.waitForFunction(() => !document.querySelector('[role="dialog"]') && document.body.textContent?.includes('Downloading: Normal 1.0') &&
    document.body.textContent?.includes('Base') && document.body.textContent?.includes('patch.bin') && !document.body.textContent?.includes('Broad original'), {timeout: 5000});
  proof.immediateCanonicalCards = true;
  proof.stage = 'immediate-download';
  let exactDownload = false;
  page.on('request', (request: any) => {
    const url = new URL(request.url());
    if (url.origin === input.origin && url.pathname === '/download/34000') {
      const ids = url.searchParams.getAll('contentIds').map(Number).sort((a, b) => a - b);
      exactDownload = url.searchParams.get('variantId') === '134001' && url.searchParams.get('explicitSelection') === 'true' &&
        ids.length === 2 && ids.join(',') === [base.id, patch.id].sort((a, b) => a - b).join(',');
    }
  });
  const downloadButton = await page.evaluateHandle((name: string) => [...document.querySelectorAll('button')].find(button =>
    [...button.querySelectorAll('p')].some(text => text.textContent?.trim() === name)), input.providerName);
  await downloadButton.asElement().click(); await downloadButton.dispose();
  const end = performance.now() + 10000;
  let files: string[] = [];
  while (performance.now() < end) {
    files = await readdir('/tmp/editor-downloads');
    if (files.length === 1 && /^[a-f0-9-]{36}$/.test(files[0])) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  if (!exactDownload || files.length !== 1 || !/^[a-f0-9-]{36}$/.test(files[0])) throw new Error('Clicked download');
  const path = '/tmp/editor-downloads/' + files[0]; const info = await stat(path);
  if (!info.isFile() || info.size < 1 || info.size > 65536) throw new Error('ZIP byte bound');
  zip = (await readFile(path)).toString('base64'); proof.clickedExactDownload = true;
  await context.close();
  proof.stage = 'ordinary-user';
  const viewer = await browser.createBrowserContext(); const viewerPage = await viewer.newPage();
  await viewerPage.setRequestInterception(true);
  viewerPage.on('request', (request: any) => { const url = new URL(request.url()); void (url.origin === input.origin || url.protocol === 'data:' ? request.continue() : request.abort()).catch(() => { failed = true; }); });
  await login(viewerPage, input, 'fixture-user', input.viewerPassword);
  proof.ordinaryProfileIdentityVerified = true;
  await viewerPage.goto(input.origin + '/game/34000', {waitUntil: 'domcontentloaded', timeout: 10000});
  await viewerPage.waitForFunction(() => document.body.textContent?.includes('Downloading: Normal 0.9'), {timeout: 10000});
  proof.ordinaryUserEditorAbsent = await viewerPage.evaluate(() => ![...document.querySelectorAll('button')].some(button => button.textContent?.trim() === 'Versions & content'));
  if (!proof.ordinaryUserEditorAbsent || failed || expectedDialog) throw new Error('UI gate');
  proof.success = true;
}
async function main(): Promise<void> {
  try { await Promise.race([run(), new Promise<never>((_, reject) => { totalTimer = setTimeout(() => reject(new Error('Browser90s')), 90000); })]); }
  catch { proof.success = false; process.exitCode = 1; zip = ''; }
  finally {
    clearTimeout(totalTimer!);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try { if (browser) await Promise.race([browser.close(), new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('Close')), 5000); })]); proof.cleanupConfirmed = true; }
    catch { proof.success = false; process.exitCode = 1; zip = ''; }
    finally { if (timer) clearTimeout(timer); }
    if (proof.success) proof.stage = 'complete';
    // This is a private bounded controller pipe. Never upload/persist this envelope.
    process.stdout.write(JSON.stringify({kind: 'private-editor-result', proof, zip}) + '\n');
  }
}
void main();
