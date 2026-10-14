// Injectable/mock-tested original-UI adapter only; no SSH/browser/transport creation.
import {allowRequest,validateLoginCredentials,loginFocusExpression,routeExpression,sanitizeMetrics,coverHashMatches,RUNTIME_ENABLED} from './frontend_cdp_measurement.mjs';
export function navigationProgress(routeIndex,stage,readiness=null){
  if(!Number.isInteger(routeIndex)||routeIndex<1||routeIndex>3||!['navigate','content','cover-body','complete'].includes(stage))throw new Error('Navigation progress boundary');
  const keys=['pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'];
  if(readiness!==null&&(typeof readiness!=='object'||Array.isArray(readiness)||Object.keys(readiness).length!==keys.length||!keys.every(key=>typeof readiness[key]==='boolean')))throw new Error('Readiness boolean boundary');
  return {routeIndex,stage,readiness:readiness===null?null:Object.fromEntries(keys.map(key=>[key,readiness[key]]))};
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
  const keys=['loginPostObserved','loginPostStatus','submittedUsernameLengthMatches','submittedPasswordLengthMatches','cdpFailure','cdpMethod','loginResultHeader','userInfoStatus','userInfoIdentity','readiness','profile'];
  if(!value||typeof value!=='object'||Object.keys(value).length!==keys.length||!keys.every(key=>Object.hasOwn(value,key))||typeof value.loginPostObserved!=='boolean')throw new Error('Auth diagnostic schema');
  if(value.loginPostStatus!==null&&(!Number.isInteger(value.loginPostStatus)||value.loginPostStatus<100||value.loginPostStatus>599))throw new Error('Auth numeric boundary');
  for(const key of ['submittedUsernameLengthMatches','submittedPasswordLengthMatches'])if(value[key]!==null&&typeof value[key]!=='boolean')throw new Error('Auth length-match boundary');
  if(!['none','execution-context-destroyed','execution-context-missing','target-closed','protocol-timeout','other'].includes(value.cdpFailure))throw new Error('Auth CDP failure boundary');
  if(!['none','page-navigation','runtime-evaluate','input-insert','request-interception','setup','other'].includes(value.cdpMethod))throw new Error('Auth CDP method boundary');
  if(!['success','failure','missing','other'].includes(value.loginResultHeader))throw new Error('Auth protocol header boundary');
  if(value.userInfoStatus!==null&&(!Number.isInteger(value.userInfoStatus)||value.userInfoStatus<100||value.userInfoStatus>599))throw new Error('Auth user-info status boundary');
  if(!['not-observed','null','fixture-user','other','invalid','failed'].includes(value.userInfoIdentity))throw new Error('Auth user-info identity boundary');
  return {...Object.fromEntries(keys.slice(0,9).map(key=>[key,value[key]])),readiness:value.readiness===null?null:authReadiness(value.readiness),profile:profileDiagnostic(value.profile)};
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
    this.loginRequestIds=new Set();this.userInfoRequestIds=new Set();this.postLoginUserInfoIds=new Set();this.userInfoBodyRequestId=null;this.userInfoBodyAttempted=false;this.authDiagnostics={loginPostObserved:false,loginPostStatus:null,submittedUsernameLengthMatches:null,submittedPasswordLengthMatches:null,cdpFailure:'none',cdpMethod:'none',loginResultHeader:'missing',userInfoStatus:null,userInfoIdentity:'not-observed',readiness:null,profile:{stage:'not-started',readiness:null}};
    cdp.onEvent=(method,params)=>this.event(method,params);
  }
  event(method,params){
    if(method==='Fetch.requestPaused'){
      const permitted=allowRequest(params.request?.url||'',this.origin);
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/login'&&params.request.method==='POST')this.authDiagnostics.loginPostObserved=true;}catch{}
      // Never retain headers/postData/cookies/console bodies in evidence.
      this.request(permitted?'Fetch.continueRequest':'Fetch.failRequest',permitted?{requestId:params.requestId}:{requestId:params.requestId,errorReason:'BlockedByClient'}).catch(()=>{this.failure=true;});
    }else if(method==='Network.requestWillBeSent'){
      if(this.loginRequestIds.has(params.requestId)&&params.redirectResponse){
        this.recordLoginStatus(params.redirectResponse);this.loginRequestIds.delete(params.requestId);
      }
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/login'&&params.request.method==='POST'&&typeof params.requestId==='string'&&params.requestId.length<=128&&this.loginRequestIds.size<4)this.loginRequestIds.add(params.requestId);}catch{}
      try{const u=new URL(params.request?.url);if(u.origin===this.origin&&u.pathname==='/connect/UserEndpoint/getUserInfo'&&params.request.method==='POST'&&typeof params.requestId==='string'&&params.requestId.length<=128&&this.userInfoRequestIds.size<4){this.userInfoRequestIds.add(params.requestId);if(this.authStage==='profile-proof'&&this.authDiagnostics.loginPostStatus===200&&this.authDiagnostics.loginResultHeader==='success'&&this.postLoginUserInfoIds.size<4)this.postLoginUserInfoIds.add(params.requestId);}}catch{}
    }else if(method==='Network.responseReceived'){
      const response=params.response;let parsed;try{parsed=new URL(response.url);}catch{return;}
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
      if(this.cover?.requestId===params.requestId)this.cover.encodedDataLength=params.encodedDataLength;
      if(this.userInfoBodyRequestId===params.requestId&&!this.userInfoBodyAttempted){
        this.userInfoBodyAttempted=true;this.userInfoBodyRequestId=null;
        if(!Number.isFinite(params.encodedDataLength)||params.encodedDataLength<0||params.encodedDataLength>16384){this.authDiagnostics.userInfoIdentity='invalid';return;}
        this.request('Network.getResponseBody',{requestId:params.requestId},this.postSubmitDeadline??0).then(response=>{this.authDiagnostics.userInfoIdentity=classifyUserInfoBody(response);}).catch(()=>{this.authDiagnostics.userInfoIdentity='failed';});
      }
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
    await this.poll(`(()=>({ready:location.pathname==='/login'&&!!document.querySelector('input[name="username"]')&&!!document.querySelector('input[name="password"]')}))()`);
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
    this.cover=null;await this.request('Page.navigate',{url:this.origin+path});
    let lastProgress=-Infinity;
    const value=await this.poll(routeExpression(path,this.coverId),value=>{
      if(performance.now()-lastProgress>=1000||value?.ready===true){this.onProgress(navigationProgress(this.navigations,'content',value?.diagnostic));lastProgress=performance.now();}
    });
    if(!this.cover||![200,304].includes(this.cover.coverResponseStatus))throw new Error('Normal cover response absent');
    this.onProgress(navigationProgress(this.navigations,'cover-body'));
    const body=await this.request('Network.getResponseBody',{requestId:this.cover.requestId});
    const matches=coverHashMatches(body.body,body.base64Encoded,this.coverSha256);
    if(!matches)throw new Error('Normal cover body identity failed');
    const {ready,diagnostic,...metrics}=value;const {requestId,...response}=this.cover;
    this.onProgress(navigationProgress(this.navigations,'complete'));
    return sanitizeMetrics({...metrics,...response,coverSha256Matches:matches});
  }
}
export function connectAndMeasure(){
  if(!RUNTIME_ENABLED)throw new Error('Connected frontend runtime disabled pending review');
  throw new Error('Owned SSH/Chrome transport not installed');
}
