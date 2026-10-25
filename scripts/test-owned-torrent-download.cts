// Uses the actual application module, transpiled in memory with the installed compiler. No network/browser.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const appRequire = createRequire(path.resolve(__dirname, '../app/package.json'));
const ts = appRequire('typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../app/src/main/frontend/endpoints/DownloadEndpoint.ts'), 'utf8');
function deferred() {
    let resolve!: (value: any) => void;
    const promise = new Promise((accept) => { resolve = accept; });
    return {promise, resolve};
}
const ready = {id: '00000000-0000-0000-0000-000000000001', state: 'READY',
    downloadUrl: '/owned-torrent/00000000-0000-0000-0000-000000000001/metadata'};
function load(create: () => Promise<any>, status: () => Promise<any> = async () => ready) {
    const navigation: string[] = [], alerts: string[] = [];
    const module = {exports: {} as any};
    const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}});
    vm.runInNewContext(compiled.outputText, {module, exports: module.exports, URLSearchParams, performance,
        require: (name: string) => {
            assert.equal(name, 'Frontend/generated/endpoints');
            return {OwnedTorrentEndpoint: {create, status}};
        },
        window: {setTimeout: (callback: () => void) => { queueMicrotask(callback); return 1; },
            location: {assign: (url: string) => navigation.push(url)}, open: (url: string) => navigation.push(url),
            alert: (message: string) => alerts.push(message)},
    });
    return {download: module.exports.downloadGame, navigation, alerts};
}
const flush = () => new Promise((resolve) => setImmediate(resolve));

test('current completed selection navigates once', async () => {
    const fixture = load(async () => ready);
    fixture.download(1, 'owned-snapshot-qb', 2, [3], new AbortController().signal);
    await flush(); assert.deepEqual(fixture.navigation, [ready.downloadUrl]); assert.equal(fixture.alerts.length, 0);
});
test('unmounted or replaced actor cannot navigate after pending create', async () => {
    const pending = deferred(), owner = new AbortController();
    const fixture = load(() => pending.promise);
    fixture.download(1, 'owned-snapshot-qb', 2, [3], owner.signal);
    owner.abort(); pending.resolve(ready); await flush();
    assert.equal(fixture.navigation.length, 0); assert.equal(fixture.alerts.length, 0);
});
test('newer click supersedes older pending create without cancelling remote ownership', async () => {
    const first = deferred(); let calls = 0;
    const fixture = load(() => ++calls === 1 ? first.promise : Promise.resolve(ready));
    fixture.download(1, 'owned-snapshot-qb', 2, [3]);
    fixture.download(1, 'owned-snapshot-qb', 2, [4]);
    await flush(); first.resolve(ready); await flush();
    assert.deepEqual(fixture.navigation, [ready.downloadUrl]); assert.equal(calls, 2);
});
test('selection invalidation during status await suppresses late navigation', async () => {
    const pending = deferred(), owner = new AbortController(); let polls = 0;
    const fixture = load(async () => ({...ready, state: 'RUNNING', downloadUrl: undefined}), () => { polls++; return pending.promise; });
    fixture.download(1, 'owned-snapshot-qb', 2, [3], owner.signal);
    await flush(); assert.equal(polls, 1);
    owner.abort(); pending.resolve(ready); await flush();
    assert.equal(fixture.navigation.length, 0); assert.equal(fixture.alerts.length, 0);
});
test('normal provider download supersedes pending owned navigation before unload', async () => {
    const pending = deferred();
    const fixture = load(() => pending.promise);
    fixture.download(1, 'owned-snapshot-qb', 2, [3]);
    fixture.download(1, 'normal-provider', 2, [3]);
    pending.resolve(ready); await flush();
    assert.equal(fixture.navigation.length, 1);
    assert.ok(fixture.navigation[0].startsWith('/download/1?provider=normal-provider'));
    assert.equal(fixture.alerts.length, 0);
});
