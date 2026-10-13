import test from 'node:test';import assert from 'node:assert/strict';
import {validateArguments,renderingConfiguration,browserResources,failureReceipt,launchEnvironment,classifyLaunchFailure} from './browser_entry.mjs';
test('bounded launch classification retains only fixed categories, never exception text',()=>{
  const cases=[['chrome_crashpad_handler: --database is required','crashpad-database'],['Read-only file system','readonly-filesystem'],['No usable sandbox','sandbox-unavailable'],['Failed to move to new namespace: Operation not permitted','namespace-permission'],['error while loading shared libraries: private.so','missing-shared-library'],['Could not find Chrome','missing-executable'],['Timed out after 10000 ms while waiting for the WS endpoint URL','launch-timeout']];
  for(const [message,expected] of cases){const category=classifyLaunchFailure(new Error(message+' password=secret https://private/'));assert.equal(category,expected);assert.equal(JSON.stringify(failureReceipt('chrome-launch',category)).includes('secret'),false);}
  assert.equal(classifyLaunchFailure(new Error('private credential')),'other');
  assert.equal(classifyLaunchFailure(new Error('Operation not permitted without namespace evidence')),'other');
  assert.equal(classifyLaunchFailure(new Error('x'.repeat(8192)+'No usable sandbox')),'other');
  assert.throws(()=>failureReceipt('navigation','sandbox-unavailable'));assert.throws(()=>failureReceipt('chrome-launch','raw secret'));
});
test('readonly-root launch writes XDG only into owned tmpfs profile without changing HOME or executable cache',()=>{
  const original={HOME:'/home/pptruser',PUPPETEER_CACHE_DIR:'/home/pptruser/.cache/puppeteer'};
  assert.deepEqual(launchEnvironment('/tmp/gameyfin-browser-aB1234',original),{...original,XDG_CONFIG_HOME:'/tmp/gameyfin-browser-aB1234/xdg-config',XDG_CACHE_HOME:'/tmp/gameyfin-browser-aB1234/xdg-cache'});
  assert.deepEqual(original,{HOME:'/home/pptruser',PUPPETEER_CACHE_DIR:'/home/pptruser/.cache/puppeteer'});
  for(const path of ['/home/pptruser','/tmp/other-abcdef','/tmp/gameyfin-browser-abcdef/../outside'])assert.throws(()=>launchEnvironment(path,original));
});
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
