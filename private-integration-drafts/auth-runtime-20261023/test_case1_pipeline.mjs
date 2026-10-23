// Pure dispatch fixtures only; these are NOT actual browser/runtime acceptance.
import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {FixtureUiDriver} from '../../scripts/browser-benchmark/frontend_ui_driver.mjs';
import {observedNetworkEvents,bindCase1ObservedEvents,enableCase1Observation,case1ObservationReady} from './browser_entry.draft.mjs';
import {runPublicAuthCase} from './case1_public_auth.mjs';
import {parseCase1Arguments,validateCase1Result,validateCase1Failure,CASE1_DOM_FLAGS} from './case1_protocol.mjs';
import {LoopbackProxy} from '../../scripts/browser-benchmark/frontend_loopback_proxy.mjs';
import {bookmarkBrowserTunnelCohort} from './passive_tunnel_cohort.mjs';

test('passive exact browser-side cohort distinguishes close events, destruction and new sockets',()=>{
  const proxy=Object.create(LoopbackProxy.prototype);Object.assign(proxy,{closed:false,inbound:new Map(),sockets:new Set()});
  const socket=()=>Object.assign(new EventEmitter(),{destroyed:false,closed:false,destroy(){throw new Error('Observer must never destroy');}});
  const a=socket(),b=socket(),http=socket();
  for(const [s,tunnel] of [[a,true],[b,true],[http,false]]){proxy.inbound.set(s,{tunnel});proxy.sockets.add(s);}
  const cohort=bookmarkBrowserTunnelCohort(proxy);
  assert.deepEqual(cohort.inspect(),{existing:2,open:2,closed:0,unknown:0,upstreamClosureProven:false});
  // A later auth socket is not a member; removing an entry is NOT a close event.
  const later=socket();proxy.inbound.set(later,{tunnel:true});proxy.sockets.add(later);proxy.inbound.delete(a);
  a.destroyed=true;
  assert.deepEqual(cohort.inspect(),{existing:2,open:1,closed:0,unknown:1,upstreamClosureProven:false});
  b.emit('close');a.emit('close');
  assert.deepEqual(cohort.inspect(),{existing:2,open:0,closed:2,unknown:0,upstreamClosureProven:false});
  cohort.dispose();assert.equal(a.listenerCount('close'),0);assert.throws(()=>cohort.inspect());
  assert.throws(()=>bookmarkBrowserTunnelCohort({inbound:new Map(),sockets:new Set()}));
});
test('invalid partial cohort leaves no listeners; either physical close order is counted',()=>{
  for(const reverse of [false,true]){
    const proxy=Object.create(LoopbackProxy.prototype);Object.assign(proxy,{closed:false,inbound:new Map(),sockets:new Set()});
    const a=Object.assign(new EventEmitter(),{destroyed:false,closed:false}),b=Object.assign(new EventEmitter(),{destroyed:false,closed:false});
    for(const s of [a,b]){proxy.inbound.set(s,{tunnel:true});proxy.sockets.add(s);}
    b.closed=undefined;assert.throws(()=>bookmarkBrowserTunnelCohort(proxy));assert.equal(a.listenerCount('close'),0);
    b.closed=false;const once=b.once;b.once=()=>{throw new Error('synthetic attachment failure');};
    assert.throws(()=>bookmarkBrowserTunnelCohort(proxy),/synthetic attachment failure/);assert.equal(a.listenerCount('close'),0);
    b.once=once;const cohort=bookmarkBrowserTunnelCohort(proxy);
    (reverse?b:a).emit('close');assert.equal(cohort.inspect().closed,1);assert.equal(cohort.inspect().open,1);
    (reverse?a:b).emit('close');assert.equal(cohort.inspect().closed,2);cohort.dispose();
  }
});
test('diagnostic dispose failure cannot strand original CDP event owner',async()=>{
  const f=await fixture({close:false}),owner=f.cdp.onEvent;
  const bookmarkTunnels=()=>({inspect(){return null;},dispose(){throw new Error('synthetic dispose failure');}});
  await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,bookmarkTunnels}),/synthetic dispose failure/);
  assert.equal(f.cdp.onEvent,owner);
});
test('cohort observation uses first enabled login checkpoint and does not repair CDP failure',async()=>{
  const f=await fixture({close:false});let diagnostic,inspections=0,disposed=false;
  const bookmarkTunnels=()=>({inspect(){inspections++;return {existing:1,open:0,closed:1,unknown:0,upstreamClosureProven:false};},dispose(){disposed=true;}});
  await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,bookmarkTunnels,onFailure:value=>{diagnostic=value;}}),/settlement unproved/);
  assert.equal(inspections,1);assert.equal(disposed,true);assert.equal(diagnostic.browserTunnelCheckpoint.closed,1);assert.equal(diagnostic.oldWebSockets,1);
  for(const changed of [{unknown:17},{upstreamClosureProven:true},{open:1},{socketId:'forbidden'}])assert.throws(()=>validateCase1Failure({...diagnostic,browserTunnelCheckpoint:{...diagnostic.browserTunnelCheckpoint,...changed}}));
});

