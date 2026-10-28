// Focused source-module checks: no browser, server, credentials or network.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const appRequire = createRequire(path.resolve(__dirname, '../app/package.json'));
const ts = process.env.TEST_TYPESCRIPT_MODULE ? require(process.env.TEST_TYPESCRIPT_MODULE) : appRequire('typescript');
function fixture() {
    const source = fs.readFileSync(path.resolve(__dirname, '../app/src/main/frontend/util/middleware.ts'), 'utf8');
    const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}});
    const warnings: unknown[] = [], toasts: any[] = [], module = {exports: {} as any};
    vm.runInNewContext(compiled.outputText, {module, exports: module.exports, TypeError,
        console: {warn: (...args: unknown[]) => warnings.push(args)},
        require(name: string) {return name === '@heroui/react' ? {addToast: (value: unknown) => toasts.push(value)} : {};}
    });
    return {middleware: module.exports.ErrorHandlingMiddleware, warnings, toasts};
}
const context = {endpoint: 'LibraryEndpoint', method: 'getAll', params: {private: 'PRIVATE_SENTINEL'}};
test('transport failure preserves exact error and never logs parameters message or URL', async () => {
    const f = fixture(), error = new TypeError('PRIVATE_SENTINEL https://private.invalid/?token=PRIVATE_SENTINEL');
    await assert.rejects(f.middleware(context, async () => {throw error;}), (e: unknown) => e === error);
    const logged = JSON.stringify(f.warnings);
    assert(logged.includes('LibraryEndpoint')); assert(logged.includes('transport'));
    assert(!logged.includes('PRIVATE_SENTINEL')); assert(!logged.includes('private.invalid'));
    assert.equal(f.warnings.length, 1); assert.equal(f.toasts.length, 0);
});
test('expected abort is propagated without a transport warning', async () => {
    const f = fixture(), error = new DOMException('PRIVATE_SENTINEL', 'AbortError');
    await assert.rejects(f.middleware(context, async () => {throw error;}), (e: unknown) => e === error);
    assert.equal(f.warnings.length, 0); assert.equal(f.toasts.length, 0);
});
test('HTML error retains original response and unread body instead of throwing JSON SyntaxError', async () => {
    const f = fixture(), body = '<html>PRIVATE_SENTINEL</html>';
    const response = new Response(body, {status: 502, headers: {'Content-Type': 'text/html'}});
    assert.equal(await f.middleware(context, async () => response), response);
    assert.equal(response.bodyUsed, false); assert.equal(await response.text(), body);
    assert.equal(f.toasts[0].description, 'LibraryEndpoint.getAll');
    assert(!JSON.stringify(f.warnings).includes('PRIVATE_SENTINEL'));
});
test('malformed JSON and JSON null preserve response and fall back to endpoint toast', async () => {
    for (const body of ['PRIVATE_SENTINEL', 'null', '']) {
        const f = fixture(), response = new Response(body, {status: 500, headers: {'Content-Type': 'application/json'}});
        assert.equal(await f.middleware(context, async () => response), response);
        assert.equal(response.bodyUsed, false); assert.equal(await response.text(), body);
        assert.equal(f.toasts[0].description, 'LibraryEndpoint.getAll');
        assert(!JSON.stringify(f.warnings).includes('PRIVATE_SENTINEL'));
    }
});
test('ordinary structured endpoint error keeps existing toast and response contract', async () => {
    const f = fixture(), response = new Response(JSON.stringify({type: 'com.vaadin.hilla.exception.EndpointException', message: 'Expected application error'}),
        {status: 400, headers: {'Content-Type': 'application/problem+json'}});
    assert.equal(await f.middleware(context, async () => response), response);
    assert.equal(response.bodyUsed, false); assert.equal(f.toasts[0].description, 'Expected application error');
    assert.equal(f.warnings.length, 0);
});
test('user-info unauthorized response stays quiet and unexpected successful HTML is diagnosed without reading it', async () => {
    const f = fixture(), denied = new Response('PRIVATE_SENTINEL', {status: 401, headers: {'Content-Type': 'text/html'}});
    assert.equal(await f.middleware({endpoint: 'UserEndpoint', method: 'getUserInfo'}, async () => denied), denied);
    assert.equal(denied.bodyUsed, false); assert.equal(f.warnings.length, 0); assert.equal(f.toasts.length, 0);
    const ok = new Response('PRIVATE_SENTINEL', {headers: {'Content-Type': 'text/html'}});
    assert.equal(await f.middleware(context, async () => ok), ok);
    assert.equal(ok.bodyUsed, false); assert(JSON.stringify(f.warnings).includes('non-json-response'));
    assert(!JSON.stringify(f.warnings).includes('PRIVATE_SENTINEL'));
});
test('invalid identifier values are not emitted in diagnostics', async () => {
    const f = fixture();
    await assert.rejects(f.middleware({endpoint: 'PRIVATE_SENTINEL/?token=x', method: 'PRIVATE_SENTINEL/?x'}, async () => {throw new TypeError();}));
    assert(!JSON.stringify(f.warnings).includes('PRIVATE_SENTINEL'));
    assert(JSON.stringify(f.warnings).includes('[invalid]'));
});
