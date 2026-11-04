// Node24 erasable types; after dependency preparation: node --test scripts/test-auth-startup.cts
type Deferred<T = unknown> = {promise: Promise<T>; resolve: (value: T) => void};
type StateName = 'Library' | 'Collection' | 'Platform' | 'GameRequest' | 'Plugin' | 'Game' | 'DownloadSession' | 'User';
type EventCallback = (payload: unknown) => void;
type RpcInit = {signal: AbortSignal};
type InitializerCall = {name: StateName | 'Scan'; signal: AbortSignal};
type ModuleMocks = Record<string, unknown>;
// Evaluated application modules are deliberately an untyped VM boundary; fixture
// ownership, signals and completion capabilities remain typed outside that boundary.
type VmExports = Record<string, any>;
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const appRequire = createRequire(path.resolve(__dirname, '../app/package.json'));
const ts = process.env.TEST_TYPESCRIPT_MODULE ? require(process.env.TEST_TYPESCRIPT_MODULE) : appRequire('typescript');
const frontend = path.resolve(__dirname, '../app/src/main/frontend');
function load<T extends object = VmExports>(file: string, mocks: ModuleMocks = {}, suffix: string = '', globals: ModuleMocks = {}): T {
    const source = fs.readFileSync(path.join(frontend, file), 'utf8') + suffix;
    const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX}});
    const module = {exports: {}};
    vm.runInNewContext(compiled.outputText, {module, exports: module.exports, AbortController, console, ...globals,
        require(name: string) {
            if (name === 'Frontend/util/startupLifecycle') return lifecycle;
            if (name === 'valtio' || name === 'valtio/index') return {proxy: value => value};
            if (name in mocks) return mocks[name];
            return {};
        }});
    return module.exports as T;
}
const lifecycle = load('util/startupLifecycle.ts');
function deferred<T = unknown>(): Deferred<T> { let resolve!: (value: T) => void; const promise = new Promise<T>(r => {resolve = r;}); return {promise, resolve}; }
const cases: StateName[] = ['Library', 'Collection', 'Platform', 'GameRequest', 'Plugin', 'Game', 'DownloadSession', 'User'];
for (const name of ['Library', 'Config'] as const) test(`actual Hilla close and reopen refreshes the ${name} snapshot instead of retaining a dead subscription`, async () => {
    let transport: any; const sent: any[] = [];
    const fluxSource = fs.readFileSync(path.join(path.dirname(appRequire.resolve('@vaadin/hilla-frontend')), 'FluxConnection.js'), 'utf8');
    const compiled = ts.transpileModule(fluxSource, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}});
    const fluxModule = {exports: {} as any};
    vm.runInNewContext(compiled.outputText, {module: fluxModule, exports: fluxModule.exports, EventTarget, CustomEvent, console,
        require(name: string) {
            if (name === './utils.js') return {VAADIN_BROWSER_ENVIRONMENT: true};
            if (name === './CsrfInfoSource.js') return {default: {get: async () => ({headerEntries: []})}};
            if (name === './FluxMessages.js') return {isClientMessage: value => typeof value?.id === 'string' && value['@type'] === 'update'};
            if (name === 'atmosphere.js') return {default: {subscribe: options => {transport = options; return {push: value => sent.push(JSON.parse(value))};}}};
            throw new Error(`Unexpected Hilla test dependency: ${name}`);
        }});
    const connection = new fluxModule.exports.FluxConnection('/connect');
    await connection.ready; transport.onOpen();
    let requests = 0, rows: any[] = name === 'Config' ? [{key: 'synthetic.value', value: 1}] : [{id: 1}];
    const eventMethod = name === 'Config' ? 'subscribe' : 'subscribeToLibraryEvents';
    const endpoint = {getAll: async () => {requests++; return rows;},
        [eventMethod]: () => connection.subscribe(`${name}Endpoint`, eventMethod, {})};
    const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': {[`${name}Endpoint`]: endpoint}});
    const initialize = mod[`initialize${name}State`];
    const state = mod[name === 'Config' ? 'configState' : 'libraryState'];
    const controller = new AbortController();
    await initialize(controller.signal);
    const original = sent.find(message => message['@type'] === 'subscribe').id;
    rows = name === 'Config' ? [{key: 'synthetic.refreshed', value: 2}] : [{id: 2}]; // Represents changes while this client was disconnected.
    transport.onClose(); transport.onReopen();
    assert.equal(requests, 2, 'Hilla reopen itself must start recovery without another page initializer');
    await initialize(controller.signal);
    assert.equal(requests, 2, 'A reopened transport must not leave a stale loaded cache');
    assert.deepEqual(Object.keys(state.state), [name === 'Config' ? 'synthetic.refreshed' : '2']);
    const subscriptions = sent.filter(message => message['@type'] === 'subscribe');
    assert.equal(subscriptions.length, 2); assert.notEqual(subscriptions[1].id, original);
    transport.onMessage({responseBody: JSON.stringify({'@type': 'update', id: subscriptions[1].id,
        item: name === 'Config' ? [{updates: {'synthetic.refreshed': 3}}] : [{type: 'updated', library: {id: 2, refreshed: true}}]})});
    assert.equal(name === 'Config' ? state.state['synthetic.refreshed'].value : state.state[2].refreshed, name === 'Config' ? 3 : true);
    controller.abort();
});
test('Config: cancellation during lost-stream snapshot cannot revive a retired page', async () => {
    const pending = deferred(); let requests = 0, attached = 0, cancelled = 0, lost: any, queued: any, received: AbortSignal | undefined;
    const endpoint = {
        getAll: (init?: RpcInit) => { received = init?.signal; return ++requests === 1 ? Promise.resolve([{key: 'synthetic.value', value: 1}]) : pending.promise; },
        subscribe: () => ({onNext(fn: EventCallback) { attached++; queued = fn; return {cancel() {cancelled++;}, onSubscriptionLost(fn: any) {lost = fn;}}; }})
    };
    const mod = load('state/ConfigState.ts', {'Frontend/generated/endpoints': {ConfigEndpoint: endpoint}});
    const owner = new AbortController(); await mod.initializeConfigState(owner.signal);
    assert.equal(received, owner.signal); assert.equal(typeof lost, 'function');
    const oldLost = lost; assert.equal(oldLost(), 'remove');
    const refresh = mod.initializeConfigState(owner.signal);
    assert.equal(requests, 2); assert.equal(received, owner.signal); assert.equal(cancelled, 1);
    owner.abort(); queued({forEach() {throw new Error('Retired config callback processed payload');}});
    pending.resolve([{key: 'synthetic.stale', value: 99}]); await refresh;
    oldLost(); assert.equal(requests, 2); assert.equal(attached, 1);
    assert.equal(mod.configState.subscription, undefined); assert.deepEqual(Object.keys(mod.configState.state), []);
});

