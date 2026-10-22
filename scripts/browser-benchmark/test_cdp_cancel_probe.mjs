import test from 'node:test';import assert from 'node:assert/strict';import {EventEmitter} from 'node:events';
import {probeCase,cleanupBrowser} from './cdp_cancel_probe.mjs';
const origin='http://127.0.0.1:55201';
function sessionFixture({cancel=true,error=true}={}){
 const session=new EventEmitter();session.detached=false;session.calls=[];
 const emit=(name,p)=>session.emit(name,p);
 session.send=async(method,params={})=>{session.calls.push([method,params]);
  if(method==='Page.navigate'){
   const next=params.url.endsWith('/b');emit('Network.requestWillBeSent',{requestId:next?'docB':'docA',frameId:'PRIVATE-FRAME',loaderId:next?'loaderB':'loaderA',type:'Document',request:{url:params.url,method:'GET'}});
   emit('Fetch.requestPaused',{requestId:next?'fetchB':'fetchA',networkId:next?'docB':'docA',frameId:'PRIVATE-FRAME',resourceType:'Document',request:{url:params.url,method:'GET'}});
   if(next&&cancel)emit('Network.loadingFailed',{requestId:'image',type:'Image',canceled:true});emit('Page.loadEventFired',{});
  }
  if(method==='Runtime.evaluate'&&params.expression.includes('createElement')){const request={url:origin+'/images/cover/1',method:'GET'};emit('Network.requestWillBeSent',{requestId:'image',frameId:'PRIVATE-FRAME',loaderId:'loaderA',type:'Image',request});emit('Fetch.requestPaused',{requestId:'held',networkId:'image',frameId:'PRIVATE-FRAME',resourceType:'Image',request});}
  if(method==='Runtime.evaluate'&&params.expression.includes('i.remove')&&cancel)emit('Network.loadingFailed',{requestId:'image',type:'Image',canceled:true});
  if(method==='Fetch.continueRequest'&&params.requestId==='held'&&error)throw new Error('Invalid InterceptionId. PRIVATE_COOKIE=secret');return {};
 };
 session.detach=async()=>{session.detached=true;};return session;
}
for(const kind of ['current-document','old-loader'])test('real transport/ledger protocol plumbing '+kind,async()=>{
 const session=sessionFixture(),row=await probeCase(session,kind);assert.equal(row.complete,true);assert.equal(row.terminalCancellationAccepted,kind==='current-document');assert.equal(row.eligibility.reason,kind==='current-document'?'eligible':'loader-mismatch');assert.equal(row.continueCommands,1);assert.equal(session.calls.filter(([m,p])=>m==='Fetch.continueRequest'&&p.requestId==='held').length,1);assert.equal(session.detached,true);assert.equal(session.eventNames().length,0);assert(!JSON.stringify(row).match(/PRIVATE|secret|http:|loaderA|image\//));
});
test('missing actual cancel event is failure, not synthetic protocol success or retry',async()=>{const session=sessionFixture({cancel:false});await assert.rejects(probeCase(session,'current-document',{limitMs:5}),/deadline/);assert.equal(session.detached,true);assert.equal(session.eventNames().length,0);assert.equal(session.calls.filter(([m,p])=>m==='Fetch.continueRequest'&&p.requestId==='held').length,0);});
test('Chrome continuing successfully is not falsely reported as reproduced invalid-ID race',async()=>{const row=await probeCase(sessionFixture({error:false}),'current-document');assert.equal(row.complete,false);assert.equal(row.category,'none');assert.equal(row.terminalCancellationAccepted,false);});
test('unknown detach terminal cannot return a successful protocol receipt',async()=>{const session=sessionFixture();session.detach=()=>new Promise(()=>{});await assert.rejects(probeCase(session,'current-document',{limitMs:20}),/deadline/);assert.equal(session.eventNames().length,0);});
test('cleanup proves retained browser exit and private profile removal; forced/unknown outcomes veto completion',async()=>{
 const missing=Object.assign(new Error('private'),{code:'ENOENT'}),access=async()=>{throw missing;},remove=async()=>{};
 const child=Object.assign(new EventEmitter(),{exitCode:0,signalCode:null});const browser={process:()=>child,close:async()=>{}};
 assert.deepEqual(await cleanupBrowser(browser,'/tmp/synthetic',{remove,access}),{browserClosed:true,profileRemoved:true,forcedTermination:false,complete:true});
 assert.equal((await cleanupBrowser(browser,'/tmp/synthetic',{remove:async()=>{throw new Error('PRIVATE');},access})).complete,false);
 const stuck=Object.assign(new EventEmitter(),{exitCode:null,signalCode:null,kill(){this.signalCode='SIGKILL';queueMicrotask(()=>this.emit('exit'));}});const forced=await cleanupBrowser({process:()=>stuck,close:()=>new Promise(()=>{})},'/tmp/synthetic',{remove,access,closeMs:1});assert.equal(forced.browserClosed,true);assert.equal(forced.forcedTermination,true);assert.equal(forced.complete,false);
 await assert.rejects(cleanupBrowser(browser,null,{closeMs:4001}),/boundary/);
});
