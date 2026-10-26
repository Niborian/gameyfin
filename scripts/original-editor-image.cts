// #161 exact reviewed PR-image UI fixture. Private stdin/stdout pipe only; no app patches.
// Supported Puppeteer APIs: Browser.createBrowserContext({downloadBehavior}) and
// DownloadBehavior policy=allowAndName (documented since25.12), native Dialog.accept.
const {createRequire} = require('node:module');
const {readFile, readdir, mkdir, stat} = require('node:fs/promises');
const puppeteer = createRequire('/home/pptruser/package.json')('puppeteer');
interface FixtureInput { origin: string; username: string; password: string; viewerPassword: string; providerName: string; providerKey: string; expectedVersion: string; }
interface SavedContent { id: number; name: string; required: boolean; defaultSelected: boolean; type: string; memberNames: string[]; }
interface SavedVariant { id: number; version: string; default: boolean; contents: SavedContent[]; }
interface SavedGame { id: number; variants: SavedVariant[]; }
const proof = {scope: 'reviewed-pr-synthetic-original-editor', stage: 'input', success: false,
  loginWithin15Seconds: false, nonDefaultPreserved: false, unrelatedDraftPreserved: false,
  adminProfileIdentityVerified: false, ordinaryProfileIdentityVerified: false,
  expectedNativeConfirmAccepted: false, immediateCanonicalCards: false, clickedExactDownload: false,
  ordinaryUserEditorAbsent: false, cleanupConfirmed: false,
  loginPhase: 'not-started', profileResponseObserved: false, profileIdentityMatched: false,
  authenticatedLandingObserved: false, profileTriggerObserved: false, profileDomIdentityMatched: false,
  editorPhase: 'not-started', gameRouteMatches: false, footerMatches: false, versionTriggerUnique: false,
  defaultVersionObserved: false, targetOptionObserved: false, targetVersionObserved: false,
  editorDialogObserved: false, splitPreviewObserved: false, typeTriggerFound: false, typeOptionsSeen: false,
  editorClosed: false, savedVersionShown: false, savedBaseShown: false, savedPatchShown: false, broadOriginalAbsent: false,
  controlledDiscardAccepted: false, keepEditingPreserved: false, escapePreserved: false,
  escapeFocusRestored: false, keepEditingFocusRestored: false, unexpectedNativeDialog: false, editorDialogStillPresent: false,
  closePhase: 'not-started', closeSnapshotObserved: false, closePromptPresent: false,
  closeKeepFocused: false, closeButtonFocused: false, closeDraftPresent: false,
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
  if (process.getuid?.() === 0 || process.env.GAMEYFIN_ORIGINAL_EDITOR !== 'reviewed-pr-synthetic-only' ||
      origin.protocol !== 'http:' || origin.port !== '8080' || !/^172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$/.test(origin.hostname) ||
      origin.href !== value.origin + '/' || value.username !== 'fixture-admin' ||
      typeof value.expectedVersion !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?$/.test(value.expectedVersion) || value.expectedVersion.length > 64 ||
      !/^[A-Za-z0-9_-]{32,64}$/.test(value.password) || !/^[A-Za-z0-9_-]{32,64}$/.test(value.viewerPassword) ||
      typeof value.providerName !== 'string' || value.providerName.length < 1 || value.providerName.length > 128 ||
      value.providerKey !== 'org.gameyfin.plugins.download.direct.DirectDownloadPlugin$DirectDownloadProvider') throw new Error('Fixture admission');
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
  try { await handle.asElement().asLocator().setTimeout(5000).click(); } finally { await handle.dispose(); }
}
async function login(page: any, input: FixtureInput, username: string, password: string): Promise<void> {
  proof.loginPhase = 'opening-login';
  proof.profileResponseObserved = false; proof.profileIdentityMatched = false;
  proof.authenticatedLandingObserved = false; proof.profileTriggerObserved = false; proof.profileDomIdentityMatched = false;
  await page.goto(input.origin + '/login', {waitUntil: 'domcontentloaded', timeout: 15000});
  await page.waitForSelector('input[name="username"]', {timeout: 15000});
  proof.loginPhase = 'form-ready';
  await page.type('input[name="username"]', username);
  await page.type('input[name="password"]', password);
  const start = performance.now();
  const remaining = () => { const value = 15000 - (performance.now() - start); if (value <= 0) throw new Error('Normal form auth deadline'); return Math.floor(value); };
  const within = async <T>(operation: Promise<T>): Promise<T> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try { return await Promise.race([operation, new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('Normal form auth deadline')), remaining()); })]); }
    finally { if (timer) clearTimeout(timer); }
  };
  const authenticatedProfile = page.waitForResponse(async (response: any) => {
    if (new URL(response.url()).origin !== input.origin || new URL(response.url()).pathname !== '/connect/UserEndpoint/getUserInfo') return false;
    proof.profileResponseObserved = true;
    if (response.status() !== 200) return false;
    try {
      const value = await response.json();
      const matches = value?.username === username && value.enabled === true && Number.isSafeInteger(value.id) && value.id > 0 && Array.isArray(value.roles);
      if (matches) proof.profileIdentityMatched = true;
      return matches;
    }
    catch { return false; }
  }, {timeout: 15000});
  void authenticatedProfile.catch(() => {});
  proof.loginPhase = 'submitted';
  await within(page.click('button[type="submit"]'));
  proof.loginPhase = 'profile-response';
  await within(authenticatedProfile);
  proof.loginPhase = 'authenticated-landing';
  await page.waitForFunction(() => location.pathname === '/' && document.querySelectorAll('nav div[data-slot="trigger"][aria-haspopup="true"] button').length === 1 &&
    !document.querySelector('input[name="password"]'), {timeout: remaining()});
  proof.authenticatedLandingObserved = true;
  const profile = await within(page.evaluateHandle(() => {
    // Published ProfileMenu wraps Avatar(as=button) in DropdownTrigger's DIV.
    // Confirmed actual DOM: the wrapper, not Avatar, owns aria-haspopup=true.
    const buttons = [...document.querySelectorAll('nav div[data-slot="trigger"][aria-haspopup="true"] button')];
    if (buttons.length !== 1) throw new Error('Unique profile trigger'); return buttons[0];
  }));
  proof.profileTriggerObserved = true; proof.loginPhase = 'profile-menu';
  await within(profile.asElement().click()); await profile.dispose();
  await page.waitForFunction((name: string) => [...document.querySelectorAll('[role="menu"] p')].some(text => text.textContent?.trim() === `Signed in as ${name}`), {timeout: remaining()}, username);
  proof.profileDomIdentityMatched = true;
  await within(page.keyboard.press('Escape'));
  if (performance.now() - start > 15000) throw new Error('Normal form auth deadline');
  proof.loginPhase = 'complete';
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
  page.on('dialog', (dialog: any) => {
    if (dialog.type() !== 'confirm' || !expectedDialog || dialog.message() !== expectedDialog) {
      proof.unexpectedNativeDialog = true;
      failed = true; void dialog.dismiss().catch(() => {}); return;
    }
    if (expectedDialog === REPLACE) proof.expectedNativeConfirmAccepted = true;
    expectedDialog = undefined;
    void dialog.accept().catch(() => { failed = true; });
  });
  proof.stage = 'normal-login';
  await login(page, input, input.username, input.password); proof.loginWithin15Seconds = true; proof.adminProfileIdentityVerified = true;
  proof.stage = 'editor';
  proof.editorPhase = 'opening-game';
  const gameReadyDeadline = performance.now() + 15000;
  const gameReadyRemaining = () => { const value = Math.floor(gameReadyDeadline - performance.now()); if (value < 1) throw new Error('Game-ready deadline'); return value; };
  await page.goto(input.origin + '/game/34000', {waitUntil: 'domcontentloaded', timeout: gameReadyRemaining()});
  proof.editorPhase = 'game-ready';
  // MainLayout's footer is not evidence that asynchronous catalogue data has
  // rendered GameView. Require the actual route, original Version control and
  // pinned selection together, within one navigation+readiness15s deadline.
  try {
    await page.waitForFunction((version: string) => location.pathname === '/game/34000' &&
      document.body.textContent?.includes('Unofficial Gameyfin ' + version) && document.body.textContent?.includes('Downloading: Normal 0.9') &&
      [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version')).length === 1,
    {timeout: gameReadyRemaining()}, input.expectedVersion);
  } finally {
    // Diagnostic only: independently sample each predicate even when the combined
    // gate fails. Never replace that failure, retry navigation, or retain DOM text.
    let snapshotTimer: ReturnType<typeof setTimeout> | undefined;
    try {
      const snapshot = await Promise.race([page.evaluate((version: string) => ({
        gameRouteMatches: location.pathname === '/game/34000',
        footerMatches: document.body.textContent?.includes('Unofficial Gameyfin ' + version) === true,
        versionTriggerUnique: [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version')).length === 1,
        defaultVersionObserved: document.body.textContent?.includes('Downloading: Normal 0.9') === true
      }), input.expectedVersion), new Promise<never>((_, reject) => { snapshotTimer = setTimeout(() => reject(new Error('Game-ready observation deadline')), 1000); })]);
      proof.gameRouteMatches = snapshot.gameRouteMatches === true;
      proof.footerMatches = snapshot.footerMatches === true;
      proof.versionTriggerUnique = snapshot.versionTriggerUnique === true;
      proof.defaultVersionObserved = snapshot.defaultVersionObserved === true;
    } catch { /* Missing diagnostic evidence remains false; the original gate controls acceptance. */ }
    finally { if (snapshotTimer) clearTimeout(snapshotTimer); }
  }
  // The original Version select, not a direct endpoint/state mutation.
  proof.editorPhase = 'version-trigger';
  const version = await page.evaluateHandle(() => {
    const buttons = [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version'));
    if (buttons.length !== 1) throw new Error('Unique version control'); return buttons[0];
  });
  // Real pointer interaction, with visibility/enabled/stable-layout preconditions.
  // A raw handle click can hit an animating control before its final position.
  await version.asElement().asLocator().setTimeout(5000).click(); await version.dispose();
  proof.editorPhase = 'version-options';
  await page.waitForFunction(() => [...document.querySelectorAll('[role="option"]')].some(option => option.textContent?.trim() === 'Normal 1.0 (latest)'), {timeout: 5000});
  proof.targetOptionObserved = true; proof.editorPhase = 'selected-version';
  await clickText(page, 'Normal 1.0 (latest)');
  await page.waitForFunction(() => document.body.textContent?.includes('Downloading: Normal 1.0'), {timeout: 5000});
  proof.targetVersionObserved = true; proof.editorPhase = 'editor-open';
  await clickText(page, 'Versions & content');
  await page.waitForSelector('[role="dialog"] input', {timeout: 5000});
  proof.editorDialogObserved = true; proof.editorPhase = 'split-preview';
  const split = await page.evaluateHandle(() => {
    let node: Element | null = [...document.querySelectorAll('[role="dialog"] p')].find(text => text.textContent === '/fixture/Normal 1.0') ?? null;
    while (node && ![...node.querySelectorAll('button')].some(button => button.textContent?.trim() === 'Split into base / extras')) node = node.parentElement;
    return [...(node?.querySelectorAll('button') ?? [])].find(button => button.textContent?.trim() === 'Split into base / extras');
  });
  await split.asElement().click(); await split.dispose();
  await page.waitForFunction(() => [...document.querySelectorAll('input')].some(input => input.value === '/fixture/Normal 1.0/Base'), {timeout: 5000});
  proof.splitPreviewObserved = true; proof.editorPhase = 'base-classification';
  // Classify only the reviewed Base child; Patch remains optional/default-selected.
  proof.editorPhase = 'type-trigger';
  const type = await page.evaluateHandle(() => {
    let node: Element | null = [...document.querySelectorAll('input')].find(input => input.value === '/fixture/Normal 1.0/Base') ?? null;
    while (node && ![...node.querySelectorAll('label')].some(label => label.textContent === 'Content type')) node = node.parentElement;
    return node?.querySelector('button[aria-haspopup="listbox"]');
  });
  try {
    const trigger = type.asElement();
    if (!trigger) throw new Error('Content type trigger');
    proof.typeTriggerFound = true;
    await trigger.asLocator().setTimeout(5000).click();
  } finally { await type.dispose(); }
  proof.editorPhase = 'type-options';
  await page.waitForFunction(() => [...document.querySelectorAll('[role="option"]')].some(option =>
    option.textContent?.trim() === 'BASE' && option.getBoundingClientRect().width > 0), {timeout: 5000});
  proof.typeOptionsSeen = true;
  await clickText(page, 'BASE');
  proof.editorPhase = 'draft';
  const edition = await page.evaluateHandle(() => {
    const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
    return label?.htmlFor ? document.getElementById(label.htmlFor) : null;
  });
  await edition.asElement().type('Unrelated draft retained'); await edition.dispose();
  proof.editorPhase = 'replace';
  expectedDialog = REPLACE;
  const savedResponse = page.waitForResponse((response: any) => new URL(response.url()).pathname === '/connect/GameEndpoint/attachVariantContent' && response.status() === 200, {timeout: 10000});
  await clickText(page, 'Replace base selection', '[role="dialog"]');
  proof.editorPhase = 'replacement-response';
  const saved: SavedGame = await (await savedResponse).json();
  proof.editorPhase = 'canonical-check';
  const selected = saved.variants.find(variant => variant.id === 134001);
  const pinned = saved.variants.find(variant => variant.id === 134000);
  if (!selected || !pinned?.default || selected.default || selected.version !== '1.0' || selected.contents.some(content => content.id === 268000)) throw new Error('Canonical replacement');
  const base = selected.contents.find(content => content.type === 'BASE');
  const patch = selected.contents.find(content => content.type === 'PATCH');
  // DTO memberNames enumerates selected paths, not recursive directory members.
  // The two actual grouped Base files are proved from the clicked ZIP below.
  if (!base?.required || !patch?.defaultSelected || patch.required || base.memberNames.join(',') !== 'Base' || selected.contents.length !== 2) throw new Error('Exact canonical contents');
  proof.nonDefaultPreserved = true;
  proof.editorPhase = 'draft-preserved';
  await page.waitForFunction(() => {
    const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
    return label?.htmlFor && (document.getElementById(label.htmlFor) as HTMLInputElement)?.value === 'Unrelated draft retained';
  }, {timeout: 5000}); proof.unrelatedDraftPreserved = true;
  proof.editorPhase = 'closing-editor';
  try {
  proof.closePhase = 'first-close';
  await clickText(page, 'Close', '[role="dialog"]');
  proof.closePhase = 'first-prompt';
  await page.waitForSelector('[role="alertdialog"][aria-label="Discard unsaved variant details"]', {timeout: 5000});
  proof.closePhase = 'initial-focus';
  await page.waitForFunction(() => document.activeElement?.closest('[role="alertdialog"]') !== null &&
    document.activeElement?.textContent?.trim() === 'Keep editing', {timeout: 5000});
  proof.closePhase = 'escape';
  await page.keyboard.press('Escape');
  proof.closePhase = 'escape-result';
  await page.waitForFunction(() => !document.querySelector('[role="alertdialog"]') &&
    [...document.querySelectorAll('input')].some(input => input.value === 'Unrelated draft retained') &&
    document.activeElement?.tagName === 'BUTTON' && document.activeElement?.textContent?.trim() === 'Close' &&
    document.activeElement?.closest('[role="dialog"]') !== null, {timeout: 5000});
  proof.escapePreserved = true; proof.escapeFocusRestored = true;
  proof.closePhase = 'second-close';
  await clickText(page, 'Close', '[role="dialog"]');
  proof.closePhase = 'second-prompt';
  await page.waitForSelector('[role="alertdialog"][aria-label="Discard unsaved variant details"]', {timeout: 5000});
  proof.closePhase = 'keep-editing';
  await clickText(page, 'Keep editing', '[role="alertdialog"]');
  proof.closePhase = 'keep-result';
  await page.waitForFunction(() => !document.querySelector('[role="alertdialog"]') &&
    [...document.querySelectorAll('input')].some(input => input.value === 'Unrelated draft retained') &&
    document.activeElement?.tagName === 'BUTTON' && document.activeElement?.textContent?.trim() === 'Close' &&
    document.activeElement?.closest('[role="dialog"]') !== null, {timeout: 5000});
  proof.keepEditingPreserved = true; proof.keepEditingFocusRestored = true;
  proof.closePhase = 'third-close';
  await clickText(page, 'Close', '[role="dialog"]');
  proof.closePhase = 'third-prompt';
  await page.waitForSelector('[role="alertdialog"][aria-label="Discard unsaved variant details"]', {timeout: 5000});
  proof.closePhase = 'discard';
  await clickText(page, 'Discard and close', '[role="alertdialog"]');
  proof.controlledDiscardAccepted = true;
  proof.closePhase = 'complete';
  } finally {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const snapshot = await Promise.race([page.evaluate(() => ({
        editorClosed: !document.querySelector('[role="dialog"]'),
        editorDialogStillPresent: [...document.querySelectorAll('[role="dialog"]')].some(dialog => dialog.textContent?.includes('Versions & content')),
        closePromptPresent: !!document.querySelector('[role="alertdialog"][aria-label="Discard unsaved variant details"]'),
        closeKeepFocused: document.activeElement?.closest('[role="alertdialog"]') != null && document.activeElement?.textContent?.trim() === 'Keep editing',
        closeButtonFocused: document.activeElement?.tagName === 'BUTTON' && document.activeElement?.textContent?.trim() === 'Close',
        closeDraftPresent: [...document.querySelectorAll('input')].some(input => input.value === 'Unrelated draft retained')
      })), new Promise<never>((_, reject) => {timer=setTimeout(() => reject(new Error('Close observation deadline')),1000);})]);
      Object.assign(proof,snapshot); proof.closeSnapshotObserved=true;
    } catch { /* Diagnostic absence never replaces the original failure. */ }
    finally {if (timer) clearTimeout(timer);}
  }
  proof.editorPhase = 'cards';
  try {
    await page.waitForFunction(() => !document.querySelector('[role="dialog"]') && document.body.textContent?.includes('Downloading: Normal 1.0') &&
      document.body.textContent?.includes('Base') && document.body.textContent?.includes('patch.bin') && !document.body.textContent?.includes('Broad original'), {timeout: 5000});
  } finally {
    const cards = await page.evaluate(() => ({
      editorClosed: !document.querySelector('[role="dialog"]'),
      editorDialogStillPresent: [...document.querySelectorAll('[role="dialog"]')].some(dialog => dialog.textContent?.includes('Versions & content')),
      savedVersionShown: document.body.textContent?.includes('Downloading: Normal 1.0') === true,
      savedBaseShown: document.body.textContent?.includes('Base') === true,
      savedPatchShown: document.body.textContent?.includes('patch.bin') === true,
      broadOriginalAbsent: !document.body.textContent?.includes('Broad original')
    }));
    Object.assign(proof, cards);
  }
  proof.immediateCanonicalCards = true;
  proof.editorPhase = 'complete';
  proof.stage = 'immediate-download';
  let exactDownload = false;
  page.on('request', (request: any) => {
    const url = new URL(request.url());
    if (url.origin === input.origin && url.pathname === '/download/34000') {
      const ids = url.searchParams.getAll('contentIds').map(Number).sort((a, b) => a - b);
      exactDownload = url.searchParams.get('provider') === input.providerKey && url.searchParams.get('variantId') === '134001' && url.searchParams.get('explicitSelection') === 'true' &&
        ids.length === 2 && ids.join(',') === [base.id, patch.id].sort((a, b) => a - b).join(',');
    }
  });
  // ComboButton shows the preferred/first provider only; other published
  // providers are legitimate. Choose Direct through the original dropdown,
  // never by mutating preferences or calling a download endpoint ourselves.
  const directVisible = await page.evaluate((name: string) => [...document.querySelectorAll('button.w-52 p')].some(text => text.textContent?.trim() === name), input.providerName);
  if (!directVisible) {
    const trigger = await page.evaluateHandle(() => {
      const buttons = [...document.querySelectorAll('button.w-52')];
      if (buttons.length !== 1) throw new Error('Unique download button');
      const triggers = [...(buttons[0].parentElement?.querySelectorAll('button[data-slot="trigger"][aria-haspopup="true"]') ?? [])];
      if (triggers.length !== 1) throw new Error('Unique download menu');
      return triggers[0];
    });
    await trigger.asElement().click(); await trigger.dispose();
    await page.waitForSelector('[role="menu"][aria-label="Merge options"]', {timeout: 5000});
    const option = await page.evaluateHandle((key: string) => {
      const items = [...document.querySelectorAll('[role="menu"][aria-label="Merge options"] [data-key]')]
        .filter(item => item.getAttribute('data-key') === key && item.getAttribute('aria-disabled') !== 'true');
      if (items.length !== 1) throw new Error('Unique direct provider option');
      return items[0];
    }, input.providerKey);
    await option.asElement().click(); await option.dispose();
    await page.waitForFunction((name: string) => [...document.querySelectorAll('button.w-52 p')].some(text => text.textContent?.trim() === name), {timeout: 5000}, input.providerName);
  }
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