test('Config page owns initialization and shows only generic current failures', async () => {
    for (const aborted of [false, true]) {
        let effect: any, signal: AbortSignal | undefined, hook = 0; const values: any[] = []; const retained = {current: undefined};
        const pending = deferred(); const jsx = (type: unknown, props: any) => ({type, props});
        const state = {isLoaded: false, state: {}, config: {}};
        const mod = load('components/administration/withConfigPage.tsx', {
            react: {useRef: () => retained, useState: (initial: any) => {const index = hook++; if (!(index in values)) values[index] = initial; return [values[index], (value: any) => {values[index] = value;}];}, useEffect: (fn: any) => {effect ??= fn;}},
            'react/jsx-runtime': {jsx, jsxs: jsx}, 'valtio/react': {useSnapshot: () => state},
            'Frontend/state/ConfigState': {configState: state, initializeConfigState: (value: AbortSignal) => {signal = value; return pending.promise;}}
        });
        const Page = mod.default(() => undefined, 'Settings'); Page({}); const cleanup = effect();
        assert.ok(signal); if (aborted) cleanup();
        pending.resolve(Promise.reject(new Error('private synthetic details'))); await assert.rejects(pending.promise); await Promise.resolve();
        hook = 0; const rendered = Page({});
        assert.equal(rendered.props.children.some((child: any) => child?.props?.role === 'alert'), !aborted);
        assert.ok(!JSON.stringify(rendered).includes('private synthetic details'));
        cleanup(); assert.equal(signal!.aborted, true);
    }
});

test('Config page keeps the same form and snapshot during reconnect but cannot save stale settings', async () => {
    const snapshot = {'synthetic.value': 1};
    const entry = {key: 'synthetic.value', value: 1, description: 'Synthetic setting', type: 'integer'};
    const state = {isLoaded: true, state: {'synthetic.value': entry}, config: snapshot};
    const retained = {current: undefined}; let updates = 0;
    const Formik = () => undefined, Button = () => undefined;
    const jsx = (type: unknown, props: any) => ({type, props});
    const mod = load('components/administration/withConfigPage.tsx', {
        react: {useRef: () => retained, useState: (initial: any) => [initial, () => {}], useEffect: () => {}},
        'react/jsx-runtime': {jsx, jsxs: jsx}, 'valtio/react': {useSnapshot: () => state},
        formik: {Formik, Form: () => undefined}, '@heroui/react': {Button},
        'Frontend/state/ConfigState': {configState: state},
        'Frontend/generated/endpoints': {ConfigEndpoint: {update: async () => {updates++;}}}
    });
    const Page = mod.default(() => undefined, 'Settings');
    const initial = Page({}); const slot = initial.props.children.findIndex((child: any) => child?.type === Formik);
    const first = initial.props.children[slot]; assert.ok(first); assert.equal(first.props.enableReinitialize, true);
    state.isLoaded = false; state.config = {}; state.state = {} as typeof state.state;
    const during = Page({}).props.children[slot];
    assert.equal(during.type, first.type); assert.equal(during.props.initialValues, snapshot);
    const fields = during.props.children({isSubmitting: false, dirty: true});
    const wrapped = fields.props.children[1];
    assert.equal(wrapped.props.getConfig('synthetic.value'), entry, 'Mounted fields retain their description/type metadata while global cache is empty');
    const save = fields.props.children[0].props.children[1].props.children[1];
    assert.equal(save.type, Button); assert.equal(save.props.isDisabled, true);
    await during.props.onSubmit({'synthetic.value': 2}); assert.equal(updates, 0);
    state.isLoaded = true; state.config = {'synthetic.value': 1}; state.state = {'synthetic.value': entry};
    const recovered = Page({}).props.children[slot];
    assert.equal(recovered.type, first.type); assert.deepEqual(recovered.props.initialValues, snapshot);
    state.config = {'synthetic.value': 3};
    assert.deepEqual(Page({}).props.children[slot].props.initialValues, {'synthetic.value': 3});
});

