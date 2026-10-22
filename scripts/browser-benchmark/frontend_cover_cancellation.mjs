import {invalidContinueContext} from './frontend_cdp_failures.mjs';
const boundedId=value=>typeof value==='string'&&value.length>0&&value.length<=128;
export const eligibilityReasons=Object.freeze(['eligible','policy-denied','duplicate','not-image','not-get','not-exact-cover','network-missing','metadata-missing','frame-mismatch','loader-mismatch','generation-mismatch','hop-replaced','ambiguous','typed-binding-missing','cancel-not-before','pause-ineligible','truncated']);
export function eligibilityDiagnostic(value){
 const enums={resourceType:['Document','Image','Script','Stylesheet','Font','Fetch','XHR','Other','unknown'],method:['GET','POST','HEAD','other','unknown'],target:['exact-cover','same-origin-other','foreign','invalid'],reason:eligibilityReasons};
 const bools=['permitted','pauseEligible','metadataPresent','duplicate','truncated','typedBinding'];const nullable=['frameMatches','loaderMatches','generationMatches','currentHop','reused','ambiguous','canceledBeforeFailure'];const keys=[...Object.keys(enums),...bools,...nullable];
 if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||!keys.every(k=>Object.hasOwn(value,k))||!Object.entries(enums).every(([k,v])=>v.includes(value[k]))||!bools.every(k=>typeof value[k]==='boolean')||!nullable.every(k=>value[k]===null||typeof value[k]==='boolean'))throw new Error('Eligibility diagnostic boundary');
 return Object.fromEntries(keys.map(k=>[k,value[k]]));
}
/** Conservative current-document/hop ledger. IDs/URLs remain bounded process memory. */
export class CoverCancellationLedger {
 constructor(origin,coverId){this.origin=origin;this.path='/images/cover/'+coverId;this.network=new Map();this.frames=new Map();this.duplicates=new Set();this.stamps=new WeakMap();this.sequence=0;this.generation=0;this.truncated=false;}
 tick(){if(this.sequence>=4096)this.truncated=true;return ++this.sequence;}
 navigationStarted(){this.tick();this.frames.clear();}
 stamp(){const token=Object.freeze({});this.stamps.set(token,this.tick());return token;}
 exactCover(request){try{const u=new URL(request?.url);return request.method==='GET'&&u.origin===this.origin&&u.pathname===this.path&&!u.search&&!u.hash&&!u.username&&!u.password;}catch{return false;}}
 observe(method,params){const sequence=this.tick();
  if(method==='Network.requestWillBeSent'){
   if(!['Document','Image'].includes(params.type)&&!this.network.has(params.requestId))return;
   if(!boundedId(params.requestId)||!boundedId(params.frameId)||!boundedId(params.loaderId)||typeof params.request?.url!=='string'||params.request.url.length>4096){this.truncated=true;return;}
   if(!this.network.has(params.requestId)&&this.network.size>=256){this.truncated=true;return;}
   if(params.type==='Document'){
    if(!this.frames.has(params.frameId)&&this.frames.size>=256){this.truncated=true;return;}
    this.frames.set(params.frameId,{loader:params.loaderId,generation:++this.generation});
   }
   const previous=this.network.get(params.requestId),frame=this.frames.get(params.frameId);
   // LoadingFailed has no hop identity: any reused network ID is ambiguous.
   this.network.set(params.requestId,{id:params.requestId,hop:(previous?.hop||0)+1,frame:params.frameId,loader:params.loaderId,generation:frame?.generation,url:params.request.url,eligible:!previous&&params.type==='Image'&&this.exactCover(params.request),canceled:null,diagnosticCanceled:null,terminal:null,ambiguous:false});
  }else if(['Network.loadingFinished','Network.loadingFailed'].includes(method)){
   const current=this.network.get(params.requestId);if(!current)return;
   if(current.terminal!==null){current.ambiguous=true;return;}
   current.terminal=method;
   if(method==='Network.loadingFailed'&&params.canceled===true)current.diagnosticCanceled=sequence;
   if(current.eligible&&method==='Network.loadingFailed'&&params.canceled===true&&params.type==='Image')current.canceled=sequence;
  }
 }
 assessment(params,permitted,duplicate,pauseEligible){
  let target='invalid';try{const u=new URL(params.request?.url);target=u.origin===this.origin?(this.exactCover({...params.request,method:'GET'})?'exact-cover':'same-origin-other'):'foreign';}catch{}
  return {fetchId:boundedId(params.requestId)?params.requestId:null,networkId:boundedId(params.networkId)?params.networkId:null,frameId:boundedId(params.frameId)?params.frameId:null,current:this.network.get(params.networkId)||null,resourceType:['Document','Image','Script','Stylesheet','Font','Fetch','XHR','Other'].includes(params.resourceType)?params.resourceType:'unknown',method:['GET','POST','HEAD'].includes(params.request?.method)?params.request.method:typeof params.request?.method==='string'?'other':'unknown',target,permitted,pauseEligible,duplicate,urlMatches:this.network.get(params.networkId)?.url===params.request?.url};
 }
 diagnose(snapshot,error,diagnosticTruncated){
  const s=snapshot,current=s.current,frame=current&&this.frames.get(current.frame),token=invalidContinueContext(error,s.fetchId),sequence=token&&this.stamps.get(token);
  const row={resourceType:s.resourceType,method:s.method,target:s.target,reason:'eligible',permitted:s.permitted,metadataPresent:!!(s.fetchId&&s.networkId&&s.frameId&&current&&boundedId(current.loader)),duplicate:s.duplicate||this.duplicates.has(s.fetchId),truncated:this.truncated||diagnosticTruncated,typedBinding:!!sequence,frameMatches:current?!!s.frameId&&s.frameId===current.frame:null,loaderMatches:current?!!frame&&frame.loader===current.loader:null,generationMatches:current?!!frame&&frame.generation===current.generation:null,currentHop:current?this.network.get(s.networkId)===current:null,reused:current?current.hop>1:null,ambiguous:current?current.ambiguous:null,canceledBeforeFailure:sequence&&Number.isInteger(current?.diagnosticCanceled)?current.diagnosticCanceled<sequence:null};
  row.reason=row.truncated?'truncated':!s.permitted?'policy-denied':row.duplicate?'duplicate':s.resourceType!=='Image'?'not-image':s.method!=='GET'?'not-get':s.target!=='exact-cover'?'not-exact-cover':!current?'network-missing':!row.metadataPresent?'metadata-missing':!row.frameMatches?'frame-mismatch':!row.loaderMatches?'loader-mismatch':!row.generationMatches?'generation-mismatch':!row.currentHop||row.reused||!s.urlMatches?'hop-replaced':row.ambiguous?'ambiguous':!row.typedBinding?'typed-binding-missing':row.canceledBeforeFailure!==true?'cancel-not-before':!current.eligible?'not-exact-cover':'eligible';
  row.pauseEligible=s.pauseEligible;if(row.reason==='eligible'&&(!s.pauseEligible||!Number.isInteger(current?.canceled)))row.reason='pause-ineligible';return eligibilityDiagnostic(row);
 }
 pause(params,permitted,duplicate){
  this.tick();if(duplicate){if(this.duplicates.size>=256)this.truncated=true;else this.duplicates.add(params.requestId);}
  const current=this.network.get(params.networkId),frame=current&&this.frames.get(current.frame);
  if(!permitted||duplicate||!boundedId(params.requestId)||!boundedId(params.networkId)||params.resourceType!=='Image'||!this.exactCover(params.request)||!current?.eligible||current.url!==params.request.url||params.frameId!==current.frame||!frame||frame.loader!==current.loader||frame.generation!==current.generation)return null;
  return {fetchId:params.requestId,current};
 }
 terminal(record,error,diagnosticTruncated){
  const token=invalidContinueContext(error,record?.fetchId),errorSequence=token&&this.stamps.get(token);
  if(!record||!errorSequence||this.truncated||diagnosticTruncated||this.duplicates.has(record.fetchId))return false;
  const current=this.network.get(record.current.id),frame=this.frames.get(record.current.frame);
  return current===record.current&&!current.ambiguous&&frame?.loader===current.loader&&frame.generation===current.generation&&Number.isInteger(current.canceled)&&current.canceled<errorSequence;
 }
}
