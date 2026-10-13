import test from 'node:test';import assert from 'node:assert/strict';
import {validateArguments,renderingConfiguration,browserResources,failureReceipt,launchEnvironment,classifyLaunchFailure,launchFailureDiagnostics,observeExecutable,browserFailureCounters} from './browser_entry.mjs';
test('authentication failure stage and cgroup evidence are fixed enum and numeric only',async()=>{
 const counts=await browserFailureCounters(async p=>p.endsWith('pids.events')?'max 29':'max 0\noom 0\noom_kill 0');
 assert.equal(counts['pids.events.max'],29);
 assert.deepEqual(failureReceipt('authentication',null,null,'submit',counts),{browserFailed:true,phase:'authentication',authStage:'submit',failureCounters:counts});
 assert.throws(()=>failureReceipt('authentication',null,null,'private password'));
 assert.throws(()=>failureReceipt('navigation',null,null,'submit'));
 assert.deepEqual(Object.values(await browserFailureCounters(async()=>{throw new Error('private');})),[null,null,null,null]);
});
test('observational preflight awaits asynchronous resolver and never exposes paths or blocks launch',async()=>{
  const fake={launch(){},executablePath:async()=>'/private/chrome'};
  const io={stat:async path=>{assert.equal(path,'/private/chrome');return {isFile:()=>true};},access:async path=>assert.equal(path,'/private/chrome')};
  assert.equal((await observeExecutable(fake,io)).preflightPhase,'complete');
  const failure=await observeExecutable(fake,{stat:async()=>{throw Object.assign(new Error('private'),{code:'ENOENT'});}});
  assert.equal(failure.preflightPhase,'stat');assert.equal(failure.preflightErrno,'ENOENT');assert.equal(JSON.stringify(failure).includes('private'),false);
  assert.equal((await observeExecutable({launch(){},executablePath:async()=>{throw new Error('private');}},io)).preflightPhase,'resolve');
});
test('launch diagnostics expose only numeric/enums/booleans from bounded head and tail',()=>{
  const error=new Error('Failed to launch the browser process: Code: 1\nstderr: password=private '+ 'x'.repeat(20000)+'The setuid sandbox is not running as root');
  const row=launchFailureDiagnostics(error,{launchCallable:true,executableRegular:true,executableAccessible:true});
  assert.equal(row.exitCode,1);assert.equal(row.setuidNotRoot,true);assert.equal(row.messageTruncated,true);
  assert.equal(JSON.stringify(row).includes('private'),false);assert.equal(JSON.stringify(row).includes('/home'),false);
  const arbitrary=launchFailureDiagnostics({name:'secret',code:'secret',message:'secret Code: 42'});
  assert.equal(arbitrary.errorName,'other');assert.equal(arbitrary.errno,'other');assert.equal(arbitrary.exitCode,null);
  assert.equal(launchFailureDiagnostics(new TypeError('puppeteer.launch is not a function')).launchNotFunction,true);
  assert.equal(launchFailureDiagnostics(new Error('Target.setAutoAttach timed out. Increase the protocolTimeout setting')).protocolTimeout,true);
  const spawn=launchFailureDiagnostics(new Error('Failed to launch the browser process: spawn /private/chrome EACCES\nstderr:\nprivate'));
  assert.equal(spawn.processExitHeader,true);assert.equal(spawn.errno,'EACCES');assert.equal(spawn.exitCode,null);
  assert.equal(launchFailureDiagnostics(new Error('Failed to launch the browser process: Code: undefined\nstderr:\nENOENT')).errno,'other');
});
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
