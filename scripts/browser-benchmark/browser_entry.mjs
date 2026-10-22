// Hosted-container only. Normal UI login; private stdin, sanitized stdout.
import {fixedFixtureTransports} from './frontend_fixture_transport.mjs';
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
import {chromeProxyFlags} from './frontend_proxy_policy.mjs';
import {FixtureUiDriver,safeAuthDiagnostics,safeProxyDiagnostics} from './frontend_ui_driver.mjs';
import {createCdpTransport,recordFirstAuthCdpFailure} from './frontend_cdp_failures.mjs';
export {classifyCdpFailure,recordFirstAuthCdpFailure} from './frontend_cdp_failures.mjs';
let currentPhase='arguments',failurePhase=null,failureEmitted=false,launchFailureCategory=null,launchDiagnostics=null,authStage=null,failureCounters=null,authDiagnostics=null;
export const authStages=Object.freeze(['interception','login-navigation','login-form','submit','redirect','profile-proof','complete']);
export const observedNetworkEvents=Object.freeze(['Fetch.requestPaused','Network.requestWillBeSent','Network.responseReceived','Network.loadingFinished','Network.loadingFailed','Network.requestWillBeSentExtraInfo','Network.responseReceivedExtraInfo','Runtime.exceptionThrown']);
export function authenticationReady(proof){
  if(!proof||typeof proof!=='object'||Object.keys(proof).length!==2||typeof proof.normalLoginAutoRedirectObserved!=='boolean'||proof.fixtureProfileVerified!==true)throw new Error('Fixture authentication proof boundary');
  return {authenticated:true,sandboxVerified:true,normalLoginAutoRedirectObserved:proof.normalLoginAutoRedirectObserved,fixtureProfileVerified:true};
}
export async function browserFailureCounters(read=fs.readFile){
  const result={};
  for(const [file,keys] of [['pids.events',['max']],['memory.events',['max','oom','oom_kill']]]){
    let text='';try{text=(await read('/sys/fs/cgroup/'+file,'utf8')).slice(0,2048);}catch{}
    for(const key of keys){const match=new RegExp('^'+key+' (\\d+)$','m').exec(text),value=match?Number(match[1]):null;result[file+'.'+key]=Number.isSafeInteger(value)&&value>=0?value:null;}
  }
  return result;
}
export async function observeExecutable(puppeteer,io=fs){
  const observed={launchCallable:typeof puppeteer.launch==='function',executableRegular:null,executableAccessible:null,preflightPhase:'resolve',preflightErrno:'other'};
  try{
    const executable=await puppeteer.executablePath();
    if(typeof executable!=='string'){observed.preflightPhase='invalid-result';return observed;}
    observed.preflightPhase='stat';observed.executableRegular=(await io.stat(executable)).isFile();
    observed.preflightPhase='access';await io.access(executable,1);observed.executableAccessible=true;observed.preflightPhase='complete';
  }catch(error){observed.preflightErrno=['ENOENT','EACCES','EPERM'].includes(error?.code)?error.code:'other';}
  return observed;
}
export function launchFailureDiagnostics(error,preflight={}){
  const message=typeof error?.message==='string'?error.message:'';
  const text=message.slice(0,8192)+(message.length>8192?message.slice(-8192):'');
  const match=/^Failed to launch the browser process:\s*Code:\s*(\d{1,3})\b/.exec(text);
  const wrapperErrno=/^Failed to launch the browser process:[^\r\n]*\b(ENOENT|EACCES|EPERM)\b/.exec(text);
  const code=match?Number(match[1]):null;
  return {errorName:['Error','TimeoutError','TypeError','SyntaxError'].includes(error?.name)?error.name:'other',
    errno:['ENOENT','EACCES','EPERM'].includes(error?.code)?error.code:wrapperErrno?.[1]||'other',
    exitCode:code!==null&&code<=255?code:null,messageTruncated:message.length>16384,
    launchCallable:preflight.launchCallable===true,
    executableRegular:typeof preflight.executableRegular==='boolean'?preflight.executableRegular:null,
    executableAccessible:typeof preflight.executableAccessible==='boolean'?preflight.executableAccessible:null,
    preflightPhase:['resolve','invalid-result','stat','access','complete'].includes(preflight.preflightPhase)?preflight.preflightPhase:'resolve',
    preflightErrno:['ENOENT','EACCES','EPERM'].includes(preflight.preflightErrno)?preflight.preflightErrno:'other',
    processExitHeader:/^Failed to launch the browser process:/.test(text),launchNotFunction:/puppeteer\.launch is not a function/.test(text),
    setuidNotRoot:/The setuid sandbox is not running as root/.test(text),
    crashpadPermission:/crashpad.*(?:Permission denied|Operation not permitted)/i.test(text),
    devtoolsEndpoint:/waiting for the WS endpoint URL/.test(text),
    protocolTimeout:/timed out\. Increase the ['"]?protocolTimeout['"]? setting/.test(text),
    configuredPathMissing:/Tried to find the browser at the configured path/.test(text),
    cacheConfiguration:/cache path is incorrectly configured/.test(text)};
}
export const launchFailureCategories=Object.freeze(['crashpad-database','readonly-filesystem','sandbox-unavailable','namespace-permission','missing-shared-library','missing-executable','launch-timeout','other']);
export function classifyLaunchFailure(error){
  // Inspect only a bounded in-memory message; never return/store the message.
  const text=(typeof error?.message==='string'?error.message:'').slice(0,8192);
  if(/chrome_crashpad_handler: --database is required/.test(text))return 'crashpad-database';
  if(/Read-only file system|EROFS/.test(text))return 'readonly-filesystem';
  if(/No usable sandbox|SUID sandbox helper binary was found, but is not configured correctly/.test(text))return 'sandbox-unavailable';
  if(/Failed to move to new namespace|Failed to unshare/.test(text))return 'namespace-permission';
  if(/error while loading shared libraries/.test(text))return 'missing-shared-library';
  if(/Could not find Chrome|Browser was not found at the configured executablePath|spawn .* ENOENT/.test(text))return 'missing-executable';
  if(/Timed out after .*waiting for the WS endpoint URL|Timeout of .* exceeded/.test(text))return 'launch-timeout';
  return 'other';
}
export function failureReceipt(phase,category=null,diagnostics=null,stage=null,counters=null,auth=null){
  if(!['arguments','input','bridge','profile','chrome-launch','sandbox','authentication','navigation','logout','cleanup'].includes(phase))throw new Error('Failure phase enum boundary');
  if(category!==null&&(phase!=='chrome-launch'||!launchFailureCategories.includes(category)))throw new Error('Launch failure category boundary');
  if(diagnostics!==null&&phase!=='chrome-launch')throw new Error('Launch diagnostics phase boundary');
  if(stage!==null&&(phase!=='authentication'||!authStages.includes(stage)))throw new Error('Authentication stage boundary');
  if(auth!==null&&phase!=='authentication')throw new Error('Authentication diagnostic phase boundary');
  return {browserFailed:true,phase,...(category===null?{}:{launchFailureCategory:category}),...(diagnostics===null?{}:{launchDiagnostics:diagnostics}),...(stage===null?{}:{authStage:stage}),...(counters===null?{}:{failureCounters:counters}),...(auth===null?{}:{authDiagnostics:safeAuthDiagnostics(auth)})};
}
let uiRounds=1,uiRound=1;
function emit(value){process.stdout.write(JSON.stringify(uiRounds===3?{uiRound,receipt:value}:value)+'\n');}
function emitFailure(phase){if(!failureEmitted){failureEmitted=true;emit(failureReceipt(phase,launchFailureCategory,launchDiagnostics,authStage,failureCounters,authDiagnostics));}}

export function validateArguments(argv){
  if(argv.length!==3||!/^172\.(1[6-9]|2[0-9]|3[01])\.\d{1,3}\.\d{1,3}$/.test(argv[0])||argv[0].split('.').some(x=>Number(x)>255)||!/^\d{1,10}$/.test(argv[1])||Number(argv[1])<1||! /^[a-f0-9]{64}$/.test(argv[2]))throw new Error('Fixture identity boundary');
  return {appIp:argv[0],coverId:Number(argv[1]),coverSha256:argv[2]};
}
export const renderingConfiguration=Object.freeze({width:1280,height:800,locale:'en-US',timezone:'UTC'});
export function launchEnvironment(profile,environment=process.env){
  if(!/^\/tmp\/gameyfin-browser-[A-Za-z0-9]{6}$/.test(profile))throw new Error('Owned launch profile boundary');
  // Keep HOME/Puppeteer executable resolution unchanged. Chrome startup writes
  // XDG config/cache to this existing private tmpfs profile, not readonly HOME.
  return {...environment,XDG_CONFIG_HOME:profile+'/xdg-config',XDG_CACHE_HOME:profile+'/xdg-cache'};
}
export async function browserResources(read=fs.readFile){
  const cpu=await read('/sys/fs/cgroup/cpu.stat','utf8');
  const usage=/^usage_usec (\d+)$/m.exec(cpu);
  const current=Number((await read('/sys/fs/cgroup/memory.current','utf8')).trim());
  const peak=Number((await read('/sys/fs/cgroup/memory.peak','utf8')).trim());
  if(!usage||![current,peak,Number(usage[1])].every(v=>Number.isSafeInteger(v)&&v>=0))throw new Error('Browser cgroup evidence unavailable');
  return {cpuUsageUsec:Number(usage[1]),memoryCurrentBytes:current,memoryPeakBytes:peak};
}

export async function main(argv){
  if(argv.length===5&&argv[3]==='--ui-version=2.4.3'&&argv[4]==='--ui-rounds=3'){uiRounds=3;argv=argv.slice(0,4);}
  const uiVersionOnly=argv.length===4&&argv[3]==='--ui-version=2.4.3';
  if(uiVersionOnly)argv=argv.slice(0,3);
  const identity=validateArguments(argv);
  if(process.getuid?.()===0||process.env.GAMEYFIN_HOSTED_BROWSER!=='reviewed-fixture-only')throw new Error('Hosted nonroot boundary');
  const require=createRequire('/home/pptruser/package.json');
  const puppeteer=require('puppeteer');
  let browser,proxy,profile,driver,preflight={launchCallable:typeof puppeteer.launch==='function'};
  let timedOut=false;const timer=setTimeout(()=>{timedOut=true;browser?.close().catch(()=>{});proxy?.close();},90000);
  const lines=[];let pending,buffer='',bytes=0,inputEnded=false;
  process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{
    bytes+=Buffer.byteLength(chunk);if(bytes>2048){process.stdin.destroy();pending?.reject(new Error('Private input boundary'));return;}
    buffer+=chunk;let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(pending){const p=pending;pending=null;p.resolve(line);}else lines.push(line);}
  });
  process.stdin.on('end',()=>{inputEnded=true;pending?.reject(new Error('Private input unavailable'));});
  const readLine=()=>lines.length?Promise.resolve(lines.shift()):inputEnded?Promise.reject(new Error('Private input unavailable')):new Promise((resolve,reject)=>{const t=setTimeout(()=>{pending=null;reject(new Error('Private input deadline'));},15000);pending={resolve:x=>{clearTimeout(t);resolve(x);},reject:x=>{clearTimeout(t);reject(x);}};});
  try{
    currentPhase='bridge';
    const origin='http://127.0.0.1:55201';proxy=new LoopbackProxy(origin,fixedFixtureTransports(origin,identity.appIp));await proxy.listen(55202);
    currentPhase='profile';
    profile=await fs.mkdtemp('/tmp/gameyfin-browser-');
    await fs.mkdir(profile+'/xdg-config',{mode:0o700});await fs.mkdir(profile+'/xdg-cache',{mode:0o700});
    await fs.mkdir(profile+'/Default');await fs.writeFile(profile+'/Default/Preferences',JSON.stringify({webrtc:{ip_handling_policy:'disable_non_proxied_udp'}}),{mode:0o600});
    currentPhase='chrome-launch';
    // Observational only: failure to inspect must not alter the actual launch.
    preflight=await observeExecutable(puppeteer);
    browser=await puppeteer.launch({headless:true,userDataDir:profile,env:launchEnvironment(profile),dumpio:false,timeout:10000,defaultViewport:{width:1280,height:800},args:[...chromeProxyFlags(55202),'--lang=en-US','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run']});
    currentPhase='sandbox';
    const page=await browser.newPage();await page.emulateTimezone('UTC');await page.setExtraHTTPHeaders({'Accept-Language':'en-US'});await page.goto('chrome://sandbox',{timeout:10000});
    const sandbox=await page.$eval('#evaluation',e=>e.textContent.trim()==='You are adequately sandboxed.');
    if(!sandbox)throw new Error('Actual Chrome sandbox gate');
    const session=await page.createCDPSession();const cdp={onEvent:null,request:createCdpTransport(session,{
      captureInterception:()=>driver?.captureInterceptionFailure(),
      onFatal:(method,error)=>{if(currentPhase==='authentication'&&driver)recordFirstAuthCdpFailure(driver.authDiagnostics,method,error);},
    })};
    for(const name of observedNetworkEvents)session.on(name,p=>cdp.onEvent?.(name,p));
    driver=new FixtureUiDriver(cdp,{origin,...identity,onProgress:progress=>emit({navigationProgress:{...progress,proxyDiagnostics:safeProxyDiagnostics(proxy.diagnostics())}})});
    for(uiRound=1;uiRound<=uiRounds;uiRound++){
    currentPhase='input';let username=await readLine(),password=await readLine();
    if(username.length<1||username.length>128||password.length<16||password.length>256||/[\x00-\x1f\x7f]/.test(username+password))throw new Error('Private credentials boundary');
    currentPhase='authentication';const authProof=await driver.authenticate(username,password,{alreadyOnLogin:uiRound>1});username='';password='';
    if(uiVersionOnly){await driver.poll("(()=>({ready:[...document.querySelectorAll('p')].some(p=>p.textContent.trim()==='Unofficial Gameyfin 2.4.3')}))()",()=>{},driver.postSubmitDeadline);emit({uiVersionVerified:true});}
    emit(authenticationReady(authProof));
    let command=await readLine();
    if(command==='navigate'){
      currentPhase='navigation';
      const before=await browserResources();
      const results=[];for(const route of ['/library/27003','/game/33000','/library/27003'])results.push(await driver.navigate(route));
      if(timedOut)throw new Error('Controller deadline');
      const after=await browserResources();
      emit({originalUiContentReady:true,coverDecoded:true,coverSha256Matches:true,navigations:results,browserResources:{before,after}});
      command=await readLine();
    }
    if(command!=='logout')throw new Error('Controller command boundary');
    currentPhase='logout';const loggedOut=await driver.logout();
    if(loggedOut?.loggedOut!==true)throw new Error('Normal UI logout gate');
    emit({loggedOut:true});
    }
    uiRound=uiRounds;
  }catch(error){failurePhase=currentPhase;failureCounters=await browserFailureCounters();if(failurePhase==='authentication'){if(authStages.includes(driver?.authStage))authStage=driver.authStage;authDiagnostics=driver?.authDiagnostics||null;}if(failurePhase==='chrome-launch'){launchFailureCategory=classifyLaunchFailure(error);launchDiagnostics=launchFailureDiagnostics(error,preflight);}emitFailure(failurePhase);throw new Error('Hosted browser operation failed');}finally{
    currentPhase='cleanup';
    clearTimeout(timer);process.stdin.destroy();proxy?.close();
    // Container removal is the independent descendant fence. Never claim the
    // profile clean from browser.close alone; tmpfs is destroyed with container.
    await browser?.close();
  }
}
if(process.argv[1]?.endsWith('/browser_entry.mjs'))main(process.argv.slice(2)).catch(()=>{emitFailure(failurePhase||currentPhase);process.stderr.write('Hosted browser operation failed\n');process.exitCode=1;});
