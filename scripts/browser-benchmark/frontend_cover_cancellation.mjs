import {invalidContinueContext} from './frontend_cdp_failures.mjs';
const boundedId=value=>typeof value==='string'&&value.length>0&&value.length<=128;
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
   this.network.set(params.requestId,{id:params.requestId,hop:(previous?.hop||0)+1,frame:params.frameId,loader:params.loaderId,generation:frame?.generation,url:params.request.url,eligible:!previous&&params.type==='Image'&&this.exactCover(params.request),canceled:null,terminal:null,ambiguous:false});
  }else if(['Network.loadingFinished','Network.loadingFailed'].includes(method)){
   const current=this.network.get(params.requestId);if(!current)return;
   if(current.terminal!==null){current.ambiguous=true;return;}
   current.terminal=method;
   if(current.eligible&&method==='Network.loadingFailed'&&params.canceled===true&&params.type==='Image')current.canceled=sequence;
  }
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
