import test from 'node:test';import assert from 'node:assert/strict';
import {fatalFlags,counters,zygoteChecks,installedChromeVersion} from './chrome_smoke.mjs';
test('version and known CHECK line retain numbers only, never executable/source paths',()=>{
 assert.equal(installedChromeVersion('/home/private/chrome/linux-155.0.8059.39/chrome-linux64/chrome'),'155.0.8059.39');
 assert.equal(installedChromeVersion('/private/secret.exe'),null);
 for(const line of ['[zygote_host_impl_linux.cc:191] Check failed: .','[zygote_host_impl_linux.cc(191)] Check failed: .'])assert.equal(zygoteChecks(line).sourceLine,191);
 assert.equal(zygoteChecks('[zygote_host_impl_linux.cc:100001] Check failed: .').sourceLine,null);
});
test('fixed known CHECK predicates and errno never retain expression/path text',()=>{
 const row=zygoteChecks('[FATAL:zygote_host_impl_linux.cc:191] Check failed: process.IsValid(). private/path password=secret: Operation not permitted (1)');
 assert.equal(row.processValid,true);assert.equal(row.errno,1);assert.equal(JSON.stringify(row).includes('private'),false);
 assert.equal(zygoteChecks('Check failed: process.IsValid() Operation not permitted (1)').processValid,false);
 assert.equal(zygoteChecks('[zygote_host_impl_linux.cc:1] Check failed: unknown private errno (999)').errno,null);
 for(const [expression,key] of [['0 == socketpair(AF_UNIX)','socketpair'],['base::UnixDomainSocket::EnableReceiveProcessId(fds[0])','receiveProcessId'],['boot_pid > 1','bootPid'],['real_pid > 1','realPid']])assert.equal(zygoteChecks('[zygote_host_impl_linux.cc:1] Check failed: '+expression)[key],true);
});
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
