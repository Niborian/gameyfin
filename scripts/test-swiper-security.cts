// Actual package regression; synthetic keys only, isolated from the app/runtime.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {createRequire} = require('node:module');
const {resolve} = require('node:path');
const {pathToFileURL} = require('node:url');

test('Swiper rejects prototype keys with overridden array methods', async () => {
    const appRequire = createRequire(resolve(__dirname, '../app/package.json'));
    const {default: Swiper} = await import(pathToFileURL(appRequire.resolve('swiper')).href);
    const originalIndexOf = Array.prototype.indexOf;
    const originalFilter = Array.prototype.filter;
    const key = 'gameyfinSwiperSyntheticProbe';
    assert.equal(Object.hasOwn(Object.prototype, key), false);
    for (const method of ['indexOf', 'filter']) {
        let polluted = false;
        try {
            if (method === 'indexOf') Array.prototype.indexOf = () => -1;
            else Array.prototype.filter = function () { return this; };
            Swiper.extendDefaults(JSON.parse('{"__proto__":{"gameyfinSwiperSyntheticProbe":true}}'));
            polluted = Object.hasOwn(Object.prototype, key);
        } finally {
            Array.prototype.indexOf = originalIndexOf;
            Array.prototype.filter = originalFilter;
            delete (Object.prototype as Record<string, unknown>)[key];
        }
        assert.equal(polluted, false, `Prototype key escaped with overridden ${method}`);
    }
});
