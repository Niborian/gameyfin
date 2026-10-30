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
function load<T extends object = VmExports>(file: string, mocks: ModuleMocks = {}, suffix: string = ''): T {
    const source = fs.readFileSync(path.join(frontend, file), 'utf8') + suffix;
    const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX}});
    const module = {exports: {}};
    vm.runInNewContext(compiled.outputText, {module, exports: module.exports, AbortController, console,
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
