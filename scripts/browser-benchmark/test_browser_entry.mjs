import test from 'node:test';import assert from 'node:assert/strict';
import {validateArguments,renderingConfiguration,browserResources,failureReceipt} from './browser_entry.mjs';
test('failure evidence is an enum, never raw exception/credential text',()=>{
  assert.deepEqual(failureReceipt('sandbox'),{browserFailed:true,phase:'sandbox'});
  assert.throws(()=>failureReceipt('password or URL from exception'));
});
test('browser cgroup resource counters are separate and strictly numeric',async()=>{
  assert.deepEqual(await browserResources(async p=>p.endsWith('cpu.stat')?'usage_usec 42\n':p.endsWith('memory.peak')?'64':'32'),{cpuUsageUsec:42,memoryCurrentBytes:32,memoryPeakBytes:64});
  await assert.rejects(browserResources(async()=> 'invalid'));
});
test('fixed comparable rendering contract',()=>assert.deepEqual(renderingConfiguration,{width:1280,height:800,locale:'en-US',timezone:'UTC'}));
test('only reviewed private literal fixture target and exact cover identity',()=>{
  assert.equal(validateArguments(['172.22.0.2','1','a'.repeat(64)]).appIp,'172.22.0.2');
  for(const ip of ['localhost','10.0.0.200','127.0.0.1','172.22.0.256','https://172.22.0.2'])assert.throws(()=>validateArguments([ip,'1','a'.repeat(64)]));
  assert.throws(()=>validateArguments(['172.22.0.2','0','a'.repeat(64)]));
});
