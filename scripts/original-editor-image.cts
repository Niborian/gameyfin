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
const gameDiagnostic = () => ({phase: 'not-started', truncated: false, pageerrorSeen: false,
  domObserved: false, profilePresent: false, signInPresent: false, loginFormPresent: false, errorHeadingPresent: false,
  families: Object.fromEntries(['game', 'library', 'user'].map(key => [key, {count: 0, status: null as number | null,
    finished: false, failed: false, canceled: false, continueAttempted: false, continueFulfilled: false, continueRejected: false,
    bodyPending: false, bodyClassified: false,
    bodyUnavailable: false, authNull: false, authMatch: false, arrayResponse: false, targetPresent: false}]))});
const proof = {scope: 'reviewed-pr-synthetic-original-editor', stage: 'input', success: false,
  loginWithin15Seconds: false, nonDefaultPreserved: false, unrelatedDraftPreserved: false,
  adminProfileIdentityVerified: false, ordinaryProfileIdentityVerified: false,
  expectedNativeConfirmAccepted: false, immediateCanonicalCards: false, clickedExactDownload: false,
  ordinaryUserEditorAbsent: false, cleanupConfirmed: false,
  ordinarySavedDetailsVisible: false,
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
  closeClick: {observed: false, preExists: false, preEnabled: false, preInViewport: false, preCenterOwn: false, preCenterToast: false,
    postExists: false, postEnabled: false, postInViewport: false, postCenterOwn: false, postCenterToast: false,
    trustedCloseClick: false, trustedOtherClick: false},
  gameDiagnostics: gameDiagnostic(),
  repeatPhase: 'not-started', viewerLogoutObserved: false, loggedOutLoginFormObserved: false,
  repeatedViewerIdentityVerified: false,
  sandboxDisabledForIsolatedSyntheticAppOnly: true};
let browser: any;
let zip = '';
let failed = false;
let totalTimer: ReturnType<typeof setTimeout>;
/** Only this game navigation generation; fixed families and <=4 observations each.
 * Response values exist transiently for synthetic boolean classification, never receipts.
 */