test('lost subscription refresh is owned, single-shot and cannot attach after auth cancellation', async () => {
    const pending = deferred(); let requests = 0, cancelled = 0;
    const callbacks: EventCallback[] = [], lost: Array<() => string> = [];
    const endpoint = {
        getAll: () => ++requests === 1 ? Promise.resolve([{id: 1}]) : pending.promise,
        subscribeToLibraryEvents: () => ({onNext(fn) {
            callbacks.push(fn);
            return {cancel() {cancelled++;}, onSubscriptionLost(fn) {lost.push(fn);}};
        }})
    };
    const mod = load('state/LibraryState.ts', {'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}});
    const controller = new AbortController(); await mod.initializeLibraryState(controller.signal);
    assert.equal(lost[0](), 'remove'); assert.equal(requests, 2); assert.equal(cancelled, 1);
    const refresh = mod.initializeLibraryState(controller.signal);
    assert.equal(lost[0](), 'remove'); assert.equal(requests, 2);
    callbacks[0]({forEach() {throw new Error('Retired subscription processed payload');}});
    controller.abort(); pending.resolve([{id: 99}]); await refresh;
    assert.equal(callbacks.length, 1); assert.equal(Object.keys(mod.libraryState.state).length, 0);
    assert.equal(mod.libraryState.subscription, undefined);
});
test('scan startup waits for library readiness before consuming the one-time scan snapshot', async () => {
    const ready = deferred(); let attached = 0, cancelled = 0, requests = 0;
    const progress = {scanId: 'active', libraryId: 52, status: 'IN_PROGRESS'};
    const endpoint = {
        getAll: () => {requests++; return ready.promise;},
        subscribeToLibraryEvents: () => ({onNext() {return {cancel() {}};}}),
        subscribeToScanProgressEvents: () => ({onNext(fn) {
            attached++; fn([progress]); return {cancel() {cancelled++;}};
        }})
    };
    const libraries = load('state/LibraryState.ts', {'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}});
    const mod = load('state/ScanState.ts', {
        'Frontend/state/LibraryState': libraries,
        'Frontend/generated/org/gameyfin/app/libraries/dto/LibraryScanStatus': {default: {IN_PROGRESS: 'IN_PROGRESS'}},
        'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}
    });
    const controller = new AbortController();
    const libraryLoad = libraries.initializeLibraryState(controller.signal);
    const first = mod.initializeScanState(controller.signal);
    const duplicate = mod.initializeScanState(controller.signal);
    assert.equal(attached, 0, 'Replay must not be discarded against an empty pending library cache');
    assert.equal(requests, 1, 'Scan initialization must share the original library RPC');
    ready.resolve([{id: 52}]);
    await libraryLoad; await first; await duplicate;
    assert.equal(attached, 1); assert.equal(mod.scanState.state.active, progress);
    controller.abort(); assert.equal(cancelled, 1); assert.equal(Object.keys(mod.scanState.state).length, 0);
});
test('scan startup cannot attach after cancellation or accept a replaced owner callback', async () => {
    const ready = deferred(); const callbacks: EventCallback[] = []; let cancelled = 0;
    const libraries = {state: {52: {id: 52}}, isLoaded: true};
    const mod = load('state/ScanState.ts', {
        'Frontend/state/LibraryState': {libraryState: libraries, initializeLibraryState: () => ready.promise},
        'Frontend/generated/endpoints': {LibraryEndpoint: {subscribeToScanProgressEvents: () => ({onNext(fn) {
            callbacks.push(fn); return {cancel() {cancelled++;}};
        }})}}
    });
    const aborted = new AbortController(); const pending = mod.initializeScanState(aborted.signal); aborted.abort();
    ready.resolve(); await pending; assert.equal(callbacks.length, 0);
    const old = new AbortController(), current = new AbortController();
    await mod.initializeScanState(old.signal); await mod.initializeScanState(current.signal);
    assert.equal(callbacks.length, 2); assert.equal(cancelled, 1);
    callbacks[0]({forEach() {throw new Error('Replaced scan generation processed payload');}});
    old.abort(); assert.equal(cancelled, 1);
    callbacks[1]([{scanId: 'new', libraryId: 52}]); assert.equal(Object.keys(mod.scanState.state).join(','), 'new');
    current.abort(); assert.equal(cancelled, 2);
});
for (const name of cases) {
    test(`${name}: failed current fetch clears ownership and same owner can retry`, async () => {
        let requests = 0, attached = 0, cancelled = 0;
        const failure = new Error('Fixed request failure');
        const initial = name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : [];
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext() {attached++; return {cancel() {cancelled++;}};}})
            : async () => {if (++requests === 1) throw failure; return initial;}});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const owner = new AbortController();
        if (name === 'User') await mod.initializeUserState(owner.signal);
        else await assert.rejects(mod[`initialize${name}State`](owner.signal), error => error === failure);
        await mod[`initialize${name}State`](owner.signal);
        assert.equal(requests, 2); assert.equal(attached, name === 'User' ? 0 : 1);
        owner.abort(); assert.equal(cancelled, attached);
    });
    test(`${name}: replacement abort before either fetch settles cannot resurrect old owner`, async () => {
        const pending = [deferred(), deferred()]; let requests = 0, attached = 0;
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext() {attached++; return {cancel() {}};}}) : () => pending[requests++].promise});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const old = new AbortController(), current = new AbortController();
        const first = mod[`initialize${name}State`](old.signal), second = mod[`initialize${name}State`](current.signal);
        current.abort();
        const initial = name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : [];
        pending[1].resolve(initial); await second;
        pending[0].resolve(name === 'User' ? [{id: 99}] : initial); await first;
        assert.equal(attached, 0); assert.equal(old.signal.aborted, false);
        const state = mod[name[0].toLowerCase() + name.slice(1) + 'State'];
        if (state.state) assert.equal(Object.keys(state.state).length, 0);
        old.abort();
    });
    test(`${name}: concurrent same-owner initialization is single flight and abort leaves no orphan`, async () => {
        const pending = deferred(); let requests = 0, attached = 0, cancelled = 0;
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext() {attached++; return {cancel() {cancelled++;}};}})
            : () => {requests++; return pending.promise;}});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const owner = new AbortController();
        const first = mod[`initialize${name}State`](owner.signal), second = mod[`initialize${name}State`](owner.signal);
        pending.resolve(name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : []);
        await Promise.all([first, second]);
        assert.equal(requests, 1); assert.equal(attached, name === 'User' ? 0 : 1);
        owner.abort(); assert.equal(cancelled, attached);
    });
    test(`${name}: superseded unaborted owner cannot attach after replacement or clear it on abort`, async () => {
        const pending = [deferred(), deferred()]; let requests = 0, attached = 0, cancelled = 0, callback: EventCallback | undefined;
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext(fn) {callback = fn; attached++; return {cancel() {cancelled++;}};}})
            : () => pending[requests++].promise});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const old = new AbortController(), current = new AbortController();
        const first = mod[`initialize${name}State`](old.signal), second = mod[`initialize${name}State`](current.signal);
        const initial = name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : [];
        pending[1].resolve(name === 'User' ? [{id: 2}] : initial); await second;
        pending[0].resolve(name === 'User' ? [{id: 1}] : initial); await first;
        assert.equal(attached, name === 'User' ? 0 : 1);
        if (name === 'User') assert.deepEqual(Object.keys(mod.userState.state), ['2']);
        old.abort(); assert.equal(cancelled, 0);
        if (callback) callback([]);
        current.abort(); assert.equal(cancelled, attached);
    });
}

test('startup helper synchronous failure cleans owner before same owner retry', async () => {
    const owner = new AbortController(); let cleared = 0;
    const state = {isLoaded: false};
    await assert.rejects(lifecycle.runStartupState(state, owner.signal, () => {cleared++;}, () => {throw Error('Fixed sync failure');}), /Fixed sync failure/);
    assert.equal(cleared, 1);
    await lifecycle.runStartupState(state, owner.signal, () => {cleared++;}, async () => {state.isLoaded = true;});
    owner.abort(); assert.equal(cleared, 2);
});

