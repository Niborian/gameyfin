// Injectable/mock-tested original-UI adapter only; no SSH/browser/transport creation.
import {allowRequest,loginExpression,routeExpression,sanitizeMetrics,coverHashMatches,RUNTIME_ENABLED} from './frontend_cdp_measurement.mjs';
export class FixtureUiDriver {
  constructor(cdp,{origin,coverId,coverSha256}){
    if(!allowRequest(origin,origin)||!Number.isSafeInteger(coverId)||coverId<1||!/^[a-f0-9]{64}$/.test(coverSha256))throw new Error('Fixture identity boundary');
    this.cdp=cdp;this.origin=origin;this.coverId=coverId;this.coverSha256=coverSha256;this.cover=null;this.failure=false;this.navigations=0;
    this.totalDeadline=performance.now()+90000;
    cdp.onEvent=(method,params)=>this.event(method,params);
  }
  event(method,params){
    if(method==='Fetch.requestPaused'){
      const permitted=allowRequest(params.request?.url||'',this.origin);
      // Never retain headers/postData/cookies/console bodies in evidence.
      this.request(permitted?'Fetch.continueRequest':'Fetch.failRequest',permitted?{requestId:params.requestId}:{requestId:params.requestId,errorReason:'BlockedByClient'}).catch(()=>{this.failure=true;});
    }else if(method==='Network.responseReceived'){
      const response=params.response;let parsed;try{parsed=new URL(response.url);}catch{return;}
      if(parsed.origin===this.origin&&parsed.pathname===`/images/cover/${this.coverId}`){
        if(typeof params.requestId!=='string'||params.requestId.length>128||!Number.isInteger(response.status))return;
        this.cover={requestId:params.requestId,coverResponseStatus:response.status,fromDiskCache:response.fromDiskCache===true,fromServiceWorker:response.fromServiceWorker===true};
      }
    }else if(method==='Network.loadingFinished'&&this.cover?.requestId===params.requestId){
      this.cover.encodedDataLength=params.encodedDataLength;
    }
  }
  async request(method,params={},deadline=this.totalDeadline){
    const remaining=Math.floor(Math.min(15000,deadline-performance.now(),this.totalDeadline-performance.now()));
    if(remaining<1)throw new Error('Controller deadline');
    return this.cdp.request(method,params,remaining);
  }
  async enableBeforeAuth(){
    for(const method of ['Page.enable','Runtime.enable','Network.enable'])await this.request(method);
    await this.request('Fetch.enable',{patterns:[{urlPattern:'*',requestStage:'Request'}]});
    // The original fixture UI does not require WebRTC. Disable exposed peer APIs
    // before any page script, in addition to the scoped non-proxied-UDP preference.
    await this.request('Page.addScriptToEvaluateOnNewDocument',{source:`for(const name of ['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'])Object.defineProperty(globalThis,name,{value:undefined,writable:false,configurable:false});`});
  }
  async evaluate(expression,deadline=this.totalDeadline){
    const remaining=Math.floor(Math.min(15000,deadline-performance.now(),this.totalDeadline-performance.now()));
    if(remaining<1)throw new Error('DOM readiness deadline');
    const value=await this.request('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true},deadline);
    if(value.exceptionDetails)throw new Error('DOM operation failed');
    return value.result?.value;
  }
  async poll(expression){
    const deadline=Math.min(performance.now()+15000,this.totalDeadline);
    while(performance.now()<deadline){
      if(this.failure)throw new Error('Origin interception failed');
      const value=await this.evaluate(expression,deadline);if(value?.ready===true)return value;
      await new Promise(resolve=>setTimeout(resolve,50));
    }
    throw new Error('DOM readiness deadline');
  }
  async authenticate(username,password){
    await this.enableBeforeAuth();await this.request('Page.navigate',{url:this.origin+'/login'});
    await this.poll(`(()=>({ready:location.pathname==='/login'&&!!document.querySelector('input[name="username"]')&&!!document.querySelector('input[name="password"]')}))()`);
    if(await this.evaluate(loginExpression(username,password))!==true)throw new Error('Normal UI login unavailable');
    // Normal Hilla login redirects; a protected target then proves fixture route content.
    await this.poll(`(()=>({ready:location.origin===${JSON.stringify(this.origin)}&&location.pathname!=='/login'}))()`);
  }
  async navigate(path){
    if(!['/library/27003','/game/33000'].includes(path)||++this.navigations>3)throw new Error('Navigation bound');
    this.cover=null;await this.request('Page.navigate',{url:this.origin+path});
    const value=await this.poll(routeExpression(path,this.coverId));
    if(!this.cover||![200,304].includes(this.cover.coverResponseStatus))throw new Error('Normal cover response absent');
    const body=await this.request('Network.getResponseBody',{requestId:this.cover.requestId});
    const matches=coverHashMatches(body.body,body.base64Encoded,this.coverSha256);
    if(!matches)throw new Error('Normal cover body identity failed');
    const {ready,...metrics}=value;const {requestId,...response}=this.cover;
    return sanitizeMetrics({...metrics,...response,coverSha256Matches:matches});
  }
}
export function connectAndMeasure(){
  if(!RUNTIME_ENABLED)throw new Error('Connected frontend runtime disabled pending review');
  throw new Error('Owned SSH/Chrome transport not installed');
}
