// Diagnostic-only pre-auth Chrome startup, hosted owned container only.
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {launchEnvironment,launchFailureDiagnostics} from './browser_entry.mjs';
import {chromeProxyFlags} from './frontend_proxy_policy.mjs';
export function fatalFlags(text){
  text=typeof text==='string'?text.slice(0,8192)+text.slice(-8192):'';
  return {pthreadCreate:/pthread_create/.test(text),resourceUnavailable:/Resource temporarily unavailable/.test(text),
    checkFailed:/Check failed:/.test(text),seccomp:/seccomp/i.test(text),ptrace:/ptrace/i.test(text),
    credentialsSource:/credentials\.cc/.test(text),zygoteSource:/zygote_host_impl_linux\.cc/.test(text)};
}
export function zygoteChecks(text){
  text=typeof text==='string'?text.slice(0,8192)+text.slice(-8192):'';
  const lines=text.split('\n').filter(line=>/zygote_host_impl_linux\.cc/.test(line)&&/Check failed:/.test(line));
  const result={processValid:false,socketpair:false,receiveProcessId:false,bootPid:false,realPid:false,errno:null,sourceLine:null};
  for(const line of lines){
    const source=/zygote_host_impl_linux\.cc(?::|\()(\d{1,6})(?:\)|\]|:)/.exec(line);
    if(source&&Number(source[1])<=100000)result.sourceLine=Number(source[1]);
    result.processValid ||= /Check failed: process\.IsValid\(\)/.test(line);
    result.socketpair ||= /Check failed:[^\n]*socketpair\(/.test(line);
    result.receiveProcessId ||= /Check failed:[^\n]*EnableReceiveProcessId\(/.test(line);
    result.bootPid ||= /Check failed:[^\n]*boot_pid\s*>\s*1/.test(line);
    result.realPid ||= /Check failed:[^\n]*real_pid\s*>\s*1/.test(line);
    const errno=/(?:Operation not permitted|Permission denied|Resource temporarily unavailable|No space left on device|Function not implemented|Operation not supported) \((\d{1,3})\)\s*$/.exec(line);
    if(errno&&[1,11,13,28,38,95].includes(Number(errno[1])))result.errno=Number(errno[1]);
  }
  return result;
}
export function installedChromeVersion(executable){
  if(typeof executable!=='string')return null;
  const match=/\/chrome\/linux-(\d{1,5}\.\d{1,5}\.\d{1,5}\.\d{1,5})\//.exec(executable);
  return match?match[1]:null;
}
export function smokeChildEnvironment(profile){
  return launchEnvironment(profile,{HOME:'/home/pptruser',PATH:'/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',LANG:'en_US.UTF-8',DBUS_SESSION_BUS_ADDRESS:'autolaunch:'});
}
export function redactedFatalExcerpt(raw){
  // Solely the unauthenticated Chrome smoke. Normal benchmark errors remain enums.
  const input=typeof raw==='string'?raw.slice(-16384):'';const lines=[],candidates=[];let following=false,previousFatal=false,rejected=false,total=0;
  for(const original of input.split(/\r?\n/)){
    const fatal=/\bFATAL\b|Check failed:/.test(original),priority=fatal||previousFatal?0:1;
    const selected=/\b(?:FATAL|ERROR)\b|Check failed:/.test(original)||following;
    following=/\b(?:FATAL|ERROR)\b|Check failed:/.test(original);
    previousFatal=fatal;
    if(!selected||!original.trim())continue;
    if(/[^\x20-\x7e\t]/.test(original)||/password|passwd|secret|token|auth|cookie|credential|csrf|(?:api|client|private)[_ -]?key|client[_-]?id|session|\bsid\b|\bkey\b|\bbearer\b|\bENV\b|command.?line|\bargs?\s*[:=]|^\s*(?:at\s|#\d|0x)/i.test(original)){rejected=true;continue;}
    let text=original.replace(/[a-z][a-z0-9+.-]*:\/\/[^\s\]\)]+/gi,'[url]')
      .replace(/(?:[A-Za-z]:[\\/]|(?:\.\.\/)+|\/)[^\s\]\),]+/g,'[path]')
      .replace(/\\[^\s\]\),]+/g,'[path]')
      .replace(/\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b/g,'[ip]')
      .replace(/\b(?:[a-f0-9]{1,4}:){2,}[a-f0-9:]+\b/gi,'[ip]')
      .replace(/\b[a-f0-9]{16,}\b/gi,'[hex]')
      .replace(/\b[A-Za-z0-9_+\/=.-]{24,}\b/g,'[long-value]')
      .replace(/0x[a-f0-9]+/gi,'[address]');
    // Exclude any remaining assignment that could carry private unlabelled data.
    if(/\b[A-Za-z_][A-Za-z0-9_]*\s*=\s*[^=\s]/.test(text)){rejected=true;continue;}
    text=text.slice(0,256);
    candidates.push({text,priority});
  }
  for(const {text} of candidates.sort((a,b)=>a.priority-b.priority)){
    const size=Buffer.byteLength(text)+(lines.length?1:0);
    if(total+size>2048||lines.length>=16)break;
    lines.push(text);total+=size;
  }
  return {lines,rejected};
}
export async function counters(read=fs.readFile){
  const result={};
  for(const [file,keys] of [['pids.events',['max']],['memory.events',['max','oom','oom_kill']]]){
    let text='';try{text=await read('/sys/fs/cgroup/'+file,'utf8');}catch{}
    for(const key of keys){const match=new RegExp('^'+key+' (\\d+)$','m').exec(text);const value=match?Number(match[1]):null;result[file+'.'+key]=Number.isSafeInteger(value)&&value>=0?value:null;}
  }
  return result;
}
async function main(){
  if(process.getuid?.()===0||process.env.GAMEYFIN_HOSTED_BROWSER!=='reviewed-fixture-only')throw new Error('Owned hosted boundary');
  const require=createRequire('/home/pptruser/package.json');const puppeteer=require('puppeteer');const {launch}=require('@puppeteer/browsers');
  let child,browser;let raw='',code=null,signal=null,phase='launch';
  const result={scope:'Chrome-only startup diagnostic; not app performance',chromeCacheVersion:null,started:false,sandboxVerified:false,exitCode:null,signal:'none',signalScope:'Includes diagnostic shutdown; not automatic crash attribution',deadlineTriggered:false,exitObservedBeforeCleanup:false,failurePhase:null,fatalFlags:fatalFlags(''),zygoteChecks:zygoteChecks(''),fatalExcerpt:redactedFatalExcerpt(''),before:await counters(),after:null};
  const timer=setTimeout(()=>{result.deadlineTriggered=true;child?.kill();},15000);
  try{
    const profile=await fs.mkdtemp('/tmp/gameyfin-browser-');
    await fs.mkdir(profile+'/xdg-config',{mode:0o700});await fs.mkdir(profile+'/xdg-cache',{mode:0o700});
    const args=await puppeteer.defaultArgs({headless:true,userDataDir:profile});
    args.push('--remote-debugging-port=0',...chromeProxyFlags(55202),'--lang=en-US','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run');
    const executablePath=await puppeteer.executablePath();result.chromeCacheVersion=installedChromeVersion(executablePath);
    child=launch({executablePath,args,env:smokeChildEnvironment(profile),dumpio:false});
    child.nodeProcess.once('exit',(actualCode,actualSignal)=>{code=actualCode;signal=actualSignal;});
    child.nodeProcess.stderr.on('data',chunk=>{raw=(raw+String(chunk)).slice(-16384);});
    phase='endpoint';const endpoint=await child.waitForLineOutput(/^DevTools listening on (ws:\/\/.*)$/,10000);
    result.started=true;
    phase='connect';browser=await puppeteer.connect({browserWSEndpoint:endpoint});
    phase='sandbox';
    const page=await browser.newPage();await page.goto('chrome://sandbox',{timeout:3000});
    result.sandboxVerified=await page.$eval('#evaluation',e=>e.textContent.trim()==='You are adequately sandboxed.');
  }catch(error){result.failurePhase=phase;result.failure=launchFailureDiagnostics(error,{launchCallable:typeof puppeteer.launch==='function'});}
  finally{
    result.exitObservedBeforeCleanup=code!==null||signal!==null;clearTimeout(timer);try{await browser?.close();}catch{}
    try{await child?.close();}catch{}
    result.exitCode=Number.isInteger(code)&&code>=0&&code<=255?code:null;
    result.signal=['SIGABRT','SIGTRAP','SIGSYS','SIGSEGV','SIGKILL','SIGTERM'].includes(signal)?signal:signal===null?'none':'other';
    result.fatalFlags=fatalFlags(raw);result.zygoteChecks=zygoteChecks(raw);result.fatalExcerpt=redactedFatalExcerpt(raw);raw='';result.after=await counters();
    process.stdout.write(JSON.stringify(result)+'\n');
    if(!result.started||!result.sandboxVerified)process.exitCode=1;
  }
}
if(process.argv[1]?.endsWith('/chrome_smoke.mjs'))main().catch(()=>{process.stderr.write('Chrome smoke failed\n');process.exitCode=1;});
