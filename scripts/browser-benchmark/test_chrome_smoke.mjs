import test from 'node:test';import assert from 'node:assert/strict';
import {fatalFlags,counters,zygoteChecks,installedChromeVersion,redactedFatalExcerpt,smokeChildEnvironment} from './chrome_smoke.mjs';
test('smoke child environment is constant whitelist, never inherits fixture or account values',()=>{
 const env=smokeChildEnvironment('/tmp/gameyfin-browser-ABC123');
 assert.deepEqual(Object.keys(env).sort(),['DBUS_SESSION_BUS_ADDRESS','HOME','LANG','PATH','XDG_CACHE_HOME','XDG_CONFIG_HOME'].sort());
 assert.equal(env.HOME,'/home/pptruser');assert.equal(env.AUTH_TOKEN,undefined);
});
test('fatal excerpt bounded and failclosed for credentials, paths, URLs and control bytes',()=>{
 const raw='[FATAL:/private/zygote.cc:3] Check failed: . https://private/ 10.0.0.200 C:\\private\\file a'.repeat(1)+'\n[ERROR] password=hidden\n[ERROR] Bearer hidden\n[ERROR] cookie: hidden\n[ERROR] key: hidden\n[ERROR] bad\u0001text';
 const row=redactedFatalExcerpt(raw);const text=JSON.stringify(row);
 for(const privateValue of ['/private','https://','10.0.0.200','hidden','C:\\private'])assert.equal(text.includes(privateValue),false);
 assert.equal(row.rejected,true);
 for(const label of ['api_key','api key','client_key','credential','session','session_id','session-id','SID','client_id','csrf','auth','auth_header','authorization'])assert.equal(redactedFatalExcerpt('[ERROR] '+label+': hidden').lines.length,0);
 assert.ok(redactedFatalExcerpt('[ERROR] ordinary noise\n'.repeat(20)+'[FATAL] Check failed: critical').lines[0].includes('critical'));
 const more=JSON.stringify(redactedFatalExcerpt('[FATAL] ws://private/ ftp://private/ 2001:db8::1 \\private\\file'));
 for(const value of ['ws://','ftp://','2001:db8','\\private'])assert.equal(more.includes(value),false);
 const bounded=redactedFatalExcerpt(('[ERROR] '+ 'x'.repeat(300)+'\n').repeat(100));
 assert.ok(bounded.lines.length<=16);assert.ok(bounded.lines.every(x=>x.length<=256));assert.ok(Buffer.byteLength(bounded.lines.join('\n'))<=2048);
});
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
