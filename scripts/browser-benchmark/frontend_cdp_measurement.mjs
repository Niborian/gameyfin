// #27/#61 diagnostic building blocks only. No browser, SSH or CDP connection is launched.
import {createHash} from 'node:crypto';
export const RUNTIME_ENABLED=false;
const METHODS=new Set(['Page.enable','Network.enable','Runtime.enable','Page.navigate','Runtime.evaluate',
  'Network.getResponseBody','Fetch.enable','Fetch.continueRequest','Fetch.failRequest','Page.addScriptToEvaluateOnNewDocument']);
export class BoundedCdp {
  constructor(transport,timeoutMs=15000){
    if(!Number.isInteger(timeoutMs)||timeoutMs<1||timeoutMs>15000)throw new Error('Deadline boundary');
    this.transport=transport;this.timeoutMs=timeoutMs;this.next=0;this.pending=new Map();this.closed=false;this.onEvent=null;
    transport.onMessage=message=>this.receive(message);
  }
  receive(raw){
    let message;try{if(typeof raw!=='string'||raw.length>3*1024*1024)throw new Error();message=JSON.parse(raw);}catch{this.close();return;}
    if(message.method&&this.onEvent){this.onEvent(message.method,message.params||{});return;}
    const pending=this.pending.get(message.id);if(!pending)return;
    clearTimeout(pending.timer);this.pending.delete(message.id);
    if(message.error)pending.reject(new Error('CDP operation failed'));else pending.resolve(message.result||{});
  }
  request(method,params={},deadlineMs=this.timeoutMs){
    if(this.closed||this.pending.size>=8||!METHODS.has(method))return Promise.reject(new Error('CDP method boundary'));
    if(!Number.isInteger(deadlineMs)||deadlineMs<1||deadlineMs>this.timeoutMs)return Promise.reject(new Error('CDP deadline boundary'));
    const id=++this.next;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('CDP deadline'));this.close();},deadlineMs);
      this.pending.set(id,{resolve,reject,timer});
      try{this.transport.send(JSON.stringify({id,method,params}));}catch{clearTimeout(timer);this.pending.delete(id);reject(new Error('CDP transport failed'));this.close();}
    });
  }
  close(){if(this.closed)return;this.closed=true;for(const pending of this.pending.values()){clearTimeout(pending.timer);pending.reject(new Error('CDP closed'));}this.pending.clear();this.transport.close();}
}
export function allowRequest(url,origin){
  const trusted=new URL(origin);
  if(trusted.protocol!=='http:'||trusted.hostname!=='127.0.0.1'||!trusted.port||trusted.username||trusted.password)throw new Error('Origin boundary');
  try{const value=new URL(url);if(value.username||value.password)return false;return value.origin===trusted.origin||value.protocol==='data:'||value.protocol==='blob:'&&value.origin===trusted.origin||value.protocol==='ws:'&&value.host===trusted.host;}catch{return false;}
}
export function loginExpression(username,password){
  if(typeof username!=='string'||typeof password!=='string'||username.length<1||username.length>128||password.length<16||password.length>256)throw new Error('Private credential boundary');
  // Returned CDP payload stays in process memory. Never serialize it to evidence/logs.
  return `(()=>{const u=document.querySelector('input[name="username"]');const p=document.querySelector('input[name="password"]');if(!u||!p||location.pathname!=='/login')return false;const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;for(const [e,v] of [[u,${JSON.stringify(username)}],[p,${JSON.stringify(password)}]]){s.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}const b=u.closest('form')?.querySelector('button[type="submit"]');if(!b)return false;b.click();return true;})()`;
}
export function routeExpression(path,coverId){
  if(!['/library/27003','/game/33000'].includes(path)||!Number.isSafeInteger(coverId)||coverId<1)throw new Error('Fixture route boundary');
  // Actual source: GameCover img alt=game.title, library27003 contains link /game/33000.
  // Placeholder data/blurhash images never satisfy exact same-origin cover path.
  return `(async()=>{if(location.pathname!==${JSON.stringify(path)})return {ready:false};const titles=['Synthetic game 33000','Enriched fixture game 33000'];const contentReady=${path.startsWith('/game')?"Array.from(document.querySelectorAll('p')).some(p=>titles.includes(p.textContent.trim()))":"!!document.querySelector('a[href=\"/game/33000\"]')"};const image=Array.from(document.images).find(i=>titles.includes(i.alt)&&new URL(i.currentSrc||i.src,location.href).origin===location.origin&&new URL(i.currentSrc||i.src,location.href).pathname==='/images/cover/${coverId}');if(!contentReady||!image||!image.complete||image.naturalWidth!==320||image.naturalHeight!==480)return {ready:false};const ready=performance.now();await image.decode();const n=performance.getEntriesByType('navigation')[0];return {ready:true,domContentLoadedMs:n?.domContentLoadedEventEnd||0,loadMs:n?.loadEventEnd||0,routeContentReadyMs:ready,coverDecodedMs:performance.now(),coverNaturalWidth:image.naturalWidth,coverNaturalHeight:image.naturalHeight};})()`;
}
const NUMBERS=new Set(['domContentLoadedMs','loadMs','routeContentReadyMs','coverDecodedMs','coverNaturalWidth','coverNaturalHeight','coverResponseStatus','encodedDataLength','resourceDurationMs']);
const BOOLEANS=new Set(['coverSha256Matches','fromDiskCache','fromServiceWorker']);
export function sanitizeMetrics(value){
  if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('Metric schema boundary');
  const result={};
  for(const [key,item] of Object.entries(value)){
    if(NUMBERS.has(key)&&typeof item==='number'&&Number.isFinite(item)&&item>=0&&item<=1e9)result[key]=item;
    else if(BOOLEANS.has(key)&&typeof item==='boolean')result[key]=item;
    else throw new Error('Metric schema boundary');
  }
  return result;
}
export function coverHashMatches(body,base64Encoded,expectedSha){
  if(typeof body!=='string'||body.length>2*1024*1024||!base64Encoded||!/^[a-f0-9]{64}$/.test(expectedSha))throw new Error('Cover body boundary');
  const bytes=Buffer.from(body,'base64');
  return bytes.length===371528&&createHash('sha256').update(bytes).digest('hex')===expectedSha;
}
export function launchRuntime(){throw new Error('Runtime disabled pending root review');}
