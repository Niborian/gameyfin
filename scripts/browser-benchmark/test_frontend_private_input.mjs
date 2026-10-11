import test from 'node:test';import assert from 'node:assert/strict';import {Readable} from 'node:stream';import {readPrivateInput} from './frontend_private_input.mjs';
test('private credentials arrive via bounded stdin only and malformed input is generic',async()=>{
 const value=await readPrivateInput(Readable.from(['fixture\nprivate-test-secret-1234\n']));assert.equal(value.username,'fixture');assert.equal(value.password.length,24);
 for(const s of ['fixture\nshort\n','fixture\nprivate-test-secret-1234\nextra\n','x'.repeat(1025)])await assert.rejects(readPrivateInput(Readable.from([s])),/Private input boundary/);
});
