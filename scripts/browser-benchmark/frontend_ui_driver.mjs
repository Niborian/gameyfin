// Injectable/mock-tested original-UI adapter only; no SSH/browser/transport creation.
import {allowRequest,validateLoginCredentials,loginFocusExpression,routeExpression,sanitizeMetrics,coverHashMatches,RUNTIME_ENABLED} from './frontend_cdp_measurement.mjs';
export function navigationProgress(routeIndex,stage,readiness=null,observation=null){
  if(!Number.isInteger(routeIndex)||routeIndex<1||routeIndex>3||!['navigate','content','cover-body','complete'].includes(stage))throw new Error('Navigation progress boundary');
  const keys=['pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'];
  if(readiness!==null&&(typeof readiness!=='object'||Array.isArray(readiness)||Object.keys(readiness).length!==keys.length||!keys.every(key=>typeof readiness[key]==='boolean')))throw new Error('Readiness boolean boundary');
  return {routeIndex,stage,readiness:readiness===null?null:Object.fromEntries(keys.map(key=>[key,readiness[key]])),...(observation===null?{}:{observation:navigationObservation(observation)})};
}
export function navigationObservation(value){
  const flags=['domRootPresent','navPresent','signInPresent','staticErrorViewPresent','uncaughtExceptionSeen','truncated'];
  const rpcs=['library','game','profile'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==9||!flags.every(k=>typeof value[k]==='boolean')||!rpcs.every(k=>value[k]&&Object.keys(value[k]).length===2&&['not-observed','1xx','2xx','3xx','4xx','5xx','invalid'].includes(value[k].status)&&['not-observed','pending','finished','failed'].includes(value[k].completion)))throw new Error('Navigation observation boundary');
  return {...Object.fromEntries(flags.map(k=>[k,value[k]])),...Object.fromEntries(rpcs.map(k=>[k,{status:value[k].status,completion:value[k].completion}]))};
}
export function observedRouteExpression(path,coverId){
  // Shell/error observations never substitute for route content or decoded cover.
  return `(async()=>{const value=await ${routeExpression(path,coverId)};value.observation={domRootPresent:!!document.querySelector('#outlet'),navPresent:!!document.querySelector('nav'),signInPresent:!!document.querySelector('nav button.gradient-primary:not(.size-8)'),staticErrorViewPresent:[...document.querySelectorAll('h1')].some(e=>e.textContent.trim().startsWith('404 – '))};return value;})()`;
}
export function authReadiness(value){
  const flags=['originMatches','loginPath','usernamePresent','passwordPresent','submitPresent','submitDisabled','invalidCredentialsDisplayed'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==flags.length+1||!flags.every(key=>typeof value[key]==='boolean')||!['loading','interactive','complete'].includes(value.documentReady))throw new Error('Auth readiness boundary');
  return {...Object.fromEntries(flags.map(key=>[key,value[key]])),documentReady:value.documentReady};
}
export function profileReadiness(value){
  const flags=['originMatches','profilePath','loginPath','myProfileHeading','usernamePresent','emailPresent','newPasswordPresent','passwordRepeatPresent','baseRootMatches','identityRow','adminItem','signOutItem','navPresent','navbarAvatarPresent','navbarSignInPresent','navTriggerPresent','navTriggerVisible','menuAriaPresent','menuAriaVisible','menuAriaOutsideNav','profileTriggerUnique'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==flags.length+1||!flags.every(key=>typeof value[key]==='boolean')||!['none','one','multiple'].includes(value.triggerCount))throw new Error('Profile readiness boundary');
  return {...Object.fromEntries(flags.map(key=>[key,value[key]])),triggerCount:value.triggerCount};
}
export function profileDiagnostic(value){
  if(!value||typeof value!=='object'||Object.keys(value).length!==2||!['not-started','navigate','profile-dom','open-menu','identity-menu','close-menu','complete'].includes(value.stage)||!Object.hasOwn(value,'readiness'))throw new Error('Profile diagnostic boundary');
  return {stage:value.stage,readiness:value.readiness===null?null:profileReadiness(value.readiness)};
}
export function safeAuthDiagnostics(value){
  const keys=['loginPostObserved','loginPostStatus','submittedUsernameLengthMatches','submittedPasswordLengthMatches','cdpFailure','cdpMethod','loginResultHeader','userInfoStatus','userInfoIdentity','cookieDiagnostics','readiness','profile'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==keys.length+(Object.hasOwn(value,'interceptionDiagnostics')?1:0)||!keys.every(key=>Object.hasOwn(value,key))||typeof value.loginPostObserved!=='boolean')throw new Error('Auth diagnostic schema');
  if(value.loginPostStatus!==null&&(!Number.isInteger(value.loginPostStatus)||value.loginPostStatus<100||value.loginPostStatus>599))throw new Error('Auth numeric boundary');
  for(const key of ['submittedUsernameLengthMatches','submittedPasswordLengthMatches'])if(value[key]!==null&&typeof value[key]!=='boolean')throw new Error('Auth length-match boundary');
  if(!['none','interception-invalid-id','execution-context-destroyed','execution-context-missing','target-closed','protocol-timeout','other'].includes(value.cdpFailure))throw new Error('Auth CDP failure boundary');
  if(!['none','page-navigation','runtime-evaluate','input-insert','request-interception','setup','other'].includes(value.cdpMethod))throw new Error('Auth CDP method boundary');
  if(!['success','failure','missing','other'].includes(value.loginResultHeader))throw new Error('Auth protocol header boundary');
  if(value.userInfoStatus!==null&&(!Number.isInteger(value.userInfoStatus)||value.userInfoStatus<100||value.userInfoStatus>599))throw new Error('Auth user-info status boundary');
  if(!['not-observed','null','fixture-user','other','invalid','failed'].includes(value.userInfoIdentity))throw new Error('Auth user-info identity boundary');
  return {...Object.fromEntries(keys.slice(0,9).map(key=>[key,value[key]])),cookieDiagnostics:cookieDiagnostics(value.cookieDiagnostics),readiness:value.readiness===null?null:authReadiness(value.readiness),profile:profileDiagnostic(value.profile),...(Object.hasOwn(value,'interceptionDiagnostics')?{interceptionDiagnostics:interceptionDiagnostics(value.interceptionDiagnostics)}:{})};
}
export function interceptionDiagnostics(value){
  const counters=['pausedEvents','duplicatePausedIds','canceledNetworkEvents'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==6||!['none','continue','deny'].includes(value.failedOperation)||!(value.failedRequestCanceled===null||typeof value.failedRequestCanceled==='boolean')||typeof value.truncated!=='boolean'||!counters.every(k=>Number.isInteger(value[k])&&value[k]>=0&&value[k]<=256))throw new Error('Interception diagnostic boundary');
  return {failedOperation:value.failedOperation,failedRequestCanceled:value.failedRequestCanceled,...Object.fromEntries(counters.map(k=>[k,value[k]])),truncated:value.truncated};
}
const cookieFlags=['loginSetCookieSeen','loginSessionCookieSetSeen','loginSessionCookieBlocked','profileCookieHeaderSeen','profileSessionCookieAssociated','profileSessionCookieBlocked','loginSubmittedWithPendingRpc','otherSessionCookieSetAfterLogin'];
export function cookieDiagnostics(value){
  if(!value||typeof value!=='object'||Object.keys(value).length!==cookieFlags.length||!cookieFlags.every(key=>Object.hasOwn(value,key)&&(value[key]===null||typeof value[key]==='boolean')))throw new Error('Cookie diagnostic boundary');
  return Object.fromEntries(cookieFlags.map(key=>[key,value[key]]));
}
function privateCookieInput(headers,rows,response){
  if(!headers||typeof headers!=='object'||Array.isArray(headers)||Object.keys(headers).length>32||!Array.isArray(rows)||rows.length>64)return false;
  let bytes=0;
  for(const [name,value] of Object.entries(headers)){if(typeof value!=='string'||name.length>256)return false;bytes+=Buffer.byteLength(name)+Buffer.byteLength(value);}
  for(const row of rows){
    if(!row||typeof row!=='object'||!Array.isArray(row.blockedReasons)||row.blockedReasons.length>32||!row.blockedReasons.every(reason=>typeof reason==='string'&&reason.length<=128))return false;
    if(row.cookie!==undefined){if(!row.cookie||typeof row.cookie.name!=='string'||row.cookie.name.length>256||typeof row.cookie.value!=='string')return false;bytes+=Buffer.byteLength(row.cookie.name)+Buffer.byteLength(row.cookie.value);}
    else if(!response)return false;
    if(response){if(typeof row.cookieLine!=='string')return false;bytes+=Buffer.byteLength(row.cookieLine);}
  }
  return bytes<=16384;
}
export function requestCookieProjection(params){
  if(!privateCookieInput(params?.headers,params?.associatedCookies,false))return null;
  const session=params.associatedCookies.filter(row=>row.cookie.name==='JSESSIONID');
  return {profileCookieHeaderSeen:Object.keys(params.headers).some(name=>name.toLowerCase()==='cookie'),profileSessionCookieAssociated:session.length>0,profileSessionCookieBlocked:session.some(row=>row.blockedReasons.length>0)};
}
export function responseCookieProjection(params){
  if(params?.statusCode!==200||!privateCookieInput(params?.headers,params?.blockedCookies,true))return null;
  const headers=Object.entries(params.headers).filter(([name])=>name.toLowerCase()==='set-cookie');
  return {loginSetCookieSeen:headers.length>0,loginSessionCookieSetSeen:headers.some(([,value])=>value.split('\n').some(line=>/^\s*JSESSIONID\s*=/.test(line))),loginSessionCookieBlocked:params.blockedCookies.some(row=>(row.cookie?.name==='JSESSIONID'||/^\s*JSESSIONID\s*=/.test(row.cookieLine))&&row.blockedReasons.length>0)};
}
class CookieObservation {
  constructor(output){this.output=output;this.known=new Map();this.ignored=new Set();this.earlyRequest=new Map();this.earlyResponse=new Map();this.overflow=new Set();this.metadata=new Map();this.pendingRpc=new Set();this.metadataOverflow=false;this.loginTimestamp=null;}
  valid(id){return typeof id==='string'&&id.length>0&&id.length<=128;}
  failUnknown(){this.metadataOverflow=true;this.output.otherSessionCookieSetAfterLogin=null;}
  main(id,kind,sameOrigin=false,rpc=false){
    if(!this.valid(id))return;
    if(sameOrigin){if(!this.metadata.has(id)&&this.metadata.size>=256)this.failUnknown();else this.metadata.set(id,{kind,timestamp:null,cookie:null});}
    if(rpc){if(this.pendingRpc.size>=256)this.failUnknown();else this.pendingRpc.add(id);}
    if(kind)for(const key of cookieFlags.slice(kind==='login'?0:3,kind==='login'?3:6))this.output[key]=null;
    if(!kind)this.known.delete(id); // Redirect reuse must not associate a later GET200 with login POST.
    if(kind&&this.known.size<4)this.known.set(id,kind);else if(!this.known.has(id)&&this.ignored.size<256)this.ignored.add(id);
    for(const [type,cache] of [['request',this.earlyRequest],['response',this.earlyResponse]]){if(cache.has(id)){this.apply(id,type,cache.get(id));if(type==='response'&&this.metadata.has(id))this.metadata.get(id).cookie=cache.get(id);cache.delete(id);}}
  }
  apply(id,type,row){
    const kind=this.known.get(id);
    if((type==='request'&&kind==='profile')||(type==='response'&&kind==='login')){for(const key of cookieFlags.slice(kind==='login'?0:3,kind==='login'?3:6))this.output[key]=row?.[key]??null;}
  }
  response(id,timestamp,status){
    const data=this.metadata.get(id);if(!data)return;
    if(!Number.isFinite(timestamp)||timestamp<0){this.failUnknown();return;}
    data.timestamp=timestamp;
    if(data.kind==='login'&&status===200){this.loginTimestamp=timestamp;this.output.otherSessionCookieSetAfterLogin=this.metadataOverflow?null:false;}
    this.reconcile();
  }
  reconcile(){
    if(this.metadataOverflow){this.output.otherSessionCookieSetAfterLogin=null;return;}if(this.loginTimestamp===null)return;
    this.output.otherSessionCookieSetAfterLogin=false;
    for(const data of this.metadata.values())if(data.kind!=='login'&&data.timestamp!==null&&data.timestamp>this.loginTimestamp){
      if(data.cookie===null){this.output.otherSessionCookieSetAfterLogin=null;continue;}
      if(data.cookie.loginSessionCookieSetSeen){this.output.otherSessionCookieSetAfterLogin=true;return;}
    }
  }
  snapshotPending(){this.output.loginSubmittedWithPendingRpc=this.metadataOverflow?null:this.pendingRpc.size>0;}
  finished(id){this.pendingRpc.delete(id);}
  extra(type,params){
    const id=params?.requestId;if(!this.valid(id)||(this.ignored.has(id)&&!this.metadata.has(id)))return;
    const row=type==='request'?requestCookieProjection(params):responseCookieProjection(params);
    if(type==='response'){
      const data=this.metadata.get(id);
      if(data){data.cookie=Number.isInteger(params.statusCode)&&params.statusCode>=100&&params.statusCode<=599?responseCookieProjection({...params,statusCode:200}):null;this.reconcile();}
    }
    if(this.known.has(id)){this.apply(id,type,row);return;}
    if(this.ignored.has(id))return;
    const cache=type==='request'?this.earlyRequest:this.earlyResponse;
    if(this.overflow.has(type))return;
    if(!cache.has(id)&&cache.size>=4){cache.clear();this.overflow.add(type);return;}
    cache.set(id,row); // Only projected booleans/null and bounded request IDs survive.
  }
}
// Private response input is reduced immediately; no DTO fields leave this function.
export function classifyUserInfoBody(response){
  if(!response||typeof response.body!=='string'||typeof response.base64Encoded!=='boolean'||Buffer.byteLength(response.body)>16384)return 'invalid';
  let body=response.body;
  if(response.base64Encoded){if(!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body))return 'invalid';body=Buffer.from(body,'base64').toString('utf8');}
  if(Buffer.byteLength(body)>16384)return 'invalid';
  let value;try{value=JSON.parse(body);}catch{return 'invalid';}
  if(value===null)return 'null';
  if(typeof value!=='object'||Array.isArray(value)||typeof value.username!=='string'||typeof value.enabled!=='boolean'||!Array.isArray(value.roles)||!value.roles.every(role=>typeof role==='string'))return 'invalid';
  return value.username==='fixture-admin'&&value.enabled===true&&value.roles.some(role=>['ROLE_ADMIN','ROLE_SUPERADMIN'].includes(role))?'fixture-user':'other';
}
export function loginResultHeader(headers){
  if(!headers||typeof headers!=='object')return 'missing';
  const rows=Object.entries(headers).filter(([name])=>name.toLowerCase()==='result');
  if(rows.length===0)return 'missing';
  return rows.length===1&&['success','failure'].includes(rows[0][1])?rows[0][1]:'other';
}
// Published HeroUI Popover clones data-slot=trigger onto ProfileMenu's DIV;
// ProfileMenu's unique child Avatar renders as button with fixed size-8 class.
const profileTriggers=`[...document.querySelectorAll('nav [data-slot="trigger"]')].filter(node=>node.getClientRects().length>0&&node.querySelectorAll('button.size-8').length===1)`;
export function loginFormExpression(){
  return `(()=>{const u=document.querySelector('input[name="username"]'),p=document.querySelector('input[name="password"]'),b=u?.closest('form')?.querySelector('button[type="submit"]');return {ready:location.pathname==='/login'&&!!u&&!!p&&!!b&&!b.disabled};})()`;
}
export function fixtureProfileExpression(origin){
  if(!allowRequest(origin,origin))throw new Error('Fixture profile origin boundary');
  return `(()=>{const u=document.querySelector('input[name="username"]'),e=document.querySelector('input[name="email"]'),p=document.querySelector('input[name="newPassword"]'),r=document.querySelector('input[name="passwordRepeat"]');const visible=node=>node.getClientRects().length>0;const menu=[...document.querySelectorAll('nav [aria-haspopup="true"],nav [aria-haspopup="menu"]')].filter(visible);const triggers=[...document.querySelectorAll('nav [data-slot="trigger"]')];const aria=[...document.querySelectorAll('[aria-haspopup="true"],[aria-haspopup="menu"]')];const items=[...document.querySelectorAll('[role="menuitem"]')];const base=new URL(document.baseURI);const diagnostic={originMatches:location.origin===${JSON.stringify(origin)},profilePath:location.pathname==='/settings/profile',loginPath:location.pathname==='/login',myProfileHeading:[...document.querySelectorAll('h2')].some(node=>node.textContent.trim()==='My Profile'),usernamePresent:!!u,emailPresent:!!e,newPasswordPresent:p?.type==='password',passwordRepeatPresent:r?.type==='password',baseRootMatches:base.origin===location.origin&&base.pathname==='/',identityRow:items.some(node=>node.textContent.trim()==='Signed in as fixture-admin'),adminItem:items.some(node=>node.textContent.trim()==='Administration'),signOutItem:items.some(node=>node.textContent.trim()==='Sign Out'),navPresent:!!document.querySelector('nav'),navbarAvatarPresent:!!document.querySelector('nav button.size-8'),navbarSignInPresent:!!document.querySelector('nav button.gradient-primary:not(.size-8)'),navTriggerPresent:triggers.length>0,navTriggerVisible:triggers.some(visible),menuAriaPresent:aria.length>0,menuAriaVisible:aria.some(visible),menuAriaOutsideNav:aria.some(node=>!node.closest('nav')),profileTriggerUnique:(${profileTriggers}).length===1,triggerCount:menu.length===0?'none':menu.length===1?'one':'multiple'};return {ready:diagnostic.originMatches&&diagnostic.profilePath&&diagnostic.myProfileHeading&&diagnostic.usernamePresent&&diagnostic.emailPresent&&diagnostic.newPasswordPresent&&diagnostic.passwordRepeatPresent&&diagnostic.profileTriggerUnique,diagnostic};})()`;
}
export function fixtureIdentityMenuExpression(){
  return `(()=>{const items=[...document.querySelectorAll('[role="menuitem"]')];const diagnostic={identityRow:items.some(node=>node.textContent.trim()==='Signed in as fixture-admin'),adminItem:items.some(node=>node.textContent.trim()==='Administration'),signOutItem:items.some(node=>node.textContent.trim()==='Sign Out')};return {ready:Object.values(diagnostic).every(value=>value===true),diagnostic};})()`;
}
export class FixtureUiDriver {
  constructor(cdp,{origin,coverId,coverSha256,onProgress=()=>{}}){
    if(!allowRequest(origin,origin)||!Number.isSafeInteger(coverId)||coverId<1||!/^[a-f0-9]{64}$/.test(coverSha256))throw new Error('Fixture identity boundary');
    this.cdp=cdp;this.origin=origin;this.coverId=coverId;this.coverSha256=coverSha256;this.cover=null;this.failure=false;this.navigations=0;
    this.totalDeadline=performance.now()+90000;
    if(typeof onProgress!=='function')throw new Error('Progress callback boundary');this.onProgress=onProgress;
    this.loginRequestIds=new Set();this.userInfoRequestIds=new Set();this.postLoginUserInfoIds=new Set();this.userInfoBodyRequestId=null;this.userInfoBodyAttempted=false;this.authDiagnostics={loginPostObserved:false,loginPostStatus:null,submittedUsernameLengthMatches:null,submittedPasswordLengthMatches:null,cdpFailure:'none',cdpMethod:'none',loginResultHeader:'missing',userInfoStatus:null,userInfoIdentity:'not-observed',cookieDiagnostics:Object.fromEntries(cookieFlags.map(key=>[key,null])),readiness:null,profile:{stage:'not-started',readiness:null}};
    this.cookieObservation=new CookieObservation(this.authDiagnostics.cookieDiagnostics);
    // IDs stay in bounded process memory only; receipts contain counts, never IDs.
    this.pausedIds=new Set();this.canceledNetworkIds=new Set();this.authDiagnostics.interceptionDiagnostics={failedOperation:'none',failedRequestCanceled:null,pausedEvents:0,duplicatePausedIds:0,canceledNetworkEvents:0,truncated:false};
    this.navigationRequests=new Map();this.navigationLast=new Map();this.navigationRequestCount=0;this.navigationState=null;
    cdp.onEvent=(method,params)=>this.event(method,params);
  }
  event(method,params){
    if(this.navigationState){
      if(method==='Runtime.exceptionThrown')this.navigationState.uncaughtExceptionSeen=true;
      if(method==='Network.requestWillBeSent'){
        try{const u=new URL(params.request?.url);const kind=u.origin===this.origin&&params.request?.method==='POST'?({'/connect/LibraryEndpoint/getAll':'library','/connect/GameEndpoint/getAll':'game','/connect/UserEndpoint/getUserInfo':'profile'})[u.pathname]:null;
          if(kind){if(typeof params.requestId==='string'&&params.requestId.length<=256&&this.navigationRequestCount<32){this.navigationRequestCount++;this.navigationRequests.set(params.requestId,kind);this.navigationLast.set(kind,params.requestId);this.navigationState[kind]={status:'not-observed',completion:'pending'};}else this.navigationState.truncated=true;}
        }catch{}
      }
      const kind=this.navigationRequests.get(params.requestId);
      // Per family, report the latest observed request, not an older response race.
      if(kind&&this.navigationLast.get(kind)===params.requestId&&method==='Network.responseReceived'){const status=params.response?.status;this.navigationState[kind].status=Number.isInteger(status)&&status>=100&&status<=599?Math.floor(status/100)+'xx':'invalid';}
      if(kind&&['Network.loadingFinished','Network.loadingFailed'].includes(method)){if(this.navigationLast.get(kind)===params.requestId)this.navigationState[kind].completion=method==='Network.loadingFinished'?'finished':'failed';this.navigationRequests.delete(params.requestId);}
    }
    if(method==='Network.requestWillBeSentExtraInfo'||method==='Network.responseReceivedExtraInfo'){
      this.cookieObservation.extra(method==='Network.requestWillBeSentExtraInfo'?'request':'response',params);
    }else if(method==='Fetch.requestPaused'){
      const diagnostic=this.authDiagnostics.interceptionDiagnostics;
      if(diagnostic.pausedEvents<256)diagnostic.pausedEvents++;else diagnostic.truncated=true;
      const id=params.requestId;
      if(typeof id==='string'&&id.length<=256){if(this.pausedIds.has(id)){if(diagnostic.duplicatePausedIds<256)diagnostic.duplicatePausedIds++;else diagnostic.truncated=true;}else if(this.pausedIds.size<256)this.pausedIds.add(id);else diagnostic.truncated=true;}else diagnostic.truncated=true;
      const permitted=allowRequest(params.request?.url||'',this.origin);
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/login'&&params.request.method==='POST')this.authDiagnostics.loginPostObserved=true;}catch{}
      // Never retain headers/postData/cookies/console bodies in evidence.
      const networkId=typeof params.networkId==='string'&&params.networkId.length<=256?params.networkId:null;
      this.request(permitted?'Fetch.continueRequest':'Fetch.failRequest',permitted?{requestId:params.requestId}:{requestId:params.requestId,errorReason:'BlockedByClient'}).catch(()=>{if(diagnostic.failedOperation==='none'){diagnostic.failedOperation=permitted?'continue':'deny';diagnostic.failedRequestCanceled=networkId!==null&&this.canceledNetworkIds.has(networkId)?true:null;}this.failure=true;});
    }else if(method==='Network.requestWillBeSent'){
      let cookieKind=null,sameOrigin=false,rpc=false;try{const u=new URL(params.request?.url);sameOrigin=u.origin===this.origin;rpc=sameOrigin&&u.pathname.startsWith('/connect/')&&params.request?.method==='POST';if(sameOrigin&&params.request?.method==='POST'){if(u.pathname==='/login')cookieKind='login';else if(u.pathname==='/connect/UserEndpoint/getUserInfo'&&this.authStage==='profile-proof'&&this.authDiagnostics.loginPostStatus===200&&this.authDiagnostics.loginResultHeader==='success')cookieKind='profile';}}catch{}
      this.cookieObservation.main(params.requestId,cookieKind,sameOrigin,rpc);
      if(this.loginRequestIds.has(params.requestId)&&params.redirectResponse){
        this.recordLoginStatus(params.redirectResponse);this.loginRequestIds.delete(params.requestId);
      }
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/login'&&params.request.method==='POST'&&typeof params.requestId==='string'&&params.requestId.length<=128&&this.loginRequestIds.size<4)this.loginRequestIds.add(params.requestId);}catch{}
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/connect/UserEndpoint/getUserInfo'&&params.request.method==='POST'&&typeof params.requestId==='string'&&params.requestId.length<=128&&this.userInfoRequestIds.size<4){this.userInfoRequestIds.add(params.requestId);if(this.authStage==='profile-proof'&&this.authDiagnostics.loginPostStatus===200&&this.authDiagnostics.loginResultHeader==='success'&&this.postLoginUserInfoIds.size<4)this.postLoginUserInfoIds.add(params.requestId);}}catch{}
    }else if(method==='Network.responseReceived'){
      const response=params.response;let parsed;try{parsed=new URL(response.url);}catch{return;}
      if(parsed.origin===this.origin)this.cookieObservation.response(params.requestId,params.timestamp,response.status);
      if(this.loginRequestIds.has(params.requestId)){this.recordLoginStatus(response);this.loginRequestIds.delete(params.requestId);}
      if(this.userInfoRequestIds.has(params.requestId)){
        if(parsed.origin===this.origin&&parsed.pathname==='/connect/UserEndpoint/getUserInfo'&&Number.isInteger(response.status)&&response.status>=100&&response.status<=599)this.authDiagnostics.userInfoStatus=response.status;
        if(parsed.origin===this.origin&&parsed.pathname==='/connect/UserEndpoint/getUserInfo'&&response.status===200&&this.postLoginUserInfoIds.has(params.requestId)&&!this.userInfoBodyAttempted&&this.userInfoBodyRequestId===null)this.userInfoBodyRequestId=params.requestId;
        this.postLoginUserInfoIds.delete(params.requestId);
        this.userInfoRequestIds.delete(params.requestId);
      }
      if(parsed.origin===this.origin&&parsed.pathname===`/images/cover/${this.coverId}`){
        if(typeof params.requestId!=='string'||params.requestId.length>128||!Number.isInteger(response.status))return;
        this.cover={requestId:params.requestId,coverResponseStatus:response.status,fromDiskCache:response.fromDiskCache===true,fromServiceWorker:response.fromServiceWorker===true};
      }
    }else if(method==='Network.loadingFinished'){
      this.cookieObservation.finished(params.requestId);
      if(this.cover?.requestId===params.requestId)this.cover.encodedDataLength=params.encodedDataLength;
      if(this.userInfoBodyRequestId===params.requestId&&!this.userInfoBodyAttempted){
        this.userInfoBodyAttempted=true;this.userInfoBodyRequestId=null;
        if(!Number.isFinite(params.encodedDataLength)||params.encodedDataLength<0||params.encodedDataLength>16384){this.authDiagnostics.userInfoIdentity='invalid';return;}
        this.request('Network.getResponseBody',{requestId:params.requestId},this.postSubmitDeadline??0).then(response=>{this.authDiagnostics.userInfoIdentity=classifyUserInfoBody(response);}).catch(()=>{this.authDiagnostics.userInfoIdentity='failed';});
      }
    }else if(method==='Network.loadingFailed'){
      this.cookieObservation.finished(params.requestId);
      if(params.canceled===true){const d=this.authDiagnostics.interceptionDiagnostics;if(d.canceledNetworkEvents<256)d.canceledNetworkEvents++;else d.truncated=true;if(typeof params.requestId==='string'&&params.requestId.length<=256&&this.canceledNetworkIds.size<256)this.canceledNetworkIds.add(params.requestId);else d.truncated=true;}
    }
  }
  recordLoginStatus(response){
    try{const u=new URL(response.url);if(u.origin===this.origin&&u.pathname==='/login'&&Number.isInteger(response.status)&&response.status>=100&&response.status<=599){this.authDiagnostics.loginPostStatus=response.status;this.authDiagnostics.loginResultHeader=loginResultHeader(response.headers);}}catch{}
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
  async poll(expression,observe=()=>{},outerDeadline=this.totalDeadline){
    const deadline=Math.min(performance.now()+15000,this.totalDeadline,outerDeadline);
    while(performance.now()<deadline){
      if(this.failure)throw new Error('Origin interception failed');
      const value=await this.evaluate(expression,deadline);observe(value);if(value?.ready===true)return value;
      await new Promise(resolve=>setTimeout(resolve,50));
    }
    throw new Error('DOM readiness deadline');
  }
  async authenticate(username,password){
    validateLoginCredentials(username,password);
    this.authStage='interception';await this.enableBeforeAuth();this.authStage='login-navigation';await this.request('Page.navigate',{url:this.origin+'/login'});
    this.authStage='login-form';
    await this.poll(loginFormExpression());
    this.authStage='submit';
    // LoginView uses React/Formik controlled fields. Real browser input events
    // and separate render turns prevent submitting stale Formik state from one
    // synchronous setter+click expression. Credentials stay private CDP payloads.
    for(const [field,text] of [['username',username],['password',password]]){
      if(await this.evaluate(loginFocusExpression(field))!==true)throw new Error('Normal UI login unavailable');
      await this.request('Input.insertText',{text});
      await this.evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))`);
    }
    const postSubmitDeadline=performance.now()+15000;
    this.postSubmitDeadline=postSubmitDeadline;
    this.cookieObservation.snapshotPending(); // Snapshot before the normal DOM submit click, never after its network side effects.
    const submitted=await this.evaluate(`(()=>{const u=document.querySelector('input[name="username"]'),p=document.querySelector('input[name="password"]');const b=u?.closest('form')?.querySelector('button[type="submit"]');if(location.pathname!=='/login'||!p||!b||b.disabled)return {clicked:false};const result={clicked:true,usernameLength:u.value.length,passwordLength:p.value.length};b.click();return result;})()`,postSubmitDeadline);
    if(!submitted||submitted.clicked!==true||!Number.isInteger(submitted.usernameLength)||submitted.usernameLength<0||submitted.usernameLength>128||!Number.isInteger(submitted.passwordLength)||submitted.passwordLength<0||submitted.passwordLength>256)throw new Error('Normal UI login unavailable');
    this.authDiagnostics.submittedUsernameLengthMatches=submitted.usernameLength===username.length;this.authDiagnostics.submittedPasswordLengthMatches=submitted.passwordLength===password.length;
    // Diagnostic continuation, not a successful automatic-landing acceptance.
    // Start original-UI observation checks for up to3s; each DOM query and
    // profile verification share the SAME15s post-submit budget. The profile loads no
    // fixture cover. Dynamic profile-menu identity proves auth; HTTP200 alone
    // cannot, and ProfileManagement's non-reinitializing Formik fields can race
    // auth initialization. No field values are read or modified by the probe.
    this.authStage='redirect';let autoRedirectObserved=false;
    const observationEnd=Math.min(performance.now()+3000,postSubmitDeadline);
    while(performance.now()<observationEnd){
      const value=await this.evaluate(`(()=>{const u=document.querySelector('input[name="username"]'),p=document.querySelector('input[name="password"]'),b=u?.closest('form')?.querySelector('button[type="submit"]');const diagnostic={originMatches:location.origin===${JSON.stringify(this.origin)},loginPath:location.pathname==='/login',usernamePresent:!!u,passwordPresent:!!p,submitPresent:!!b,submitDisabled:!!b?.disabled,invalidCredentialsDisplayed:document.body?.textContent?.includes('Invalid username and/or password.')===true,documentReady:document.readyState};return {ready:diagnostic.originMatches&&!diagnostic.loginPath,diagnostic};})()`,postSubmitDeadline);
      this.authDiagnostics.readiness=authReadiness(value?.diagnostic);
      if(value.ready===true){autoRedirectObserved=true;break;}
      await new Promise(resolve=>setTimeout(resolve,50));
    }
    while(this.authDiagnostics.loginPostStatus!==200||this.authDiagnostics.loginResultHeader!=='success'){
      if(this.authDiagnostics.loginResultHeader==='failure'||(this.authDiagnostics.loginPostStatus!==null&&this.authDiagnostics.loginPostStatus>=400)||performance.now()>=postSubmitDeadline)throw new Error('Normal login protocol acceptance unproved');
      await new Promise(resolve=>setTimeout(resolve,Math.min(50,Math.max(0,postSubmitDeadline-performance.now()))));
    }
    this.authStage='profile-proof';this.authDiagnostics.profile.stage='navigate';await this.request('Page.navigate',{url:this.origin+'/settings/profile'},postSubmitDeadline);
    this.authDiagnostics.profile.stage='profile-dom';
    await this.poll(fixtureProfileExpression(this.origin),value=>{this.authDiagnostics.profile.readiness=profileReadiness(value?.diagnostic);},postSubmitDeadline);
    this.authDiagnostics.profile.stage='open-menu';
    await this.openProfileMenu(postSubmitDeadline);
    this.authDiagnostics.profile.stage='identity-menu';
    await this.poll(fixtureIdentityMenuExpression(),value=>{this.authDiagnostics.profile.readiness=profileReadiness({...this.authDiagnostics.profile.readiness,...value?.diagnostic});},postSubmitDeadline);
    this.authDiagnostics.profile.stage='close-menu';
    await this.openProfileMenu(postSubmitDeadline); // Close through the same normal trigger.
    this.authDiagnostics.profile.stage='complete';
    this.authStage='complete';return {normalLoginAutoRedirectObserved:autoRedirectObserved,fixtureProfileVerified:true};
  }
  async logout(){
    // MainLayout renders one ProfileMenu dropdown in the navbar; its original
    // Sign Out item calls useAuth().logout. No synthetic logout POST/API call.
    await this.openProfileMenu();
    await this.poll(`(()=>({ready:[...document.querySelectorAll('[role="menuitem"]')].some(e=>e.textContent.trim()==='Sign Out')}))()`);
    if(await this.evaluate(`(()=>{const items=[...document.querySelectorAll('[role="menuitem"]')];if(!['My Profile','Administration','Help','Sign Out'].every(label=>items.some(e=>e.textContent.trim()===label)))return false;const targets=items.filter(e=>e.textContent.trim()==='Sign Out');if(targets.length!==1)return false;targets[0].click();return true;})()`)!==true)throw new Error('Normal UI logout unavailable');
    await this.poll(`(()=>({ready:location.origin===${JSON.stringify(this.origin)}&&location.pathname==='/login'&&!!document.querySelector('input[name="username"]')&&!!document.querySelector('input[name="password"]')}))()`);
    return {loggedOut:true};
  }
  async openProfileMenu(deadline=this.totalDeadline){
      if(await this.evaluate(`(()=>{if(location.origin!==${JSON.stringify(this.origin)}||location.pathname==='/login')return false;const triggers=${profileTriggers};if(triggers.length!==1)return false;triggers[0].click();return true;})()`,deadline)!==true)throw new Error('Normal UI logout unavailable');
  }
  async navigate(path){
    if(!['/library/27003','/game/33000'].includes(path)||++this.navigations>3)throw new Error('Navigation bound');
    this.onProgress(navigationProgress(this.navigations,'navigate'));
    this.navigationRequests.clear();this.navigationLast.clear();this.navigationRequestCount=0;this.navigationState={uncaughtExceptionSeen:false,truncated:false,...Object.fromEntries(['library','game','profile'].map(k=>[k,{status:'not-observed',completion:'not-observed'}]))};
    this.cover=null;await this.request('Page.navigate',{url:this.origin+path});
    let lastProgress=-Infinity;
    const value=await this.poll(observedRouteExpression(path,this.coverId),value=>{
      if(performance.now()-lastProgress>=1000||value?.ready===true){this.onProgress(navigationProgress(this.navigations,'content',value?.diagnostic,value?.observation?{...value.observation,...this.navigationState}:null));lastProgress=performance.now();}
    });
    if(!this.cover||![200,304].includes(this.cover.coverResponseStatus))throw new Error('Normal cover response absent');
    this.onProgress(navigationProgress(this.navigations,'cover-body'));
    const body=await this.request('Network.getResponseBody',{requestId:this.cover.requestId});
    const matches=coverHashMatches(body.body,body.base64Encoded,this.coverSha256);
    if(!matches)throw new Error('Normal cover body identity failed');
    const {ready,diagnostic,observation,...metrics}=value;const {requestId,...response}=this.cover;
    this.onProgress(navigationProgress(this.navigations,'complete'));
    return sanitizeMetrics({...metrics,...response,coverSha256Matches:matches});
  }
}
export function connectAndMeasure(){
  if(!RUNTIME_ENABLED)throw new Error('Connected frontend runtime disabled pending review');
  throw new Error('Owned SSH/Chrome transport not installed');
}