async function fixture(options={}){
  const session=new EventEmitter(), calls=[], origin='http://127.0.0.1:55203';
  const cdp={onEvent:null,request:async(method)=>{calls.push(method);return method==='Network.getResponseBody'?{body:'null',base64Encoded:false}:{};}};
  const driver=new FixtureUiDriver(cdp,{origin,coverId:42,coverSha256:'a'.repeat(64)});
  let forwarded=0;const owner=cdp.onEvent;cdp.onEvent=(...args)=>{forwarded++;owner(...args);};
  if(options.bound!==false)bindCase1ObservedEvents(session,cdp);
  if(options.bound!==false&&options.enabled!==false)await enableCase1Observation(cdp);
  const request=(id,path)=>session.emit('Network.requestWillBeSent',{requestId:id,request:{url:origin+path,method:'POST'},timestamp:1});
  driver.navigate=async()=>{
    driver.navigations++;
    if(driver.navigations===1){
      request('anonymous','/connect/UserEndpoint/getUserInfo');
      session.emit('Network.responseReceived',{requestId:'anonymous',response:{url:origin+'/connect/UserEndpoint/getUserInfo',status:200,headers:{}}});
      session.emit('Network.loadingFinished',{requestId:'anonymous',encodedDataLength:4});
      session.emit('Network.webSocketCreated',{requestId:'public-ws',url:'ws://127.0.0.1:55203/HILLA/push'});
      if(options.handshake)session.emit('Network.webSocketHandshakeResponseReceived',{requestId:'public-ws',response:{status:options.handshake,headers:{secret:'never-retain'},statusText:'never-retain'}});
      if(options.frameError)session.emit('Network.webSocketFrameError',{requestId:'public-ws',errorMessage:'never-retain'});
      if(options.admin)request('admin',options.admin);
      if(options.overflow)for(let i=0;i<257;i++)session.emit('Page.frameNavigated',{frame:{url:origin+'/game/33000'}});
    }
    return {coverSha256Matches:options.badCover!==true}; // Synthetic result, not runtime evidence.
  };
  driver.evaluate=async(expression)=>{
    if(expression.includes('footerVersionMatches:'))return Object.fromEntries(CASE1_DOM_FLAGS.map(k=>[k,false]));
    if(options.frame!==false)session.emit('Page.frameNavigated',{frame:{url:origin+'/login'}});
    if(options.close!==false)session.emit('Network.webSocketClosed',{requestId:'public-ws'});
    if(options.authCatalog){request('late','/connect/GameEndpoint/getAll');session.emit('Network.loadingFinished',{requestId:'late',encodedDataLength:2});}
    return true;
  };
  driver.poll=async()=>({ready:true}); // No sleep or actual browser in dispatch tests.
  return {session,cdp,driver,calls,get forwarded(){return forwarded;}};
}
const execute=f=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true});
test('post-browse failure reports strict boolean current DOM and bounded counts without recovering assertion',async()=>{
  const f=await fixture({close:false});let diagnostic;
  await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,onFailure:value=>{diagnostic=value;}}),/settlement unproved/);
  assert.equal(diagnostic.stage,'settlement');assert.equal(diagnostic.oldWebSockets,1);
  assert.deepEqual(validateCase1Failure(diagnostic),diagnostic);
  for(const changed of [{stage:'arbitrary'},{pendingCatalogHttp:129},{privateText:'forbidden'},{dom:{...diagnostic.dom,rawUrl:'forbidden'}}])assert.throws(()=>validateCase1Failure({...diagnostic,...changed}));
});
test('actual copied event dispatcher projects only bounded WebSocket lifecycle and rejects malformed status',async()=>{
  for(const handshake of [101,403]){
    const f=await fixture({close:false,handshake,frameError:true});let diagnostic;
    await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,onFailure:value=>{diagnostic=value;}}),/settlement unproved/);
    assert.deepEqual(diagnostic.websocketLifecycle,{created:1,handshake101:handshake===101?1:0,handshakeNon101:handshake===101?0:1,frameError:1,closed:0});
    assert.equal(JSON.stringify(diagnostic).includes('never-retain'),false);
    assert.throws(()=>validateCase1Failure({...diagnostic,websocketLifecycle:{...diagnostic.websocketLifecycle,headers:'never-retain'}}));
    assert.throws(()=>validateCase1Failure({...diagnostic,websocketLifecycle:{...diagnostic.websocketLifecycle,closed:17}}));
  }
  for(const response of [null,{status:'101'},{status:99},{status:600}]){
    const f=await fixture();f.session.emit('Network.webSocketHandshakeResponseReceived',{requestId:'known',response});
    assert.equal(case1ObservationReady(f.cdp),false);
  }
  for(const name of ['Network.webSocketFrameError','Network.webSocketClosed','Network.webSocketHandshakeResponseReceived']){
    const f=await fixture();f.session.emit(name,{requestId:'x'.repeat(129),response:{status:101},errorMessage:'never-retain'});
    assert.equal(case1ObservationReady(f.cdp),false);
  }
  const f=await fixture({handshake:101,authCatalog:true});let diagnostic;
  await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,onFailure:value=>{diagnostic=value;}}),/settlement unproved/);
  assert.equal(diagnostic.websocketLifecycle.closed,1);
  assert.equal(diagnostic.oldWebSockets,0);
});
test('diagnostic DOM unavailability is null and never changes original failure',async()=>{
  const f=await fixture({close:false}),evaluate=f.driver.evaluate;let diagnostic;
  f.driver.evaluate=async(expression,...args)=>{if(expression.includes('footerVersionMatches:'))throw new Error('unavailable');return evaluate(expression,...args);};
  await assert.rejects(()=>runPublicAuthCase({driver:f.driver,cdp:f.cdp,publicPolicyEnabled:true,onFailure:value=>{diagnostic=value;}}),/settlement unproved/);
  assert.equal(diagnostic.dom,null);
});