test('standalone library calls remain single flight without a signal', async () => {
    let requests = 0, attached = 0;
    const endpoint = {getAll: async () => {requests++; return [];}, subscribeToLibraryEvents: () => ({onNext() {attached++; return {cancel() {}};}})};
    const mod = load('state/LibraryState.ts', {'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}});
    await Promise.all([mod.initializeLibraryState(), mod.initializeLibraryState()]);
    assert.equal(requests, 1); assert.equal(attached, 1);
});

test('replacing attached owner cancels exactly old handle and ignores its unaborted queued callback', async () => {
    const callbacks: EventCallback[] = []; let cancelled = 0;
    const endpoint = {getAll: async () => [], subscribeToLibraryEvents: () => ({onNext(fn) {callbacks.push(fn); return {cancel() {cancelled++;}};}})};
    const mod = load('state/LibraryState.ts', {'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}});
    const old = new AbortController(), current = new AbortController();
    await mod.initializeLibraryState(old.signal); await mod.initializeLibraryState(current.signal);
    assert.equal(cancelled, 1); assert.equal(old.signal.aborted, false);
    callbacks[0]({forEach() {throw Error('Superseded callback processed');}});
    const replacement = mod.libraryState.subscription;
    old.abort(); assert.equal(cancelled, 1); assert.equal(mod.libraryState.subscription, replacement);
    current.abort(); assert.equal(cancelled, 2);
});
for (const name of cases) {
    if (name !== 'User') test(`${name}: non-aborted request failure remains visible to caller`, async () => {
        const failure = new Error('Fixed test request failure');
        const endpoint = new Proxy({}, {get: () => async () => {throw failure;}});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        await assert.rejects(mod[`initialize${name}State`](new AbortController().signal), error => error === failure);
    });
    test(`${name}: forwards same signal to RPC and suppresses only lifecycle-aborted rejection`, async () => {
        let received: AbortSignal | undefined;
        const endpoint = new Proxy({}, {get: () => (init: RpcInit) => new Promise((_, reject) => {
            received = init.signal;
            init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {once: true});
        })});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const controller = new AbortController(); const result = mod[`initialize${name}State`](controller.signal);
        assert.equal(received, controller.signal); controller.abort(); await result;
    });
    test(`${name}: aborted pending initial fetch cannot populate cache or attach subscription`, async () => {
        const pending = deferred(); let attached = 0;
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext() {attached++; return {cancel() {}};}}) : () => pending.promise});
        const endpoints = new Proxy({}, {get: () => endpoint});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': endpoints});
        const controller = new AbortController();
        const result = mod[`initialize${name}State`](controller.signal);
        controller.abort();
        pending.resolve(name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : []);
        await result;
        const state = mod[name[0].toLowerCase() + name.slice(1) + 'State'];
        assert.equal(attached, 0);
        assert.equal(state.subscription, undefined);
        if (state.state) assert.equal(Object.keys(state.state).length, 0);
        if (name === 'User') assert.equal(state.isLoaded, false);
    });
}
test('public anonymous routes stay enabled; auth-only forms do not bootstrap catalogs', () => {
    for (const route of ['/login', '/login/', '/LOGIN', '/LoGiN/', '/setup', '/SETUP/', '/reset-password', '/accept-invitation', '/confirm-email']) assert.equal(lifecycle.isAuthOnlyRoute(route), true);
    for (const route of ['/', '/library/52', '/game/2055', '/search', '/settings/profile']) assert.equal(lifecycle.isAuthOnlyRoute(route), false);
    assert.equal(lifecycle.isAuthOnlyRoute('/library/52'), lifecycle.isAuthOnlyRoute('/game/2055'));
});
for (const name of cases.filter(name => name !== 'User')) {
    test(`${name}: already queued callback cannot mutate an aborted generation`, async () => {
        let callback!: EventCallback;
        const initial = name === 'Platform' ? {available: [], inUseByGames: [], inUseByLibraries: []} : [];
        const endpoint = new Proxy({}, {get: (_, method) => method.startsWith('subscribe')
            ? () => ({onNext(fn) {callback = fn; return {cancel() {}};}}) : async () => initial});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        const controller = new AbortController(); await mod[`initialize${name}State`](controller.signal); controller.abort();
        callback({forEach() {throw new Error('Stale callback processed payload');}});
    });
}
test('login waits for auth and necessary policy request settlement, never a timer', () => {
    assert.equal(lifecycle.loginIsReady(false, false, true), true);
    for (const args of [[true, false, true], [false, true, true], [false, false, false]]) assert.equal(lifecycle.loginIsReady(...args), false);
});
test('subscription cancellation clears owned cache, while old abort cannot clear newer owner', () => {
    const old = new AbortController(), current = new AbortController(); let cancelled = 0;
    const state = {subscription: {cancel() {cancelled++;}}, records: {1: true}};
    lifecycle.ownStartupState(state, old.signal, () => {state.records = {};});
    lifecycle.ownStartupState(state, current.signal, () => {state.records = {};});
    old.abort(); assert.equal(cancelled, 0); assert.equal(Object.keys(state.records).length, 1);
    current.abort(); assert.equal(cancelled, 1); assert.equal(state.subscription, undefined); assert.equal(Object.keys(state.records).length, 0);
});
test('actual library callback ignores late queued updates and next snapshot drops deleted records', async () => {
    let callback!: EventCallback; let cancelled = 0; let entries = [{id: 1}];
    const endpoint = {getAll: async () => entries, subscribeToLibraryEvents: () => ({onNext(fn) {callback = fn; return {cancel() {cancelled++;}};}})};
    const mod = load('state/LibraryState.ts', {'Frontend/generated/endpoints': {LibraryEndpoint: endpoint}});
    const old = new AbortController(); await mod.initializeLibraryState(old.signal); const late = callback;
    old.abort(); assert.equal(cancelled, 1);
    late([{type: 'created', library: {id: 99}}]); assert.equal(Object.keys(mod.libraryState.state).length, 0);
    entries = [{id: 2}]; await mod.initializeLibraryState(new AbortController().signal);
    assert.deepEqual(Object.keys(mod.libraryState.state), ['2']);
});
test('actual user cache is cleared across administrator identities', async () => {
    let users = [{id: 1}];
    const mod = load('state/UserState.ts', {'Frontend/generated/endpoints': {UserEndpoint: {getAllUsers: async () => users}}});
    const first = new AbortController(); await mod.initializeUserState(first.signal); first.abort();
    assert.equal(mod.userState.isLoaded, false); assert.equal(Object.keys(mod.userState.state).length, 0);
    users = [{id: 2}]; await mod.initializeUserState(new AbortController().signal);
    assert.deepEqual(Object.keys(mod.userState.state), ['2']);
});
test('actual App layout ownership skips forms, retains public browse lifecycle, and aborts on identity change', () => {
    let pathname = '/login', auth = {state: {initializing: false, loading: false, user: undefined}};
    let previous: readonly unknown[] | undefined, cleanup: (() => void) | undefined, pending: (() => void | (() => void)) | null; const calls: InitializerCall[] = [];
    const mocks: ModuleMocks = {
        'react': {
            useEffect() {throw Error('Startup ownership must not wait for passive cleanup');},
            useLayoutEffect(fn, deps) {if (!previous || deps.some((d, i) => d !== previous[i])) {pending = fn; previous = deps;}}
        },
        'react-router': {useLocation: () => ({pathname})},
        '@phosphor-icons/react': {IconContext: {Provider: () => null}},
        'react/jsx-runtime': {jsx: () => null, jsxs: () => null},
        'Frontend/util/auth': {useAuth: () => auth},
        'Frontend/util/utils': {isAdmin: () => auth.state.user?.admin === true}
    };
    for (const name of [...cases, 'Scan']) mocks[`Frontend/state/${name}State`] = {[`initialize${name}State`]: signal => calls.push({name, signal})};
    const app = load('App.tsx', mocks, '\nexport {ViewWithAuth};');
    function render() {pending = null; app.ViewWithAuth(); if (pending) {cleanup?.(); cleanup = pending();}}
    render(); assert.equal(calls.length, 0);
    pathname = '/library/52'; render(); assert.equal(calls.length, 6); const first = calls[0].signal;
    pathname = '/game/2055'; render(); assert.equal(calls.length, 6); assert.equal(first.aborted, false);
    auth = {state: {...auth.state, user: {id: 1, username: 'admin', admin: true}}}; render();
    assert.equal(first.aborted, true); assert.equal(calls.length, 15); const admin = calls.at(-1).signal;
    pathname = '/login'; render(); assert.equal(admin.aborted, true); assert.equal(calls.length, 15);
    auth = {state: {...auth.state, user: {id: 2, username: 'other-admin', admin: true}}}; render(); assert.equal(calls.length, 15);
    pathname = '/'; render(); assert.equal(calls.length, 24);
});
test('actual LoginView prevents fast submit until policy settles, including a failed lookup', async () => {
    for (const failed of [false, true]) {
        let finish, reject; const policy = new Promise((resolve, fail) => {finish = resolve; reject = fail;});
        const values = [], effects = [], cleanups = []; let hook = 0, effectDone = false, button, submit, logins = 0;
        const jsx = (type, props) => {
            if (type === 'Formik') {submit = props.onSubmit; return props.children({isSubmitting: false});}
            if (type === 'Button' && props.type === 'submit') button = props;
            return null;
        };
        const mod = load('views/LoginView.tsx', {
            'react': {useState(initial) {const index = hook++; if (!(index in values)) values[index] = initial; return [values[index], value => {values[index] = value;}];},
                useRef(initial) {const index = hook++; if (!(index in values)) values[index] = {current: initial}; return values[index];},
                useEffect(fn) {hook++; if (!effectDone) effects.push(fn);}},
            'react/jsx-runtime': {jsx, jsxs: jsx},
            'Frontend/util/auth': {useAuth: () => ({state: {initializing: false, loading: false}, login: async () => {logins++; return {error: true};}})},
            '@heroui/react': {Button: 'Button', useDisclosure: () => ({})},
            'formik': {Formik: 'Formik'},
            'Frontend/generated/endpoints': {RegistrationEndpoint: {isSelfRegistrationAllowed: () => policy}}
        });
        function render() {hook = 0; mod.default(); if (!effectDone) {effectDone = true; for (const effect of effects) cleanups.push(effect());}}
        const formik = {setFieldError() {}};
        render(); assert.equal(button.isDisabled, true); await submit({}, formik); assert.equal(logins, 0);
        if (failed) reject(new Error('Unavailable registration policy')); else finish(false);
        await new Promise(resolve => setImmediate(resolve));
        render(); assert.equal(button.isDisabled, false); await submit({}, formik); assert.equal(logins, 1);
        for (const cleanup of cleanups) if (typeof cleanup === 'function') cleanup();
    }
});

