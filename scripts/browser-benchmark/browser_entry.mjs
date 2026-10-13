// Hosted-container only. Normal UI login; private stdin, sanitized stdout.
import net from 'node:net';
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
import {chromeProxyFlags} from './frontend_proxy_policy.mjs';
import {FixtureUiDriver} from './frontend_ui_driver.mjs';
let currentPhase='arguments',failurePhase=null,failureEmitted=false,launchFailureCategory=null,launchDiagnostics=null;
export function launchFailureDiagnostics(error,preflight={}){
  const message=typeof error?.message==='string'?error.message:'';
  const text=message.slice(0,8192)+(message.length>8192?message.slice(-8192):'');
  const match=/^Failed to launch the browser process:\s*Code:\s*(\d{1,3})\b/.exec(text);
  const code=match?Number(match[1]):null;
  return {errorName:['Error','TimeoutError','TypeError','SyntaxError'].includes(error?.name)?error.name:'other',
    errno:['ENOENT','EACCES','EPERM'].includes(error?.code)?error.code:'other',
    exitCode:code!==null&&code<=255?code:null,messageTruncated:message.length>16384,
    launchCallable:preflight.launchCallable===true,
    executableRegular:typeof preflight.executableRegular==='boolean'?preflight.executableRegular:null,
    executableAccessible:typeof preflight.executableAccessible==='boolean'?preflight.executableAccessible:null,
    processExitHeader:!!match,launchNotFunction:/puppeteer\.launch is not a function/.test(text),
    setuidNotRoot:/The setuid sandbox is not running as root/.test(text),
    crashpadPermission:/crashpad.*(?:Permission denied|Operation not permitted)/i.test(text),
    devtoolsEndpoint:/waiting for the WS endpoint URL/.test(text),
    protocolTimeout:/timed out\. Increase the ['"]?protocolTimeout['"]? setting/.test(text)};
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
export function failureReceipt(phase,category=null,diagnostics=null){
  if(!['arguments','input','bridge','profile','chrome-launch','sandbox','authentication','navigation','cleanup'].includes(phase))throw new Error('Failure phase enum boundary');
  if(category!==null&&(phase!=='chrome-launch'||!launchFailureCategories.includes(category)))throw new Error('Launch failure category boundary');
  if(diagnostics!==null&&phase!=='chrome-launch')throw new Error('Launch diagnostics phase boundary');
  return {browserFailed:true,phase,...(category===null?{}:{launchFailureCategory:category}),...(diagnostics===null?{}:{launchDiagnostics:diagnostics})};
}
function emitFailure(phase){if(!failureEmitted){failureEmitted=true;process.stdout.write(JSON.stringify(failureReceipt(phase,launchFailureCategory,launchDiagnostics))+'\n');}}

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
  const identity=validateArguments(argv);
  if(process.getuid?.()===0||process.env.GAMEYFIN_HOSTED_BROWSER!=='reviewed-fixture-only')throw new Error('Hosted nonroot boundary');
  const require=createRequire('/home/pptruser/package.json');
  const puppeteer=require('puppeteer');
  const sockets=new Set();let browser,proxy,bridge,profile;const preflight={launchCallable:typeof puppeteer.launch==='function'};
  let timedOut=false;const timer=setTimeout(()=>{timedOut=true;browser?.close().catch(()=>{});proxy?.close();for(const s of sockets)s.destroy();},90000);
  const lines=[];let pending,buffer='',bytes=0,inputEnded=false;
  process.stdin.setEncoding('utf8');process.stdin.on('data',chunk=>{
    bytes+=Buffer.byteLength(chunk);if(bytes>2048){process.stdin.destroy();pending?.reject(new Error('Private input boundary'));return;}
    buffer+=chunk;let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(pending){const p=pending;pending=null;p.resolve(line);}else lines.push(line);}
  });
  process.stdin.on('end',()=>{inputEnded=true;pending?.reject(new Error('Private input unavailable'));});
  const readLine=()=>lines.length?Promise.resolve(lines.shift()):inputEnded?Promise.reject(new Error('Private input unavailable')):new Promise((resolve,reject)=>{const t=setTimeout(()=>{pending=null;reject(new Error('Private input deadline'));},15000);pending={resolve:x=>{clearTimeout(t);resolve(x);},reject:x=>{clearTimeout(t);reject(x);}};});
  try{
    currentPhase='bridge';
    bridge=net.createServer(client=>{if(sockets.size>=16){client.destroy();return;}const upstream=net.connect({host:identity.appIp,port:8080});for(const s of [client,upstream]){sockets.add(s);s.setTimeout(15000,()=>s.destroy());s.on('error',()=>{});s.on('close',()=>{sockets.delete(s);client.destroy();upstream.destroy();});}client.pipe(upstream);upstream.pipe(client);});
    await new Promise((resolve,reject)=>{bridge.once('error',reject);bridge.listen(55201,'127.0.0.1',resolve);});
    const origin='http://127.0.0.1:55201';proxy=new LoopbackProxy(origin);await proxy.listen(55202);
    currentPhase='profile';
    profile=await fs.mkdtemp('/tmp/gameyfin-browser-');
    await fs.mkdir(profile+'/xdg-config',{mode:0o700});await fs.mkdir(profile+'/xdg-cache',{mode:0o700});
    await fs.mkdir(profile+'/Default');await fs.writeFile(profile+'/Default/Preferences',JSON.stringify({webrtc:{ip_handling_policy:'disable_non_proxied_udp'}}),{mode:0o600});
    currentPhase='chrome-launch';
    // Observational only: failure to inspect must not alter the actual launch.
    try{const executable=puppeteer.executablePath();preflight.executableRegular=(await fs.stat(executable)).isFile();await fs.access(executable,1);preflight.executableAccessible=true;}catch{preflight.executableAccessible=false;}
    browser=await puppeteer.launch({headless:true,userDataDir:profile,env:launchEnvironment(profile),dumpio:false,timeout:10000,defaultViewport:{width:1280,height:800},args:[...chromeProxyFlags(55202),'--lang=en-US','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run']});
    currentPhase='sandbox';
    const page=await browser.newPage();await page.emulateTimezone('UTC');await page.setExtraHTTPHeaders({'Accept-Language':'en-US'});await page.goto('chrome://sandbox',{timeout:10000});
    const sandbox=await page.$eval('#evaluation',e=>e.textContent.trim()==='You are adequately sandboxed.');
    if(!sandbox)throw new Error('Actual Chrome sandbox gate');
    const session=await page.createCDPSession();const cdp={onEvent:null,request:(m,p,timeoutMs=15000)=>new Promise((resolve,reject)=>{
      const deadline=setTimeout(()=>reject(new Error('Private CDP deadline')),Math.min(15000,timeoutMs));
      session.send(m,p).then(value=>{clearTimeout(deadline);resolve(value);},()=>{clearTimeout(deadline);reject(new Error('Private CDP operation failed'));});
    })};
    for(const name of ['Fetch.requestPaused','Network.responseReceived','Network.loadingFinished'])session.on(name,p=>cdp.onEvent?.(name,p));
    const driver=new FixtureUiDriver(cdp,{origin,...identity});
    currentPhase='input';let username=await readLine(),password=await readLine();
    if(username.length<1||username.length>128||password.length<16||password.length>256||/[\x00-\x1f\x7f]/.test(username+password))throw new Error('Private credentials boundary');
    currentPhase='authentication';await driver.authenticate(username,password);username='';password='';
    process.stdout.write(JSON.stringify({authenticated:true,sandboxVerified:true})+'\n');
    if(await readLine()!=='navigate')throw new Error('Controller command boundary');
    currentPhase='navigation';
    const before=await browserResources();
    const results=[];for(const route of ['/library/27003','/game/33000','/library/27003'])results.push(await driver.navigate(route));
    if(timedOut)throw new Error('Controller deadline');
    const after=await browserResources();
    process.stdout.write(JSON.stringify({originalUiContentReady:true,coverDecoded:true,coverSha256Matches:true,navigations:results,browserResources:{before,after}})+'\n');
  }catch(error){failurePhase=currentPhase;if(failurePhase==='chrome-launch'){launchFailureCategory=classifyLaunchFailure(error);launchDiagnostics=launchFailureDiagnostics(error,preflight);}emitFailure(failurePhase);throw new Error('Hosted browser operation failed');}finally{
    currentPhase='cleanup';
    clearTimeout(timer);process.stdin.destroy();proxy?.close();for(const s of sockets)s.destroy();bridge?.close();
    // Container removal is the independent descendant fence. Never claim the
    // profile clean from browser.close alone; tmpfs is destroyed with container.
    await browser?.close();
  }
}
if(process.argv[1]?.endsWith('/browser_entry.mjs'))main(process.argv.slice(2)).catch(()=>{emitFailure(failurePhase||currentPhase);process.stderr.write('Hosted browser operation failed\n');process.exitCode=1;});
