import test from 'node:test';import assert from 'node:assert/strict';
import {FixtureUiDriver,safeAuthDiagnostics} from './frontend_ui_driver.mjs';
import {createCdpTransport,recordFirstAuthCdpFailure,invalidContinueContext} from './frontend_cdp_failures.mjs';
const origin='http://127.0.0.1:53123',config={origin,coverId:7,coverSha256:'0'.repeat(64)};
async function scenario(options={}){
 const calls=[],fatal=[];let driver,reject;
 const session={send:(method,params)=>{calls.push({method,params});return new Promise((_,r)=>{reject=r;});}};
 const transport=createCdpTransport(session,{captureInterception:()=>driver.captureInterceptionFailure(),onFatal:(method,error)=>{fatal.push(method);recordFirstAuthCdpFailure(driver.authDiagnostics,method,error);}});
 const cdp={request:options.untyped?((method,params)=>session.send(method,params)):options.timeout?((method,params)=>transport(method,params,1)):options.wrongTypedMethod?((_method,params)=>transport('Fetch.failRequest',params)):options.wrongTypedId?((method,params)=>transport(method,{...params,requestId:'OTHER-FETCH'})):transport};
 driver=new FixtureUiDriver(cdp,config);
 const request={url:options.url||origin+'/images/cover/7',method:options.method||'GET'};
 if(!options.noDocument)driver.event('Network.requestWillBeSent',{requestId:'PRIVATE-DOCUMENT',frameId:'PRIVATE-FRAME',loaderId:'PRIVATE-LOADER',type:'Document',request:{url:origin+'/game/33000',method:'GET'}});
 const network={requestId:'PRIVATE-NETWORK',frameId:'PRIVATE-FRAME',loaderId:'PRIVATE-LOADER',type:options.type||'Image',request};
 if(!options.noNetwork)driver.event('Network.requestWillBeSent',{...network,...options.network});
 const paused={requestId:'PRIVATE-FETCH',networkId:'PRIVATE-NETWORK',frameId:'PRIVATE-FRAME',resourceType:options.type||'Image',request,...options.paused};
 driver.event('Fetch.requestPaused',paused);
 if(options.finishedBefore)driver.event('Network.loadingFinished',{requestId:'PRIVATE-NETWORK'});
 const cancel=()=>driver.event('Network.loadingFailed',{requestId:options.unrelated?'UNRELATED':'PRIVATE-NETWORK',type:options.cancelType||options.type||'Image',canceled:options.canceled!==false});
 if(!options.noCancel&&!options.late)cancel();
 if(options.reuse)driver.event('Network.requestWillBeSent',{...network,redirectResponse:{status:302}});
 if(options.newDocument)driver.event('Network.requestWillBeSent',{requestId:'NEW-DOCUMENT',frameId:'PRIVATE-FRAME',loaderId:'NEW-LOADER',type:'Document',request:{url:origin+'/settings/profile',method:'GET'}});
 if(options.navigation)driver.coverCancellation.navigationStarted();
 if(options.duplicate)driver.coverCancellation.duplicates.add('PRIVATE-FETCH');
 if(options.truncated)driver.authDiagnostics.interceptionDiagnostics.truncated=true;
 if(options.overflow){for(let i=0;i<257;i++)driver.event('Network.requestWillBeSent',{...network,requestId:'overflow-'+i});}
 if(!options.timeout){reject(Object.assign(new Error(options.error||'Invalid InterceptionId. PRIVATE_COOKIE=secret'),{category:'interception-invalid-id'}));if(options.late)queueMicrotask(cancel);}
 await new Promise(resolve=>setTimeout(resolve,options.timeout?10:0));
 assert.equal(calls.length,1,'One command; never retry, delay, or disable interception');
 assert.equal(calls[0].method,options.wrongTypedMethod||!request.url.startsWith(origin)?'Fetch.failRequest':'Fetch.continueRequest');
 assert(!JSON.stringify(safeAuthDiagnostics(driver.authDiagnostics)).match(/PRIVATE|secret|http:|images\/cover/));
 return {driver,calls,fatal,session};
}
test('real transport boundary accepts only exact already-canceled current cover ImageGET terminal state',async()=>{
 const {driver,fatal,calls}=await scenario();assert.equal(driver.failure,false);assert.deepEqual(fatal,[]);
 assert.equal(driver.authDiagnostics.interceptionDiagnostics.verifiedCanceledImageContinuations,1);assert.equal(driver.authDiagnostics.cdpFailure,'none');
 driver.totalDeadline=performance.now()-1;await assert.rejects(driver.navigate('/game/33000'),/deadline/);assert.equal(calls.length,1,'Cancellation does not satisfy the unchanged content/decode deadline');
});
for(const [name,options] of Object.entries({
 Document:{type:'Document'},Script:{type:'Script'},XHR:{type:'XHR'},Fetch:{type:'Fetch'},
 login:{type:'Document',url:origin+'/login',method:'POST'},profileRpc:{type:'Fetch',url:origin+'/connect/UserEndpoint/getUserInfo',method:'POST'},
 deniedExternal:{url:'https://external.invalid/images/cover/7'},post:{method:'POST'},head:{method:'HEAD'},
 otherCover:{url:origin+'/images/cover/8'},query:{url:origin+'/images/cover/7?x=1'},fragment:{url:origin+'/images/cover/7#x'},
 noDocument:{noDocument:true},noNetwork:{noNetwork:true},missingFrame:{paused:{frameId:undefined}},missingNetworkId:{paused:{networkId:undefined}},
 wrongFrame:{paused:{frameId:'OTHER'}},wrongLoader:{network:{loaderId:'OTHER'}},missingLoader:{network:{loaderId:undefined}},wrongUrl:{network:{request:{url:origin+'/images/cover/8',method:'GET'}}},
 unrelated:{unrelated:true},noCancel:{noCancel:true},notCanceled:{canceled:false},wrongCancelType:{cancelType:'Script'},lateCancellation:{late:true},
 reusedNetworkHop:{reuse:true},newDocumentGeneration:{newDocument:true},explicitNavigation:{navigation:true},duplicateFetch:{duplicate:true},truncated:{truncated:true},overflow:{overflow:true},
 alreadyFinished:{finishedBefore:true},wrongTypedMethod:{wrongTypedMethod:true},wrongTypedId:{wrongTypedId:true},forgedCategory:{untyped:true,error:'Sanitized failure'},timeout:{timeout:true},otherError:{error:'Target closed PRIVATE'},
}))test('one-attempt fail-closed negative '+name,async()=>{const {driver}=await scenario(options);assert.equal(driver.failure,true);assert.equal(driver.authDiagnostics.interceptionDiagnostics.verifiedCanceledImageContinuations,0);assert.notEqual(driver.authDiagnostics.cdpFailure,'none');});
test('handled terminal cancellation never masks a later fatal transport cause',async()=>{
 const {driver,session,fatal}=await scenario();session.send=async()=>{throw new Error('Target closed PRIVATE');};
 await assert.rejects(driver.evaluate('fixed safe expression'),/Private CDP operation failed/);
 assert.equal(driver.authDiagnostics.cdpFailure,'target-closed');assert.equal(driver.authDiagnostics.cdpMethod,'runtime-evaluate');assert.deepEqual(fatal,['Runtime.evaluate']);
});

