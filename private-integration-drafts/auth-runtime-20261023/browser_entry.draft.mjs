// DRAFT ONLY: explicit hosted case1 CLI; no current harness/workflow imports this copy.
// Hosted-container only. Normal UI login; private stdin, sanitized stdout.
import {runPublicAuthCase} from './case1_public_auth.mjs';
import {bookmarkBrowserTunnelCohort} from './passive_tunnel_cohort.mjs';
import {parseCase1Arguments,CASE1_COMMAND,case1Receipt,validateCase1Failure} from './case1_protocol.mjs';
import {fixedFixtureTransports} from '../../scripts/browser-benchmark/frontend_fixture_transport.mjs';
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {LoopbackProxy} from '../../scripts/browser-benchmark/frontend_loopback_proxy.mjs';
import {chromeProxyFlags} from '../../scripts/browser-benchmark/frontend_proxy_policy.mjs';
import {FixtureUiDriver,safeAuthDiagnostics,safeProxyDiagnostics} from '../../scripts/browser-benchmark/frontend_ui_driver.mjs';
import {createCdpTransport,recordFirstAuthCdpFailure} from '../../scripts/browser-benchmark/frontend_cdp_failures.mjs';
export {classifyCdpFailure,recordFirstAuthCdpFailure} from '../../scripts/browser-benchmark/frontend_cdp_failures.mjs';
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
let uiRounds=1,uiRound=1,publicAuthCase1Failure=null;
function emit(value){process.stdout.write(JSON.stringify(uiRounds===3?{uiRound,receipt:value}:value)+'\n');}
function emitFailure(phase){if(!failureEmitted){failureEmitted=true;const receipt=failureReceipt(phase,launchFailureCategory,launchDiagnostics,authStage,failureCounters,authDiagnostics);if(publicAuthCase1Failure!==null){if(phase!=='navigation')throw new Error('Case1 failure phase');receipt.publicAuthCase1Failure=validateCase1Failure(publicAuthCase1Failure);}emit(receipt);}}

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
  const identity=validateArguments(parseCase1Arguments(argv));
  uiRounds=1;
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
    const origin='http://127.0.0.1:55201';proxy=new LoopbackProxy(origin,{...fixedFixtureTransports(origin,identity.appIp),threeUiRounds:uiRounds===3});if(uiRounds===3)proxy.beginRound(1);await proxy.listen(55202);
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
    bindCase1ObservedEvents(session,cdp);
    await enableCase1Observation(cdp);
    driver=new FixtureUiDriver(cdp,{origin,...identity,onProgress:progress=>emit({navigationProgress:{...progress,proxyDiagnostics:safeProxyDiagnostics(proxy.diagnostics(),uiRounds===3)}})});
    currentPhase='input';
    if(await readLine()!==CASE1_COMMAND)throw new Error('Exact case1 controller command required');
    currentPhase='navigation';
    const proof=await runPublicAuthCase({driver,cdp,publicPolicyEnabled:true,bookmarkTunnels:()=>bookmarkBrowserTunnelCohort(proxy),onFailure:value=>{publicAuthCase1Failure=validateCase1Failure(value);}});
    if(timedOut)throw new Error('Controller deadline');
    emit(case1Receipt(proof,proxy.diagnostics()));

  }catch(error){failurePhase=currentPhase;failureCounters=await browserFailureCounters();if(failurePhase==='authentication'){if(authStages.includes(driver?.authStage))authStage=driver.authStage;authDiagnostics=driver?.authDiagnostics||null;}if(failurePhase==='chrome-launch'){launchFailureCategory=classifyLaunchFailure(error);launchDiagnostics=launchFailureDiagnostics(error,preflight);}emitFailure(failurePhase);throw new Error('Hosted browser operation failed');}finally{
    currentPhase='cleanup';
    clearTimeout(timer);process.stdin.destroy();proxy?.close();
    // Container removal is the independent descendant fence. Never claim the
    // profile clean from browser.close alone; tmpfs is destroyed with container.
    await browser?.close();
  }
}
if(process.argv[1]?.endsWith('/browser_entry.draft.mjs'))main(process.argv.slice(2)).catch(()=>{emitFailure(failurePhase||currentPhase);process.stderr.write('Hosted browser operation failed\n');process.exitCode=1;});


export const case1AdditionalEvents=Object.freeze(['Page.frameNavigated','Network.webSocketCreated','Network.webSocketClosed','Network.webSocketHandshakeResponseReceived','Network.webSocketFrameError']);
const case1ObservationContracts=new WeakMap();
export function case1ObservationReady(cdp){const contract=case1ObservationContracts.get(cdp);return contract?.domainsEnabled===true&&contract.failed===false;}
export async function enableCase1Observation(cdp){
  const contract=case1ObservationContracts.get(cdp);
  if(!contract)throw new Error('Case1 event pipeline must be bound first');
  // These actual domains produce the lifecycle events; never assume listeners enable them.
  for(const method of ['Page.enable','Network.enable'])await cdp.request(method,{});
  if(contract.failed)throw new Error('Case1 observation already failed');
  contract.domainsEnabled=true;
}
export function bindCase1ObservedEvents(session,cdp){
  if(case1ObservationContracts.has(cdp))throw new Error('Case1 pipeline already bound');
  // Preserve all original events, including Fetch ownership, without dropping gate events.
  for(const name of observedNetworkEvents)session.on(name,p=>cdp.onEvent?.(name,p));
  let additional=0,failed=false;
  const invalid=()=>{if(!failed){failed=true;const contract=case1ObservationContracts.get(cdp);if(contract)contract.failed=true;cdp.onEvent?.('Fixture.observationOverflow',{});}};
  const bounded=v=>typeof v==='string'&&v.length>0&&v.length<=128;
  for(const name of case1AdditionalEvents)session.on(name,p=>{
    if(++additional>256){invalid();return;}
    if(name==='Page.frameNavigated'){
      if(typeof p?.frame?.url!=='string'||p.frame.url.length>4096||p.frame.parentId!==undefined&&!bounded(p.frame.parentId)){invalid();return;}
      cdp.onEvent?.(name,{frame:{url:p.frame.url,...(p.frame.parentId===undefined?{}:{parentId:p.frame.parentId})}});
    }else{
      if(!bounded(p?.requestId)||name==='Network.webSocketCreated'&&(typeof p.url!=='string'||p.url.length>4096)){invalid();return;}
      if(name==='Network.webSocketHandshakeResponseReceived'&&(!Number.isInteger(p.response?.status)||p.response.status<100||p.response.status>599)){invalid();return;}
      // Do not forward handshake headers/statusText or frame error text/payload.
      cdp.onEvent?.(name,{requestId:p.requestId,...(name==='Network.webSocketCreated'?{url:p.url}:{}),...(name==='Network.webSocketHandshakeResponseReceived'?{status:p.response.status}:{})});
    }
  });
  case1ObservationContracts.set(cdp,{domainsEnabled:false,failed:false});
}
