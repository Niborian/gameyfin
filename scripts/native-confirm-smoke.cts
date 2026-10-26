// #161 browser-control gate only: no Gameyfin, network, credentials or app mutation.
// Native TypeScript stripping; Puppeteer is supplied by the exact pinned CI image.
const {createRequire} = require('node:module');
const load = createRequire('/home/pptruser/package.json');
interface NativeDialog {
  type(): string;
  message(): string;
  accept(): Promise<void>;
  dismiss(): Promise<void>;
}
interface SmokePage {
  on(event: 'dialog', listener: (dialog: NativeDialog) => void): void;
  goto(url: string, options: {timeout: number}): Promise<unknown>;
  evaluate(fn: (message: string) => boolean, message: string): Promise<boolean>;
}
interface SmokeBrowser { newPage(): Promise<SmokePage>; close(): Promise<void>; }
interface PinnedPuppeteer {
  launch(options: {headless: boolean; userDataDir: string; timeout: number; args: string[]}): Promise<SmokeBrowser>;
}
const puppeteer: PinnedPuppeteer = load('puppeteer');
const MESSAGE = 'Gameyfin isolated native confirmation control smoke';
const receipt = {
  scope: 'synthetic-blank-page-native-confirm-only',
  stage: 'admission', success: false, nativeDialogSeen: false,
  confirmationAccepted: false, originalConfirmReturnedTrue: false,
  cleanupConfirmed: false, sandboxDisabledForSyntheticOnly: true,
};
let browser: SmokeBrowser | undefined;
let dialogCount = 0;
let deadlineTimer: ReturnType<typeof setTimeout> | undefined;
async function exercise(): Promise<void> {
  if (process.getuid?.() === 0 || process.env.GAMEYFIN_NATIVE_CONFIRM_SMOKE !== 'synthetic-blank-page-only') {
    throw new Error('Admission');
  }
  receipt.stage = 'launch';
  browser = await puppeteer.launch({headless: true, userDataDir: '/tmp/native-confirm-profile', timeout: 10000,
    // No privileged capabilities: this one synthetic blank page has no network,
    // credentials, app or personal profile. Do not reuse these flags for app tests.
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-background-networking',
      '--disable-component-update', '--disable-sync', '--no-first-run']});
  const page = await browser.newPage();
  await page.goto('about:blank', {timeout: 5000});
  receipt.stage = 'native-confirm';
  let accept!: () => void;
  let reject!: (failure: Error) => void;
  const accepted = new Promise<void>((resolve, fail) => { accept = resolve; reject = fail; });
  page.on('dialog', dialog => {
    dialogCount++;
    if (dialogCount !== 1 || dialog.type() !== 'confirm' || dialog.message() !== MESSAGE) {
      void dialog.dismiss().catch(() => {});
      reject(new Error('Unexpected dialog'));
      return;
    }
    receipt.nativeDialogSeen = true;
    void dialog.accept().then(() => { receipt.confirmationAccepted = true; accept(); }, () => reject(new Error('Dialog acceptance')));
  });
  // Invoke the actual browser-native confirm. Never assign/replace window.confirm.
  const [returned] = await Promise.all([page.evaluate(message => globalThis.confirm(message), MESSAGE), accepted]);
  receipt.originalConfirmReturnedTrue = returned === true;
  if (!returned || dialogCount !== 1 || !receipt.confirmationAccepted) throw new Error('Native confirmation');
}
async function main(): Promise<void> {
  try {
    await Promise.race([exercise(), new Promise<never>((_, reject) => {
      deadlineTimer = setTimeout(() => reject(new Error('Deadline')), 30000);
    })]);
    receipt.success = true;
  } catch {
    // Fixed receipt only: never serialize Chrome errors, logs, environment or paths.
    process.exitCode = 1;
  } finally {
    if (deadlineTimer) clearTimeout(deadlineTimer);
    let cleanupTimer: ReturnType<typeof setTimeout> | undefined;
    try {
      if (browser) await Promise.race([browser.close(), new Promise<never>((_, reject) => {
        cleanupTimer = setTimeout(() => reject(new Error('Cleanup deadline')), 5000);
      })]);
      receipt.cleanupConfirmed = true;
    } catch { receipt.success = false; process.exitCode = 1; }
    finally { if (cleanupTimer) clearTimeout(cleanupTimer); }
    if (receipt.success) receipt.stage = 'complete';
    process.stdout.write(JSON.stringify(receipt) + '\n');
  }
}
void main();