test('actual duplicate paused events invalidate both records without adding retries',async()=>{
 const pending=[],calls=[];let driver;
 const request=createCdpTransport({send:(method,params)=>{calls.push({method,params});return new Promise((_,reject)=>pending.push(reject));}},{captureInterception:()=>driver.captureInterceptionFailure()});
 driver=new FixtureUiDriver({request},config);
 driver.event('Network.requestWillBeSent',{requestId:'doc',frameId:'frame',loaderId:'loader',type:'Document',request:{url:origin+'/',method:'GET'}});
 const image={url:origin+'/images/cover/7',method:'GET'};
 driver.event('Network.requestWillBeSent',{requestId:'net',frameId:'frame',loaderId:'loader',type:'Image',request:image});
 const pause={requestId:'fetch',networkId:'net',frameId:'frame',resourceType:'Image',request:image};
 driver.event('Fetch.requestPaused',pause);driver.event('Fetch.requestPaused',pause);
 driver.event('Network.loadingFailed',{requestId:'net',type:'Image',canceled:true});
 for(const reject of pending)reject(new Error('Invalid InterceptionId.'));
 await new Promise(resolve=>setImmediate(resolve));assert.equal(driver.failure,true);assert.equal(calls.length,2);assert(calls.every(v=>v.method==='Fetch.continueRequest'));
 assert.equal(driver.authDiagnostics.interceptionDiagnostics.duplicatePausedIds,1);assert.equal(driver.authDiagnostics.interceptionDiagnostics.verifiedCanceledImageContinuations,0);
});

test('real driver navigation invalidates older cover generation before new document events',async()=>{
 let reject,driver;const calls=[];
 const request=createCdpTransport({send:(method,params)=>{calls.push(method);return method==='Page.navigate'?Promise.resolve({}):new Promise((_,r)=>{reject=r;});}},{captureInterception:()=>driver.captureInterceptionFailure()});
 driver=new FixtureUiDriver({request},config);
 driver.event('Network.requestWillBeSent',{requestId:'doc',frameId:'frame',loaderId:'loader',type:'Document',request:{url:origin+'/',method:'GET'}});
 const image={url:origin+'/images/cover/7',method:'GET'};
 driver.event('Network.requestWillBeSent',{requestId:'net',frameId:'frame',loaderId:'loader',type:'Image',request:image});
 driver.event('Fetch.requestPaused',{requestId:'fetch',networkId:'net',frameId:'frame',resourceType:'Image',request:image});
 await driver.request('Page.navigate',{url:origin+'/settings/profile'});
 driver.event('Network.loadingFailed',{requestId:'net',type:'Image',canceled:true});reject(new Error('Invalid InterceptionId.'));
 await new Promise(resolve=>setImmediate(resolve));assert.equal(driver.failure,true);assert.deepEqual(calls,['Fetch.continueRequest','Page.navigate']);assert.equal(driver.authDiagnostics.interceptionDiagnostics.verifiedCanceledImageContinuations,0);
});

test('real transport binds FetchID at invocation even if caller mutates params before failure',async()=>{
 let reject;const token=Object.freeze({});let sent;
 const request=createCdpTransport({send:(_method,params)=>{sent=params.requestId;return new Promise((_,r)=>{reject=r;});}},{captureInterception:()=>token});
 const params={requestId:'ORIGINAL-FETCH'};const pending=request('Fetch.continueRequest',params);params.requestId='MUTATED-FETCH';
 reject(new Error('Invalid InterceptionId. PRIVATE'));
 let error;try{await pending;}catch(value){error=value;}
 assert.equal(sent,'ORIGINAL-FETCH');assert.equal(invalidContinueContext(error,'ORIGINAL-FETCH'),token);assert.equal(invalidContinueContext(error,'MUTATED-FETCH'),null);
 assert(!JSON.stringify(error).includes('PRIVATE'));
});
