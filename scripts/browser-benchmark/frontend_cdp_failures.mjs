// Raw CDP errors terminate here. Branding/context never enters public receipts.
const failures=new WeakMap();
const interception=new Set(['Fetch.continueRequest','Fetch.failRequest']);
export function classifyCdpFailure(error,method=null){
 const branded=failures.get(error);if(branded)return branded.method===method?branded.category:'other';
 const text=typeof error?.message==='string'?error.message.slice(0,8192):'';
 if(interception.has(method)&&/(?:^|\s)Invalid InterceptionId\.(?:\s|$)/.test(text))return 'interception-invalid-id';
 if(/Execution context was destroyed/.test(text))return 'execution-context-destroyed';
 if(/Cannot find context with (?:specified )?id|Cannot find execution context/.test(text))return 'execution-context-missing';
 if(/Target closed|Session closed|Connection closed/.test(text))return 'target-closed';
 if(/timed out|timeout|deadline/i.test(text))return 'protocol-timeout';return 'other';
}
export function recordFirstAuthCdpFailure(diagnostics,method,error){
 if(diagnostics.cdpFailure!=='none')return;
 diagnostics.cdpFailure=classifyCdpFailure(error,method);
 diagnostics.cdpMethod=method==='Page.navigate'?'page-navigation':method==='Runtime.evaluate'?'runtime-evaluate':method==='Input.insertText'?'input-insert':interception.has(method)?'request-interception':['Page.enable','Runtime.enable','Network.enable','Fetch.enable','Page.addScriptToEvaluateOnNewDocument'].includes(method)?'setup':'other';
}
export function invalidContinueContext(error,fetchId){const value=failures.get(error);return typeof fetchId==='string'&&value?.fetchId===fetchId&&value.method==='Fetch.continueRequest'&&value.category==='interception-invalid-id'?value.context:null;}
export function createCdpTransport(session,{onFatal=()=>{},captureInterception=()=>null}={}){
 return (method,params={},timeoutMs=15000)=>new Promise((resolve,reject)=>{
  let settled=false;
  const fetchId=typeof params.requestId==='string'&&params.requestId.length>0&&params.requestId.length<=128?params.requestId:null;
  const fail=raw=>{if(settled)return;settled=true;clearTimeout(timer);
   // Capture ordering BEFORE rejection, not in the later driver promise callback.
   const context=interception.has(method)?captureInterception():null;
   const error=new Error('Private CDP operation failed');failures.set(error,Object.freeze({method,category:classifyCdpFailure(raw,method),context,fetchId}));
   if(!interception.has(method))onFatal(method,error);reject(error);
  };
  const timer=setTimeout(()=>fail(new Error('Private CDP deadline')),Math.min(15000,timeoutMs));
  try{Promise.resolve(session.send(method,params)).then(value=>{if(settled)return;settled=true;clearTimeout(timer);resolve(value);},fail);}catch(error){fail(error);}
 });
}