for (const kind of ['Library', 'Collection'] as const) {
    test(`actual ${kind} view distinguishes pending snapshots from loaded empty content`, () => {
        const container = {isLoaded:false,state:{52:{name:'Fixture catalogue'}}};
        const grouping = kind === 'Library' ? 'gamesByLibraryId' : 'gamesByCollectionId';
        const games = {isLoaded:false,[grouping]:{} as Record<number,object[]>};
        const jsx = (type: unknown, props: any) => ({type,props});
        const grid = () => undefined;
        const view = load(`views/${kind}View.tsx`, {
            'react': {useEffect:()=>{}},
            'react/jsx-runtime': {jsx,jsxs:jsx,Fragment:'fragment'},
            'valtio/react': {useSnapshot:(value:unknown)=>value},
            'react-router': {useParams:()=>({libraryId:'52',collectionId:'52'}),useNavigate:()=>()=>{}},
            [`Frontend/state/${kind}State`]: {[`${kind.toLowerCase()}State`]:container},
            'Frontend/state/GameState': {gameState:games},
            'Frontend/components/general/covers/CoverGrid': {default:grid,__esModule:true}
        }).default;
        function nodes(value:any):any[] {
            if(Array.isArray(value)) return value.flatMap(nodes);
            if(!value || typeof value!=='object') return [value];
            return [value,...nodes(value.props?.children)];
        }
        const empty = `This ${kind.toLowerCase()} is empty.`;
        for(const [containerReady,gameReady] of [[false,false],[true,false],[false,true]]) {
            container.isLoaded=containerReady; games.isLoaded=gameReady;
            const rendered=nodes(view());
            assert.ok(rendered.some(n=>n?.props?.role==='status'));
            assert.equal(rendered.includes(empty),false);
            assert.equal(rendered.some(n=>n?.type===grid),false);
        }
        container.isLoaded=true; games.isLoaded=true;
        assert.ok(nodes(view()).includes(empty));
        const selected=[{id:1,title:'Fixture game'}]; games[grouping][52]=selected;
        const rendered=nodes(view());
        assert.equal(rendered.includes(empty),false);
        assert.equal(rendered.some(n=>n?.props?.role==='status'),false);
        assert.equal(rendered.find(n=>n?.type===grid).props.games,selected);
    });
}

