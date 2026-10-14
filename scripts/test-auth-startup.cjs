// Run after frontend dependency preparation: node --test scripts/test-auth-startup.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const appRequire = createRequire(path.resolve(__dirname, '../app/package.json'));
const ts = process.env.TEST_TYPESCRIPT_MODULE ? require(process.env.TEST_TYPESCRIPT_MODULE) : appRequire('typescript');
const frontend = path.resolve(__dirname, '../app/src/main/frontend');
function load(file, mocks = {}, suffix = '') {
    const source = fs.readFileSync(path.join(frontend, file), 'utf8') + suffix;
    const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX}});
    const module = {exports: {}};
    vm.runInNewContext(compiled.outputText, {module, exports: module.exports, AbortController, console,
        require(name) {
            if (name === 'Frontend/util/startupLifecycle') return lifecycle;
            if (name === 'valtio' || name === 'valtio/index') return {proxy: value => value};
            if (name in mocks) return mocks[name];
            return {};
        }});
    return module.exports;
}
const lifecycle = load('util/startupLifecycle.ts');
function deferred() { let resolve; const promise = new Promise(r => {resolve = r;}); return {promise, resolve}; }
const cases = ['Library', 'Collection', 'Platform', 'GameRequest', 'Plugin', 'Game', 'DownloadSession', 'User'];
for (const name of cases) {
    if (name !== 'User') test(`${name}: non-aborted request failure remains visible to caller`, async () => {
        const failure = new Error('Fixed test request failure');
        const endpoint = new Proxy({}, {get: () => async () => {throw failure;}});
        const mod = load(`state/${name}State.ts`, {'Frontend/generated/endpoints': new Proxy({}, {get: () => endpoint})});
        await assert.rejects(mod[`initialize${name}State`](new AbortController().signal), error => error === failure);
    });
    test(`${name}: forwards same signal to RPC and suppresses only lifecycle-aborted rejection`, async () => {
        let received;
        const endpoint = new Proxy({}, {get: () => init => new Promise((_, reject) => {
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
        let callback;
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
    let callback; let cancelled = 0; let entries = [{id: 1}];
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
test('actual App effect skips forms, retains public browse lifecycle across navigation, and aborts on identity change', () => {
    let pathname = '/login', auth = {state: {initializing: false, loading: false, user: undefined}};
    let previous, cleanup, pending; const calls = [];
    const mocks = {
        'react': {useEffect(fn, deps) {if (!previous || deps.some((d, i) => d !== previous[i])) {pending = fn; previous = deps;}}},
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
        const values = []; let hook = 0, effectDone = false, effect, button, submit, logins = 0;
        const jsx = (type, props) => {
            if (type === 'Formik') {submit = props.onSubmit; return props.children({isSubmitting: false});}
            if (type === 'Button' && props.type === 'submit') button = props;
            return null;
        };
        const mod = load('views/LoginView.tsx', {
            'react': {useState(initial) {const index = hook++; if (!(index in values)) values[index] = initial; return [values[index], value => {values[index] = value;}];},
                useEffect(fn) {if (!effectDone) effect = fn;}},
            'react/jsx-runtime': {jsx, jsxs: jsx},
            'Frontend/util/auth': {useAuth: () => ({state: {initializing: false, loading: false}, login: async () => {logins++; return {error: true};}})},
            '@heroui/react': {Button: 'Button', useDisclosure: () => ({})},
            'formik': {Formik: 'Formik'},
            'Frontend/generated/endpoints': {RegistrationEndpoint: {isSelfRegistrationAllowed: () => policy}}
        });
        function render() {hook = 0; mod.default(); if (!effectDone) {effectDone = true; effect();}}
        const formik = {setFieldError() {}};
        render(); assert.equal(button.isDisabled, true); await submit({}, formik); assert.equal(logins, 0);
        if (failed) reject(new Error('Unavailable registration policy')); else finish(false);
        await new Promise(resolve => setImmediate(resolve));
        render(); assert.equal(button.isDisabled, false); await submit({}, formik); assert.equal(logins, 1);
    }
});
