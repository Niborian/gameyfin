import test from 'node:test';import assert from 'node:assert/strict';
import {fatalFlags,counters} from './chrome_smoke.mjs';
test('bounded fatal booleans never retain stderr text',()=>{
 const row=fatalFlags('secret /private credentials.cc Check failed: pthread_create: Resource temporarily unavailable');
 assert.equal(row.pthreadCreate,true);assert.equal(row.credentialsSource,true);assert.equal(JSON.stringify(row).includes('secret'),false);
 assert.ok(Object.values(row).every(x=>typeof x==='boolean'));
});
test('owned cgroup counters strict numeric allowlist and unavailable explicit',async()=>{
 const row=await counters(async p=>p.endsWith('pids.events')?'max 2\nprivate 666':'max 1\noom 0\noom_kill 0');
 assert.equal(row['pids.events.max'],2);assert.equal(Object.keys(row).length,4);
 assert.deepEqual(Object.values(await counters(async()=>{throw new Error('private');})),[null,null,null,null]);
});