test('session check failure surfaces explicit recovery without changing Hilla authentication outcomes', async () => {
    for(const outcome of ['network','unauthorized','success','anonymous','unmounted','superseded'] as const) {
        class UnauthorizedResponseError extends Error {}
        const failure=outcome==='unauthorized'?new UnauthorizedResponseError():new TypeError('private synthetic details must not appear');
        const result={id:42,roles:[]}; const request=deferred<any>();
        let failed=false,check:any,cleanup:any,reloads=0,updates=0,calls=0,cached:any;
        const owner={current:{active:true,request:0}};
        const provider=()=>undefined,hook=()=>undefined;
        const jsx=(type:unknown,props:any)=>({type,props});
        const exports=load('util/SessionAuthProvider.tsx',{
            'react':{useState:()=>[failed,(v:boolean)=>{failed=v;updates++;}],useRef:()=>owner,useLayoutEffect:(fn:()=>any)=>{cleanup=fn();},useMemo:(fn:()=>unknown)=>cached??=(fn())},
            'react/jsx-runtime':{jsx,jsxs:jsx},
            '@vaadin/hilla-react-auth':{configureAuth:(getUser:any)=>{check=getUser;return{AuthProvider:provider,useAuth:hook};}},
            '@vaadin/hilla-frontend':{UnauthorizedResponseError},
            'Frontend/generated/endpoints':{UserEndpoint:{getUserInfo:()=>++calls===1?request.promise:Promise.resolve(result)}}
        }, '', {window:{location:{reload:()=>{reloads++;}}}});
        const first=exports.AuthProvider({children:'protected content'});
        assert.equal(first.type,provider);assert.equal(exports.useAuth,hook);
        const pending=check();
        if(outcome==='unmounted')cleanup();
        if(outcome==='superseded')assert.equal(await check(),result);
        if(outcome==='success'||outcome==='anonymous')request.resolve(outcome==='success'?result:undefined);
        else request.promise.catch(()=>{}), request.resolve(Promise.reject(failure));
        if(outcome==='success')assert.equal(await pending,result);
        else if(outcome==='anonymous')assert.equal(await pending,undefined);
        else await assert.rejects(pending,error=>error===failure);
        const rendered=exports.AuthProvider({children:'protected content'});
        if(outcome==='network') {
            assert.equal(rendered.props.role,'alert');assert.equal(updates,1);
            const text=JSON.stringify(rendered);assert.ok(text.includes('Reload Gameyfin'));assert.ok(!text.includes('private synthetic details'));assert.ok(!text.includes('protected content'));
            const button=rendered.props.children.find(n=>n?.type==='button');assert.equal(reloads,0);button.props.onClick();assert.equal(reloads,1);
        } else {assert.equal(rendered.type,provider);assert.equal(updates,0);}

    }
});

