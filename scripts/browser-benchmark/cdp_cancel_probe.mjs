// Explicit hosted developer protocol test, never application/browser acceptance.
import http from 'node:http';
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
import {chromeProxyFlags} from './frontend_proxy_policy.mjs';
import {launchEnvironment} from './browser_entry.mjs';
import {CoverCancellationLedger} from './frontend_cover_cancellation.mjs';
import {createCdpTransport,classifyCdpFailure} from './frontend_cdp_failures.mjs';
import {allowRequest} from './frontend_cdp_measurement.mjs';
export const probeScope='Owned Chrome-only cancellation ordering; not application or performance acceptance';
const origin='http://127.0.0.1:55201';
function barrier(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}
async function bounded(promise,ms){let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Probe operation deadline')),ms);})]);}finally{clearTimeout(timer);}}
export async function probeCase(session,kind,{limitMs=15000}={}){
 if(!['current-document','old-loader'].includes(kind)||!Number.isInteger(limitMs)||limitMs<1||limitMs>15000)throw new Error('Probe case boundary');
 const ledger=new CoverCancellationLedger(origin,1),paused=barrier(),canceled=barrier(),loaded=barrier(),newDocument=barrier();let held=null,fatal=false,timer;
 const request=createCdpTransport(session,{captureInterception:()=>ledger.stamp(),onFatal:()=>{fatal=true;}});
 const listeners=[];const on=(name,fn)=>{session.on(name,fn);listeners.push([name,fn]);};
 for(const name of ['Network.requestWillBeSent','Network.loadingFinished','Network.loadingFailed'])on(name,p=>{
  ledger.observe(name,p);
  if(name==='Network.loadingFailed'&&held&&p.requestId===held.params.networkId&&p.canceled===true)canceled.resolve();
  if(name==='Network.requestWillBeSent'&&p.type==='Document'&&p.request?.url===origin+'/b')newDocument.resolve();
 });
 on('Page.loadEventFired',()=>loaded.resolve());
 on('Fetch.requestPaused',p=>{
  const permitted=allowRequest(p.request?.url,origin);
  if(p.resourceType==='Image'&&ledger.exactCover(p.request)){
   if(held){fatal=true;return;}
   const record=ledger.pause(p,permitted,false);held={params:p,record,assessment:ledger.assessment(p,permitted,false,record!==null)};paused.resolve();return;
  }
  request(permitted?'Fetch.continueRequest':'Fetch.failRequest',permitted?{requestId:p.requestId}:{requestId:p.requestId,errorReason:'BlockedByClient'}).catch(()=>{fatal=true;});
 });
 const work=async()=>{
  await request('Page.enable');await request('Network.enable');await request('Fetch.enable',{patterns:[{urlPattern:'*',requestStage:'Request'}]});
  ledger.navigationStarted();await request('Page.navigate',{url:origin+'/a'});await loaded.promise;
  await request('Runtime.evaluate',{expression:"(()=>{const i=document.createElement('img');i.id='cover';document.body.append(i);i.src='/images/cover/1';})()"});await paused.promise;
  await request('Runtime.evaluate',{expression:"(()=>{const i=document.getElementById('cover');i.remove();i.src='';})()"});
  await canceled.promise; // Deliberately held ONE request, only in this protocol test.
  if(kind==='old-loader'){ledger.navigationStarted();await request('Page.navigate',{url:origin+'/b'});await newDocument.promise;}
  let error=null;try{await request('Fetch.continueRequest',{requestId:held.params.requestId});}catch(value){error=value;}
  const category=error?classifyCdpFailure(error,'Fetch.continueRequest'):'none';
  const eligibility=ledger.diagnose(held.assessment,error,false),terminal=ledger.terminal(held.record,error,false);
  const expected=category==='interception-invalid-id'&&!fatal&&(kind==='current-document'?terminal&&eligibility.reason==='eligible':!terminal&&eligibility.loaderMatches===false);
  return {kind,complete:expected,category,terminalCancellationAccepted:terminal,matchingCancellationObserved:true,continueCommands:1,eligibility};
 };
 try{return await Promise.race([work(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Probe case deadline')),Math.max(1,limitMs-Math.min(1000,limitMs/2)));})]);}
 finally{clearTimeout(timer);for(const [name,fn] of listeners)session.off(name,fn);await bounded(session.detach(),Math.min(1000,Math.max(1,limitMs/2)));}
}
export async function cleanupBrowser(browser,profile,{remove=fs.rm,access=fs.access,closeMs=4000}={}){
 if(!Number.isInteger(closeMs)||closeMs<1||closeMs>4000)throw new Error('Cleanup budget boundary');
 let closed=false,removed=false,forced=false;const child=browser?.process();let timer;
 try{if(browser){await Promise.race([browser.close(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Probe cleanup deadline')),closeMs);})]);closed=!!child&&(child.exitCode!==null||child.signalCode!==null);}else closed=true;}
 catch{forced=true;if(child&&child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');await new Promise(resolve=>{const timeout=setTimeout(resolve,2000);child.once('exit',()=>{clearTimeout(timeout);resolve();});});}closed=!!child&&(child.exitCode!==null||child.signalCode!==null);}
 finally{clearTimeout(timer);}
 if(profile){try{if(!closed)throw new Error('Unknown browser termination');await remove(profile,{recursive:true,force:false});try{await access(profile);}catch(e){if(e.code==='ENOENT')removed=true;}}catch{}}
 else removed=true;
 return {browserClosed:closed,profileRemoved:removed,forcedTermination:forced,complete:closed&&removed&&!forced};
}
export async function runProbe(puppeteer){
 let browser,profile,proxy,app,timer;const sockets=new Set();
 const result={scope:probeScope,complete:false,sandboxVerified:false,cases:[],failure:'none',cleanup:{browserClosed:false,profileRemoved:false,forcedTermination:false,complete:false},listenersClosed:false};
 // Main work is at most75s, reserving cleanup inside the90s whole-probe bound.
 const work=async()=>{
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
  app=http.createServer({maxHeaderSize:16384},(req,res)=>{if(!['GET','HEAD'].includes(req.method)||!['/a','/b','/images/cover/1','/favicon.ico'].includes(req.url)){res.writeHead(404);res.end();return;}res.setHeader('Cache-Control','no-store');if(req.url==='/images/cover/1'){res.setHeader('Content-Type','image/png');res.end(png);}else{res.setHeader('Content-Type','text/html');res.end('<!doctype html><html><body>Owned protocol fixture</body></html>');}});
  app.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));s.on('error',()=>{});s.setTimeout(15000,()=>s.destroy());});
  await new Promise((resolve,reject)=>{app.once('error',reject);app.listen(55201,'127.0.0.1',resolve);});proxy=new LoopbackProxy(origin);await proxy.listen(55202);
  profile=await fs.mkdtemp('/tmp/gameyfin-browser-');await fs.mkdir(profile+'/xdg-config',{mode:0o700});await fs.mkdir(profile+'/xdg-cache',{mode:0o700});
  browser=await puppeteer.launch({headless:true,userDataDir:profile,env:launchEnvironment(profile),dumpio:false,timeout:10000,args:[...chromeProxyFlags(55202),'--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run']});
  const sandbox=await browser.newPage();await sandbox.goto('chrome://sandbox',{timeout:10000});result.sandboxVerified=await sandbox.$eval('#evaluation',e=>e.textContent.trim()==='You are adequately sandboxed.');await sandbox.close();if(!result.sandboxVerified)throw new Error('Probe sandbox');
  for(const kind of ['current-document','old-loader']){const page=await browser.newPage();try{result.cases.push(await probeCase(await page.createCDPSession(),kind));}finally{await page.close();}if(!result.cases.at(-1).complete)throw new Error('Probe not reproduced');}
 };
 try{await Promise.race([work(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Probe overall deadline')),75000);})]);}
 catch{result.failure='probe-failed-or-not-reproduced';}
 finally{
  clearTimeout(timer);result.cleanup=await cleanupBrowser(browser,profile);
  try{const proxyClosed=proxy&&proxy.server.listening?new Promise(r=>proxy.server.once('close',r)):Promise.resolve();proxy?.close();for(const s of sockets)s.destroy();const appClosed=app&&app.listening?new Promise((resolve,reject)=>app.close(e=>e?reject(e):resolve())):Promise.resolve();await bounded(Promise.all([proxyClosed,appClosed]),2000);result.listenersClosed=sockets.size===0&&(!proxy||proxy.sockets.size===0&&proxy.reservedSockets===0);}catch{result.listenersClosed=false;}
  result.complete=result.failure==='none'&&result.cases.length===2&&result.cases.every(c=>c.complete)&&result.cleanup.complete&&result.listenersClosed;
 }
 return result;
}
async function main(){if(process.getuid?.()===0||process.env.GAMEYFIN_HOSTED_BROWSER!=='reviewed-fixture-only')throw new Error('Hosted probe boundary');const require=createRequire('/home/pptruser/package.json');const result=await runProbe(require('puppeteer'));process.stdout.write(JSON.stringify(result)+'\n');if(!result.complete)process.exitCode=1;}
if(process.argv[1]?.endsWith('/cdp_cancel_probe.mjs'))main().catch(()=>{process.stdout.write(JSON.stringify({scope:probeScope,complete:false,failure:'entry-failed'})+'\n');process.exitCode=1;});