function observeGameNavigation(page: any, input: FixtureInput) {
  const diagnostic = gameDiagnostic(); diagnostic.phase = 'observing';
  let active = true;
  const latest = new Map<string, any>();
  const timers = new Set<ReturnType<typeof setTimeout>>();
  function family(request: any): string | undefined {
    try {
      const url = new URL(request.url());
      if (url.origin !== input.origin || request.method() !== 'POST') return;
      return ({'/connect/GameEndpoint/getAll': 'game', '/connect/LibraryEndpoint/getAll': 'library',
        '/connect/UserEndpoint/getUserInfo': 'user'} as Record<string, string>)[url.pathname];
    } catch { return; }
  }
  function current(request: any) {
    const key = family(request); return active && key && latest.get(key) === request ? diagnostic.families[key] : undefined;
  }
  const requested = (request: any) => {
    const key = family(request); if (!active || !key) return;
    try {
      const frame = request.frame();
      if (frame !== page.mainFrame() || new URL(frame.url()).pathname !== '/game/34000') return;
    } catch { return; }
    const before = diagnostic.families[key];
    if (before.count === 4) {diagnostic.truncated = true; return;}
    diagnostic.families[key] = {...gameDiagnostic().families[key], count: before.count + 1}; latest.set(key, request);
  };
  const responded = (response: any) => {
    const request = response.request(), row = current(request); if (!row) return;
    const status = response.status(); if (Number.isInteger(status) && status >= 100 && status <= 599) row.status = status;
    if (status !== 200 || family(request) === 'library') return;
    // No unbounded/unknown-size response-body observation. Missing length stays unknown.
    const length = response.headers()['content-length'];
    if (!/^[0-9]+$/.test(length ?? '') || Number(length) > 65536) {row.bodyUnavailable = true; return;}
    row.bodyPending = true;
    let timer: ReturnType<typeof setTimeout>;
    void Promise.race([response.buffer(), new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('Observation deadline')), 1000); timers.add(timer);
    })]).then((bytes: any) => {
      if (!current(request)) return;
      if (bytes.length > 65536) throw new Error('Observation size');
      const value = JSON.parse(bytes.toString('utf8'));
      row.bodyClassified = true;
      if (family(request) === 'user') {
        row.authNull = value === null;
        row.authMatch = value?.username === input.username && value?.enabled === true && Number.isSafeInteger(value?.id) && value.id > 0;
      } else {row.arrayResponse = Array.isArray(value); row.targetPresent = Array.isArray(value) && value.some(item => item?.id === 34000);}
    }).catch(() => {if (current(request)) row.bodyUnavailable = true;}).finally(() => {
      clearTimeout(timer!); timers.delete(timer!); if (current(request)) row.bodyPending = false;
    });
  };
  const finished = (request: any) => {const row = current(request); if (row) row.finished = true;};
  const continuation = (request: any, phase: 'attempted' | 'fulfilled' | 'rejected') => {
    const row = current(request); if (!row) return;
    if (phase === 'attempted') row.continueAttempted = true;
    else if (phase === 'fulfilled') row.continueFulfilled = true;
    else row.continueRejected = true;
  };
  const requestFailed = (request: any) => {const row = current(request); if (row) {row.failed = true; row.canceled = request.failure()?.errorText === 'net::ERR_ABORTED';}};
  const pageerror = () => {if (active) diagnostic.pageerrorSeen = true;};
  page.on('response', responded); page.on('requestfinished', finished);
  page.on('requestfailed', requestFailed); page.on('pageerror', pageerror);
  return {diagnostic, requested, continuation, stop() {
    active = false; diagnostic.phase = 'complete'; timers.forEach(clearTimeout); timers.clear(); latest.clear();
    page.off('response', responded); page.off('requestfinished', finished);
    page.off('requestfailed', requestFailed); page.off('pageerror', pageerror);
  }};
}
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
async function login(page: any, input: FixtureInput, username: string, password: string, alreadyOnForm = false): Promise<void> {
  proof.loginPhase = 'opening-login';
  proof.profileResponseObserved = false; proof.profileIdentityMatched = false;
  proof.authenticatedLandingObserved = false; proof.profileTriggerObserved = false; proof.profileDomIdentityMatched = false;
  if (!alreadyOnForm) await page.goto(input.origin + '/login', {waitUntil: 'domcontentloaded', timeout: 15000});
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
  let continuationObserver: ReturnType<typeof observeGameNavigation> | undefined;
  await page.setRequestInterception(true);
  page.on('request', (request: any) => {
    const url = new URL(request.url());
    const allowed = url.origin === input.origin || url.protocol === 'data:' || url.protocol === 'blob:' && url.origin === input.origin;
    const resolution = allowed ? request.continue() : request.abort();
    // The original decision/command runs first, without waiting for observation.
    const observer = continuationObserver;
    observer?.requested(request); observer?.continuation(request, 'attempted');
    void resolution.then(() => {observer?.continuation(request, 'fulfilled');}, () => {
      failed = true; observer?.continuation(request, 'rejected');
    });
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
  const navigationObservation = observeGameNavigation(page, input);
  continuationObserver = navigationObservation;
  proof.gameDiagnostics = navigationObservation.diagnostic;
  // MainLayout's footer is not evidence that asynchronous catalogue data has
  // rendered GameView. Require the actual route, original Version control and
  // pinned selection together, within one navigation+readiness15s deadline.
  try {
    await page.goto(input.origin + '/game/34000', {waitUntil: 'domcontentloaded', timeout: gameReadyRemaining()});
    proof.editorPhase = 'game-ready';
    await page.waitForFunction((version: string) => location.pathname === '/game/34000' &&
      document.body.textContent?.includes('Unofficial Gameyfin ' + version) && document.body.textContent?.includes('Downloading: v0.9') &&
      [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version')).length === 1,
    {timeout: gameReadyRemaining()}, input.expectedVersion);
  } finally {
    continuationObserver = undefined;
    navigationObservation.stop();
    // Diagnostic only: independently sample each predicate even when the combined
    // gate fails. Never replace that failure, retry navigation, or retain DOM text.
    let snapshotTimer: ReturnType<typeof setTimeout> | undefined;
    try {
      const snapshot = await Promise.race([page.evaluate((version: string) => ({
        gameRouteMatches: location.pathname === '/game/34000',
        footerMatches: document.body.textContent?.includes('Unofficial Gameyfin ' + version) === true,
        versionTriggerUnique: [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version')).length === 1,
        defaultVersionObserved: document.body.textContent?.includes('Downloading: v0.9') === true,
        profilePresent: document.querySelectorAll('nav div[data-slot="trigger"][aria-haspopup="true"] button').length === 1,
        signInPresent: document.querySelectorAll('nav button.gradient-primary').length === 1 && !document.querySelector('nav button.size-8'),
        loginFormPresent: !!document.querySelector('input[name="username"]'),
        errorHeadingPresent: [...document.querySelectorAll('h1')].some(node => node.textContent?.trim().startsWith('404 – '))
      }), input.expectedVersion), new Promise<never>((_, reject) => { snapshotTimer = setTimeout(() => reject(new Error('Game-ready observation deadline')), 1000); })]);
      proof.gameRouteMatches = snapshot.gameRouteMatches === true;
      proof.footerMatches = snapshot.footerMatches === true;
      proof.versionTriggerUnique = snapshot.versionTriggerUnique === true;
      proof.defaultVersionObserved = snapshot.defaultVersionObserved === true;
      Object.assign(proof.gameDiagnostics, {domObserved: true, profilePresent: snapshot.profilePresent === true,
        signInPresent: snapshot.signInPresent === true, loginFormPresent: snapshot.loginFormPresent === true,
        errorHeadingPresent: snapshot.errorHeadingPresent === true});
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
  await page.waitForFunction(() => [...document.querySelectorAll('[role="option"]')].some(option => option.textContent?.trim() === 'v1.5.0 · Latest'), {timeout: 5000});
  proof.targetOptionObserved = true; proof.editorPhase = 'selected-version';
  await clickText(page, 'v1.5.0 · Latest');
  await page.waitForFunction(() => document.body.textContent?.includes('Downloading: v1.5.0'), {timeout: 5000});
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
  // Replace the prefilled controlled input using real keyboard events, so React
  // observes the same selection/deletion/typing sequence as a user.
  try {
    await edition.asElement().click();
    await page.waitForFunction(() => {
      const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
      return label?.htmlFor && document.activeElement === document.getElementById(label.htmlFor);
    }, {timeout: 5000});
    await page.keyboard.down('Control');
    try { await page.keyboard.press('KeyA'); } finally { await page.keyboard.up('Control'); }
    await page.keyboard.press('Backspace');
    await page.keyboard.type('Unrelated draft retained');
  } finally { await edition.dispose(); }
  await page.waitForFunction(() => {
    const label = [...document.querySelectorAll('label')].find(node => node.textContent === 'Edition');
    return label?.htmlFor && (document.getElementById(label.htmlFor) as HTMLInputElement)?.value === 'Unrelated draft retained';
  }, {timeout: 5000});
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
  const close = await page.evaluateHandle(() => {
    const buttons = [...document.querySelectorAll('[role="dialog"] button')].filter(button => button.textContent?.trim() === 'Close');
    if (buttons.length !== 1) throw new Error('Unique Close control'); return buttons[0];
  });
  let clickObservation: any;
  async function boundedCloseObservation(operation: Promise<any>) {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try { return await Promise.race([operation, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('Close click observation deadline')), 1000);
    })]); } finally {if (timer) clearTimeout(timer);}
  }
  try {
    await close.asElement().scrollIntoView(); // Normal UI scrolling, not a scripted click.
    try {clickObservation = await boundedCloseObservation(page.evaluateHandle((button: HTMLButtonElement) => {
      let trustedCloseClick = false, trustedOtherClick = false;
      const clicked = (event: MouseEvent) => {
        if (!event.isTrusted) return;
        if (event.target instanceof Node && button.contains(event.target)) trustedCloseClick = true;
        else trustedOtherClick = true;
      };
      const sample = () => {
        const rect = button.getBoundingClientRect(), x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
        const inside = rect.width > 0 && rect.height > 0 && x >= 0 && y >= 0 && x < innerWidth && y < innerHeight;
        const hit = inside ? document.elementFromPoint(x, y) : null;
        return {Exists: button.isConnected, Enabled: !button.disabled && button.getAttribute('aria-disabled') !== 'true',
          InViewport: inside, CenterOwn: !!hit && button.contains(hit), CenterToast: !!hit?.closest('[aria-label="toast"]')};
      };
      const before = sample();
      document.addEventListener('click', clicked, {capture: true, passive: true});
      // Remote fallback also removes a listener if its handle arrives after the
      // bounded observation call timed out. This never retries or delays a click.
      const expiry = setTimeout(() => document.removeEventListener('click', clicked, true), 6000);
      return {finish() {
        clearTimeout(expiry);
        document.removeEventListener('click', clicked, true);
        const after = sample();
        return {observed: true, preExists: before.Exists, preEnabled: before.Enabled, preInViewport: before.InViewport,
          preCenterOwn: before.CenterOwn, preCenterToast: before.CenterToast, postExists: after.Exists,
          postEnabled: after.Enabled, postInViewport: after.InViewport, postCenterOwn: after.CenterOwn,
          postCenterToast: after.CenterToast, trustedCloseClick, trustedOtherClick};
      }};
    }, close));} catch { /* Observation absence never substitutes for the real click/prompt gate. */ }
    await close.asElement().asLocator().setTimeout(5000).click();
  } finally {
    try {
      if (clickObservation) {
        try {proof.closeClick = await boundedCloseObservation(clickObservation.evaluate((value: any) => value.finish()));}
        catch { /* Missing evidence cannot bypass the original prompt gate. */ }
        finally {await clickObservation.dispose();}
      }
    } finally {await close.dispose();}
  }
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
    await page.waitForFunction(() => !document.querySelector('[role="dialog"]') && document.body.textContent?.includes('Downloading: v1.5.0') &&
      document.body.textContent?.includes('Base') && document.body.textContent?.includes('patch.bin') && !document.body.textContent?.includes('Broad original'), {timeout: 5000});
  } finally {
    const cards = await page.evaluate(() => ({
      editorClosed: !document.querySelector('[role="dialog"]'),
      editorDialogStillPresent: [...document.querySelectorAll('[role="dialog"]')].some(dialog => dialog.textContent?.includes('Versions & content')),
      savedVersionShown: document.body.textContent?.includes('Downloading: v1.5.0') === true,
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
  await viewerPage.waitForFunction(() => document.body.textContent?.includes('Downloading: v0.9'), {timeout: 10000});
  proof.ordinaryUserEditorAbsent = await viewerPage.evaluate(() => ![...document.querySelectorAll('button')].some(button => button.textContent?.trim() === 'Versions & content'));
  if (!proof.ordinaryUserEditorAbsent || failed || expectedDialog) throw new Error('UI gate');
  const viewerVersion = await viewerPage.evaluateHandle(() => {
    const controls = [...document.querySelectorAll('button[aria-haspopup="listbox"]')].filter(node => node.parentElement?.textContent?.includes('Version'));
    if (controls.length !== 1) throw new Error('Unique viewer version'); return controls[0];
  });
  try { await viewerVersion.asElement().asLocator().setTimeout(5000).click(); } finally { await viewerVersion.dispose(); }
  await clickText(viewerPage, 'v1.5.0 · Latest');
  await viewerPage.waitForFunction(() => document.body.textContent?.includes('Downloading: v1.5.0') &&
    [...document.querySelectorAll('p')].some(node => node.textContent === 'Edition: Survivors of the Void + Seekers of the Storm + Alloyed Collective + Hallowed Concepts'), {timeout: 5000});
  proof.ordinarySavedDetailsVisible = true;
  proof.stage = 'repeat-login'; proof.repeatPhase = 'profile-menu';
  const viewerProfile = await viewerPage.evaluateHandle(() => {
    const buttons = [...document.querySelectorAll('nav div[data-slot="trigger"][aria-haspopup="true"] button')];
    if (buttons.length !== 1) throw new Error('Unique viewer profile'); return buttons[0];
  });
  try { await viewerProfile.asElement().click(); } finally { await viewerProfile.dispose(); }
  await viewerPage.waitForFunction(() => [...document.querySelectorAll('[role="menuitem"]')].some(item => item.textContent?.trim() === 'Sign Out'), {timeout: 5000});
  const loggedOut = viewerPage.waitForResponse((response: any) => {
    const url = new URL(response.url());
    // Native no-options Hilla logout submits a document form. With OIDC disabled,
    // Vaadin's default logout success handler redirects (not the OIDC-only200 branch).
    return url.origin === input.origin && url.pathname === '/logout' && response.request().method() === 'POST' &&
      response.request().isNavigationRequest() && response.status() === 302;
  }, {timeout: 10000});
  void loggedOut.catch(() => {});
  proof.repeatPhase = 'sign-out';
  const signOut = await viewerPage.evaluateHandle(() => {
    const items = [...document.querySelectorAll('[role="menuitem"]')].filter(item => item.textContent?.trim() === 'Sign Out');
    if (items.length !== 1) throw new Error('Unique Sign Out'); return items[0];
  });
  try { await signOut.asElement().click(); } finally { await signOut.dispose(); }
  await loggedOut; proof.viewerLogoutObserved = true;
  // Public browsing is disabled in this synthetic fixture. Logout's root redirect
  // must automatically reach the private login form, outside MainLayout/navbar.
  // This does not test the separate public-mode navbar Sign In action.
  proof.repeatPhase = 'logged-out-form';
  await viewerPage.waitForFunction(() => location.pathname === '/login' &&
    !!document.querySelector('input[name="username"]') && !!document.querySelector('input[name="password"]') &&
    document.querySelectorAll('nav div[data-slot="trigger"][aria-haspopup="true"] button').length === 0, {timeout: 10000});
  proof.loggedOutLoginFormObserved = true; proof.repeatPhase = 'normal-form';
  await login(viewerPage, input, 'fixture-user', input.viewerPassword, true);
  proof.repeatedViewerIdentityVerified = true; proof.repeatPhase = 'complete';
  if (failed) throw new Error('Repeat UI gate');
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