test('version interaction waits for an unobstructed trigger and its own exact visible option', () => {
    const source=fs.readFileSync(path.resolve(__dirname,'original-editor-image.cts'),'utf8');
    const fragment=source.slice(source.indexOf('function versionControlReadiness('),source.indexOf('/** Failure-only discriminator'));
    const compiled=ts.transpileModule(fragment+'\nmodule.exports=versionControlReadiness;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
    function node(role='',text='') {return {isConnected:true,hidden:false,disabled:false,textContent:text,attributes:{role} as Record<string,string>,options:[] as any[],listboxes:[] as any[],
        ancestors:new Set<string>(),rect:{x:10,y:10,width:100,height:30},
        getAttribute(key:string){return this.attributes[key]??null;},closest(selector:string){return selector.split(',').some(part=>this.ancestors.has(part)||this.hidden&&part==='[aria-hidden="true"]')?{}:null;},
        getBoundingClientRect(){return this.rect;},querySelectorAll(selector:string){return selector==='[role="listbox"]'?this.listboxes:this.options;},contains(child:any){return child===this;}};}
    const trigger=node(),controlled=node('listbox'),unrelated=node('listbox'),target=node('option','v1.5.0'),decoy=node('option','v1.5.0');
    controlled.options=[target];unrelated.options=[decoy];trigger.attributes['aria-controls']='controlled';trigger.attributes['aria-expanded']='false';
    let hit:any=unrelated,controlledRoot:any=controlled;
    const module={exports:undefined as any};
    vm.runInNewContext(compiled,{module,window:{innerWidth:1000,innerHeight:700},document:{elementFromPoint:()=>hit,
        getElementById:(id:string)=>({controlled:controlledRoot,alias:controlledRoot,unrelated})[id]??null,querySelectorAll:()=>{throw new Error('Global option lookup is not scoped');}}});
    const ready=(mode:string)=>module.exports(trigger,'v1.5.0',mode);
    assert.equal(ready('trigger'),false); // A retained popup backdrop intercepts the pointer.
    hit=trigger;assert.equal(ready('trigger'),true);
    trigger.hidden=true;assert.equal(ready('trigger'),false);trigger.hidden=false;
    trigger.disabled=true;assert.equal(ready('trigger'),false);trigger.disabled=false;
    assert.equal(ready('option'),false); // A matching unrelated option cannot open this control.
    trigger.attributes['aria-expanded']='true';controlled.hidden=true;assert.equal(ready('option'),false);controlled.hidden=false;
    target.textContent='v1.5.00';assert.equal(ready('option'),false);target.textContent='v1.5.0';
    controlled.options=[target,decoy];assert.equal(ready('option'),false);controlled.options=[target];
    assert.equal(ready('option'),target);
    const wrapper=node();wrapper.listboxes=[controlled];controlledRoot=wrapper;
    assert.equal(ready('option'),target); // Installed HeroUI wrapper ID still scopes its own inner listbox.
    trigger.attributes['aria-controls']='controlled alias';assert.equal(ready('option'),target); // Duplicate references deduplicate.
    wrapper.listboxes=[controlled,unrelated];assert.equal(ready('option'),false); // Two visible scoped listboxes are ambiguous.
    unrelated.hidden=true;assert.equal(ready('option'),target);unrelated.hidden=false;
    wrapper.listboxes=[unrelated];assert.equal(ready('option'),decoy); // A different controlled subtree changes ownership, never global lookup.
    wrapper.listboxes=[];assert.equal(ready('option'),false);
    controlledRoot=controlled;trigger.attributes['aria-controls']='controlled';
    const flags=ready('snapshot');assert.deepEqual({...flags},{observed:true,connected:true,enabled:true,inertAncestor:false,ariaHiddenAncestor:false,
        inViewport:true,centerOwn:true,centerNavbar:false,centerOverlay:false,centerSwiper:false,
        hitReady:true,expanded:true,controlledListboxVisible:true,targetOptionVisible:true});
    for(const [ancestor,key] of [['nav','centerNavbar'],['[data-slot="backdrop"]','centerOverlay'],['.swiper','centerSwiper']]) {
        hit=unrelated;unrelated.ancestors.add(ancestor);const blocked=ready('snapshot');
        assert.equal(blocked[key],true);assert.equal(blocked.centerOwn,false);assert.equal(blocked.inViewport,true);assert.equal(blocked.hitReady,false);
        unrelated.ancestors.clear();
    }
    hit=trigger;trigger.ancestors.add('[inert]');assert.equal(ready('snapshot').inertAncestor,true);assert.equal(ready('trigger'),false);trigger.ancestors.clear();
    trigger.hidden=true;assert.equal(ready('snapshot').ariaHiddenAncestor,true);trigger.hidden=false;
    trigger.rect.y=800;assert.equal(ready('snapshot').inViewport,false);assert.equal(ready('snapshot').centerOwn,false);trigger.rect.y=10;
    trigger.disabled=true;assert.equal(ready('snapshot').enabled,false);trigger.disabled=false;
    trigger.isConnected=false;assert.equal(ready('option'),false);assert.equal(ready('snapshot').connected,false);
});

test('failure-only session probe distinguishes browser input, connection and response waits', async () => {
    const source=fs.readFileSync(path.resolve(__dirname,'original-editor-image.cts'),'utf8');
    const fragment=source.slice(source.indexOf('const emptySessionProbe ='),source.indexOf('const gameDiagnostic =')) +
        source.slice(source.indexOf('async function probeCurrentSession('),source.indexOf('async function login('));
    const compiled=ts.transpileModule(fragment+'\nmodule.exports={probeCurrentSession,emptySessionProbe};', {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
    const {EventEmitter}=require('node:events');
    for(const phase of ['browser-input','connection','response','success','late-input']) {
        let requests=0,destroyed=0,agentDestroyed=0,now=0;
        const nativeHttp={Agent:class {destroy(){agentDestroyed++;}},request(_options:any,response:any){
            requests++;const request=new EventEmitter();request.destroy=()=>{destroyed++;};
            request.end=()=>queueMicrotask(()=>{
                if(phase==='connection')return;
                const socket=new EventEmitter();request.emit('socket',socket);
                now=phase==='late-input'?1880:150;socket.emit('connect');
                now=phase==='late-input'?1900:160;request.emit('finish');
                if(phase!=='success')return;
                now=180;
                const body=new EventEmitter();body.statusCode=200;response(body);
                body.emit('data',Buffer.from(JSON.stringify({username:'fixture-admin',enabled:true,id:1})));body.emit('end');
            });return request;
        }};
        const module={exports:undefined as any};
        vm.runInNewContext(compiled,{module,nativeHttp,URL,Buffer,performance:{now:()=>now},
            // A deterministic probe clock models input overhead without any
            // application sleep, timeout extension or real socket/server call.
            setTimeout:(fn:()=>void,ms:number)=>{assert.equal(ms,2000);return setTimeout(()=>{now=2000;fn();},20);},clearTimeout});
        const empty=module.exports.emptySessionProbe();
        const times=['inputsElapsedMs','socketConnectedElapsedMs','requestFlushedElapsedMs','responseHeadersElapsedMs','totalElapsedMs'];
        assert.equal(empty.kind,'not-run');assert.ok(times.every(key=>empty[key]===null));
        const page={cookies:()=>phase==='browser-input'?new Promise(()=>{}):Promise.resolve().then(()=>{now=phase==='late-input'?1750:100;return [];}),evaluate:()=>Promise.resolve('synthetic-csrf')};
        const result=await module.exports.probeCurrentSession(page,{origin:'http://172.20.0.2:8080',username:'fixture-admin'});
        assert.equal(result.inputsReady,phase!=='browser-input');
        assert.equal(result.socketConnected,['response','success','late-input'].includes(phase));
        assert.equal(result.requestFlushed,['response','success','late-input'].includes(phase));
        assert.equal(result.kind,phase==='success'?'completed':'timeout');
        assert.equal(result.currentIdentityMatches,phase==='success');
        assert.equal(result.status,phase==='success'?200:null);
        assert.equal(requests,phase==='browser-input'?0:1);
        assert.deepEqual(Object.keys(result).sort(),['kind','status','currentIdentityMatches','profileNull','inputsReady','socketConnected','requestFlushed',...times].sort());
        assert.ok(times.every(key=>result[key]===null||Number.isInteger(result[key])&&result[key]>=0&&result[key]<=2000));
        assert.equal(result.inputsElapsedMs,phase==='browser-input'?null:phase==='late-input'?1750:100);
        assert.equal(result.socketConnectedElapsedMs,result.socketConnected?(phase==='late-input'?1880:150):null);
        assert.equal(result.requestFlushedElapsedMs,result.requestFlushed?(phase==='late-input'?1900:160):null);
        assert.equal(result.responseHeadersElapsedMs,phase==='success'?180:null);
        assert.equal(result.totalElapsedMs,phase==='success'?180:2000);
        if(phase==='late-input')assert.equal(result.totalElapsedMs-result.requestFlushedElapsedMs,100); // Not a 2-second server stall.
        if(requests){assert.ok(destroyed>=1);assert.equal(agentDestroyed,1);}
        assert.ok(!JSON.stringify(result).includes('synthetic-csrf'));
    }
});

test('actual search waits for URL filters and both snapshots before reporting matches', () => {
    const state:any={isLoaded:false,sortedAlphabetically:[],knownDevelopers:[],knownGenres:[],knownThemes:[],knownFeatures:[],knownPerspectives:[],knownKeywords:[],knownVariantTags:[]};
    const libraries={isLoaded:false,libraries:[]};
    const values:any[]=[]; let cursor=0; let effects:Array<()=>void>=[];
    const jsx=(type:unknown,props:any)=>({type,props}); const grid=()=>undefined;
    const params={get:(key:string)=>key==='filters'?'1':null,getAll:(key:string)=>key==='lib'?['7']:[]};
    const view=load('views/SearchView.tsx',{
        'react':{useState:(initial:any)=>{const i=cursor++;if(!(i in values))values[i]=initial;return[values[i],(v:any)=>{values[i]=v;}];},useMemo:(fn:()=>unknown)=>fn(),useEffect:(fn:()=>void)=>effects.push(fn)},
        'react/jsx-runtime':{jsx,jsxs:jsx,Fragment:'fragment'},
        'valtio/react':{useSnapshot:(v:unknown)=>v},
        'react-router':{useSearchParams:()=>[params,()=>{}]},
        'Frontend/state/GameState':{gameState:state},
        'Frontend/state/LibraryState':{libraryState:libraries},
        'Frontend/components/general/covers/CoverGrid':{default:grid,__esModule:true},
        'Frontend/util/utils':{compoundRating:()=>5}
    },'',{window:{scrollTo:()=>{}}}).default;
    function nodes(v:any):any[]{return Array.isArray(v)?v.flatMap(nodes):v&&typeof v==='object'?[v,...nodes(v.props?.children)]:[v];}
    function render(){cursor=0;effects=[];return nodes(view());}
    function pending(rendered:any[]){assert.ok(rendered.some(n=>n?.props?.role==='status'));assert.equal(rendered.some(n=>n?.type===grid),false);assert.equal(rendered.some(n=>typeof n==='string'&&n.includes('No games found')),false);}
    pending(render()); const applyUrl=effects[0];
    state.isLoaded=true;libraries.isLoaded=true;
    pending(render()); // Even loaded snapshots must wait for the URL filter effect.
    applyUrl();
    for(const [g,l] of [[false,true],[true,false]]){state.isLoaded=g;libraries.isLoaded=l;pending(render());}
    state.isLoaded=true;libraries.isLoaded=true;
    const matching={id:1,title:'A',libraryId:7}; const other={id:2,title:'B',libraryId:8};
    state.sortedAlphabetically=[matching,other];
    let rendered=render();
    assert.deepEqual(Array.from(rendered.find(n=>n?.type===grid).props.games),( [matching] ));
    assert.equal(rendered.some(n=>typeof n==='string'&&n.includes('No games found')),false);
    state.sortedAlphabetically=[other];rendered=render();
    assert.ok(rendered.some(n=>typeof n==='string'&&n.includes('No games found')));
});

test('closed password reset has no startup RPC and reopening cannot reuse a stale policy', async () => {
    const requests:Array<{result:Deferred<boolean>;signal?:AbortSignal}>=[];
    const values:any[]=[];let cursor=0,previous:any[]|undefined,pending:any,cleanup:any,open=false;
    const jsx=(type:any,props:any)=>type==='ModalContent'?props.children(()=>{}):({type,props});
    const view=load('components/general/modals/PasswordResetModal.tsx',{
        'react':{useState:(initial:any)=>{const i=cursor++;if(!(i in values))values[i]=initial;return[values[i],(next:any)=>{values[i]=next;}];},
            useEffect:(fn:any,deps:any[])=>{if(!previous||deps.some((v,i)=>v!==previous![i])){pending=fn;previous=deps;}}},
        'react/jsx-runtime':{jsx,jsxs:jsx,Fragment:'fragment'},
        '@heroui/react':{Button:'Button',Modal:'Modal',ModalContent:'ModalContent'},
        '@heroui/input':{Input:'Input'},
        'Frontend/generated/endpoints':{MessageEndpoint:{isEnabled:(init?:{signal:AbortSignal})=>{const result=deferred<boolean>();requests.push({result,signal:init?.signal});return result.promise;}}}
    }).default;
    function nodes(v:any):any[]{return Array.isArray(v)?v.flatMap(nodes):v&&typeof v==='object'?[v,...nodes(v.props?.children)]:[v];}
    function render(){cursor=0;pending=undefined;const tree=nodes(view({isOpen:open,onOpenChange:()=>{}}));if(pending){cleanup?.();cleanup=pending();}return tree;}
    function send(tree:any[]){return tree.find(n=>n?.type==='Button'&&n.props.children==='Send request').props;}
    render();assert.equal(requests.length,0);
    open=true;assert.equal(send(render()).isDisabled,true);assert.equal(requests.length,1);
    open=false;render();assert.equal(requests[0].signal?.aborted,true);
    requests[0].result.resolve(true);await new Promise(resolve=>setImmediate(resolve));
    open=true;assert.equal(send(render()).isDisabled,true);assert.equal(requests.length,2);
    requests[1].result.resolve(false);await new Promise(resolve=>setImmediate(resolve));
    let tree=render();assert.equal(send(tree).isDisabled,true);assert.ok(tree.some(n=>typeof n==='string'&&n.includes('Password self-service is disabled')));
    open=false;render();open=true;render();requests[2].result.resolve(Promise.reject(new Error('private synthetic payload')) as any);
    await new Promise(resolve=>setImmediate(resolve));tree=render();assert.equal(send(tree).isDisabled,true);assert.ok(tree.some(n=>n?.props?.role==='alert'));assert.ok(!JSON.stringify(tree).includes('private synthetic payload'));
    open=false;render();open=true;render();requests[3].result.resolve(true);await new Promise(resolve=>setImmediate(resolve));
    tree=render();assert.equal(send(tree).isDisabled,false);assert.ok(tree.some(n=>n?.type==='Input'));
    open=false;render();assert.equal(requests[3].signal?.aborted,true);assert.equal(requests.length,4);
});