test('copied pipeline preserves original events and explicitly enables required CDP domains',async()=>{
  const f=await fixture();assert.equal(case1ObservationReady(f.cdp),true);
  assert.deepEqual(f.calls,['Page.enable','Network.enable']);
  for(const event of observedNetworkEvents)assert.equal(f.session.listenerCount(event),1);
  for(const event of ['Page.frameNavigated','Network.webSocketCreated','Network.webSocketClosed'])assert.equal(f.session.listenerCount(event),1);
  const result=await execute(f);assert.ok(f.forwarded>0);
  assert.equal(result.authDocumentCatalogRpcCount,0);
  assert.equal(result.authCatalogObservationWindow,'login-document-start-to-first-enabled-login-form-checkpoint');
  assert.equal(result.anonymousAdminSubscriptionAbsenceProven,false);
  assert.equal(result.sameDocumentReactCancellationProven,false);
  assert.equal(result.fullIssue154AcceptanceProven,false);
  assert.deepEqual(validateCase1Result(result),result);
});
test('missing binding or missing domain enable cannot yield absence proof',async()=>{
  for(const options of [{bound:false},{enabled:false}]){const f=await fixture(options);await assert.rejects(()=>execute(f),/Fresh original driver/);}
});
test('missing frame forwarding and an unclosed real-observed socket fail closed',async()=>{
  for(const options of [{frame:false},{close:false}]){const f=await fixture(options);await assert.rejects(()=>execute(f),/settlement unproved/);}
});
test('actual admin HTTP methods and late catalog calls are rejected',async()=>{
  for(const admin of ['/connect/UserEndpoint/getAllUsers','/connect/BandwidthMonitoringEndpoint/getActiveSessions','/connect/LibraryEndpoint/subscribeToScanProgressEvents']){
    const f=await fixture({admin});await assert.rejects(()=>execute(f),/Anonymous public identity/);
  }
  const f=await fixture({authCatalog:true});await assert.rejects(()=>execute(f),/settlement unproved/);
});
test('additional-event cap overrun is a failure, not silent zero activity',async()=>{
  const f=await fixture({overflow:true});await assert.rejects(()=>execute(f),/Anonymous public identity/);
});
test('observation cap never drops original interception ownership events',()=>{
  const session=new EventEmitter(), forwarded=[],cdp={onEvent:(method)=>forwarded.push(method)};
  bindCase1ObservedEvents(session,cdp);
  for(let i=0;i<257;i++)session.emit('Page.frameNavigated',{frame:{url:'http://127.0.0.1:55203/login'}});
  session.emit('Fetch.requestPaused',{});
  assert.equal(forwarded.filter(method=>method==='Fixture.observationOverflow').length,1);
  assert.equal(forwarded.at(-1),'Fetch.requestPaused');
});
test('missing original cover proof cannot become a successful public receipt',async()=>{
  const f=await fixture({badCover:true});await assert.rejects(()=>execute(f),/Original cover identity/);
});
test('actual case1 mode and receipt fields fail closed on wider claims or unknown data',async()=>{
  assert.deepEqual(parseCase1Arguments(['172.18.0.2','42','a'.repeat(64),'--public-auth-case1']),['172.18.0.2','42','a'.repeat(64)]);
  for(const args of [[],['x','y','z'],['x','y','z','--ui-rounds=3'],['x','y','z','--public-auth-case1','extra']])assert.throws(()=>parseCase1Arguments(args));
  const f=await fixture(),result=await execute(f);
  for(const changed of [{fullIssue154AcceptanceProven:true},{anonymousAdminSubscriptionAbsenceProven:true},{naturalPendingCatalogAtSignIn:129},{authDocumentCatalogRpcCount:1},{uiVersionVerified:false},{privateDto:'forbidden'},{authCatalogObservationWindow:'forever'}])assert.throws(()=>validateCase1Result({...result,...changed}));
});
test('observation failure before case installation remains permanently failed',async()=>{
  const f=await fixture();
  for(let i=0;i<257;i++)f.session.emit('Page.frameNavigated',{frame:{url:'http://127.0.0.1:55203/login'}});
  assert.equal(case1ObservationReady(f.cdp),false);
  await assert.rejects(()=>execute(f),/Fresh original driver/);
});
