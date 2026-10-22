import test from 'node:test';import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import vm from 'node:vm';
import {FixtureUiDriver,connectAndMeasure,navigationProgress,navigationObservation,observedRouteExpression,safeProxyDiagnostics,authReadiness,safeAuthDiagnostics,loginResultHeader,fixtureProfileExpression,fixtureIdentityMenuExpression,profileReadiness,profileDiagnostic,classifyUserInfoBody,cookieDiagnostics,requestCookieProjection,responseCookieProjection,loginFormExpression} from './frontend_ui_driver.mjs';
import {recordFirstAuthCdpFailure} from './browser_entry.mjs';
const config={origin:'http://127.0.0.1:53123',coverId:7,coverSha256:'0'.repeat(64)};
const readyAuth={originMatches:true,loginPath:false,usernamePresent:false,passwordPresent:false,submitPresent:false,submitDisabled:false,invalidCredentialsDisplayed:false,documentReady:'complete'};
const unknownCookies={loginSetCookieSeen:null,loginSessionCookieSetSeen:null,loginSessionCookieBlocked:null,profileCookieHeaderSeen:null,profileSessionCookieAssociated:null,profileSessionCookieBlocked:null,loginSubmittedWithPendingRpc:null,otherSessionCookieSetAfterLogin:null};
const readyProfile={originMatches:true,profilePath:true,loginPath:false,myProfileHeading:true,usernamePresent:true,emailPresent:true,newPasswordPresent:true,passwordRepeatPresent:true,baseRootMatches:true,identityRow:true,adminItem:true,signOutItem:true,navPresent:true,navbarAvatarPresent:true,navbarSignInPresent:false,navTriggerPresent:true,navTriggerVisible:true,menuAriaPresent:true,menuAriaVisible:true,menuAriaOutsideNav:false,profileTriggerUnique:true,triggerCount:'one'};
test('normal login form waits for original enabled submit control before typing',()=>{
  let button={disabled:true},password={};
  const username={closest:selector=>{assert.equal(selector,'form');return {querySelector:selector=>{assert.equal(selector,'button[type="submit"]');return button;}};}};
  const document={querySelector:selector=>selector==='input[name="username"]'?username:password};
  const evaluate=()=>vm.runInNewContext(loginFormExpression(),{document,location:{pathname:'/login'}}).ready;
  assert.equal(evaluate(),false);button.disabled=false;assert.equal(evaluate(),true);
  button=null;assert.equal(evaluate(),false);button={disabled:false};password=null;assert.equal(evaluate(),false);
  assert(!loginFormExpression().includes('.click('));assert(!loginFormExpression().includes('.value'));
});
test('authentication diagnostics expose only status, length matches and fixed readiness',()=>{
  const row={loginPostObserved:true,loginPostStatus:401,submittedUsernameLengthMatches:true,submittedPasswordLengthMatches:true,cdpFailure:'none',cdpMethod:'none',loginResultHeader:'missing',userInfoStatus:null,userInfoIdentity:'not-observed',cookieDiagnostics:unknownCookies,readiness:readyAuth,profile:{stage:'not-started',readiness:null}};
  assert.deepEqual(safeAuthDiagnostics(row),row);
  for(const bad of [{...row,cookie:'secret'},{...row,loginPostStatus:'secret'},{...row,submittedPasswordLengthMatches:43},{...row,readiness:{...readyAuth,documentReady:'secret'}},{...row,readiness:{...readyAuth,text:'secret'}}])assert.throws(()=>safeAuthDiagnostics(bad));
  assert.throws(()=>authReadiness({...readyAuth,loginPath:1}));
});
test('profile substage/readiness diagnostics reject arbitrary data without loosening gates',()=>{
  assert.deepEqual(profileDiagnostic({stage:'profile-dom',readiness:readyProfile}),{stage:'profile-dom',readiness:readyProfile});
  for(const bad of [{...readyProfile,url:'secret'},{...readyProfile,triggerCount:2},{...readyProfile,identityRow:'private'}])assert.throws(()=>profileReadiness(bad));
  assert.throws(()=>profileDiagnostic({stage:'private',readiness:null}));
});
test('Hilla Result header is a fixed protocol classification, never a raw header',()=>{
  assert.equal(loginResultHeader({Result:'success','Spring-CSRF-token':'private'}),'success');assert.equal(loginResultHeader({result:'failure'}),'failure');
  assert.equal(loginResultHeader({'Set-Cookie':'private'}),'missing');assert.equal(loginResultHeader({Result:'private'}),'other');
  assert.equal(loginResultHeader({Result:'success',result:'private'}),'other');
});
test('private user info reduces only bounded JSON to a fixed identity enum',()=>{
  const body=value=>({body:JSON.stringify(value),base64Encoded:false});
  const fixture={username:'fixture-admin',enabled:true,roles:['ROLE_SUPERADMIN'],email:'PRIVATE_EMAIL',token:'PRIVATE_TOKEN'};
  assert.equal(classifyUserInfoBody(body(fixture)),'fixture-user');
  assert.equal(classifyUserInfoBody(body({...fixture,roles:['ROLE_ADMIN']})),'fixture-user');
  assert.equal(classifyUserInfoBody(body({...fixture,roles:['ADMIN']})),'other');
  assert.equal(classifyUserInfoBody(body({...fixture,username:'PRIVATE_USER'})),'other');
  assert.equal(classifyUserInfoBody(body({...fixture,enabled:false})),'other');
  assert.equal(classifyUserInfoBody(body(null)),'null');
  for(const value of [[],{},'PRIVATE', {...fixture,roles:[{}]}])assert.equal(classifyUserInfoBody(body(value)),'invalid');
  for(const response of [{body:'PRIVATE',base64Encoded:false},{body:'!PRIVATE',base64Encoded:true},{body:' '.repeat(16385),base64Encoded:false}])assert.equal(classifyUserInfoBody(response),'invalid');
  assert.equal(classifyUserInfoBody({body:Buffer.from(JSON.stringify(fixture)).toString('base64'),base64Encoded:true}),'fixture-user');
});
test('cookie projections emit only nullable booleans and reject malformed/oversized inputs',()=>{
  const request={headers:{Cookie:'JSESSIONID=PRIVATE'},associatedCookies:[{cookie:{name:'JSESSIONID',value:'PRIVATE'},blockedReasons:[]}]};
  const response={statusCode:200,headers:{'Set-Cookie':'other=PRIVATE\nJSESSIONID=PRIVATE; HttpOnly'},blockedCookies:[]};
  assert.deepEqual(requestCookieProjection(request),{profileCookieHeaderSeen:true,profileSessionCookieAssociated:true,profileSessionCookieBlocked:false});
  assert.deepEqual(responseCookieProjection(response),{loginSetCookieSeen:true,loginSessionCookieSetSeen:true,loginSessionCookieBlocked:false});
  assert.equal(responseCookieProjection({...response,blockedCookies:[{cookieLine:'JSESSIONID=PRIVATE',blockedReasons:['SecureOnly']}]}).loginSessionCookieBlocked,true);
  assert.equal(requestCookieProjection({...request,associatedCookies:[{cookie:{name:'JSESSIONID',value:'PRIVATE'},blockedReasons:['SecureOnly']}]}).profileSessionCookieBlocked,true);
  assert.equal(responseCookieProjection({...response,statusCode:302}),null);
  for(const value of [{...request,associatedCookies:{}},{...request,associatedCookies:Array(65).fill({})},{...request,headers:{Cookie:'P'.repeat(16385)}},{...request,associatedCookies:[{cookie:{name:'JSESSIONID',value:'PRIVATE'},blockedReasons:'private'}]}])assert.equal(requestCookieProjection(value),null);
  for(const value of [{...response,headers:{} ,blockedCookies:[{}]},{...response,headers:{'Set-Cookie':34}}])assert.equal(responseCookieProjection(value),null);
  assert.deepEqual(cookieDiagnostics(unknownCookies),unknownCookies);
  for(const value of [{...unknownCookies,raw:'PRIVATE'},{...unknownCookies,profileCookieHeaderSeen:1}])assert.throws(()=>cookieDiagnostics(value));
});
test('cookie ExtraInfo correlates early and late projections without retaining secrets',()=>{
  const driver=new FixtureUiDriver({request:async()=>({})},config);
  const login={requestId:'login',statusCode:200,headers:{'Set-Cookie':'JSESSIONID=PRIVATE'},blockedCookies:[]};
  driver.event('Network.responseReceivedExtraInfo',login);
  driver.event('Network.requestWillBeSent',{requestId:'login',request:{url:config.origin+'/login',method:'POST'}});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.loginSessionCookieSetSeen,true);
  driver.event('Network.responseReceivedExtraInfo',{...login,headers:{'Set-Cookie':34}});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.loginSessionCookieSetSeen,null);
  driver.authStage='profile-proof';driver.authDiagnostics.loginPostStatus=200;driver.authDiagnostics.loginResultHeader='success';
  driver.event('Network.requestWillBeSent',{requestId:'profile',request:{url:config.origin+'/connect/UserEndpoint/getUserInfo',method:'POST'}});
  driver.event('Network.requestWillBeSentExtraInfo',{requestId:'profile',headers:{Cookie:'JSESSIONID=PRIVATE'},associatedCookies:[{cookie:{name:'JSESSIONID',value:'PRIVATE'},blockedReasons:[]}]});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.profileSessionCookieAssociated,true);
  assert(!JSON.stringify(driver.cookieObservation).includes('PRIVATE'));assert(!JSON.stringify(safeAuthDiagnostics(driver.authDiagnostics)).includes('PRIVATE'));
  driver.event('Network.requestWillBeSentExtraInfo',{requestId:'profile',headers:{Cookie:34},associatedCookies:[]});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.profileCookieHeaderSeen,null);
  driver.event('Network.requestWillBeSent',{requestId:'next-profile',request:{url:config.origin+'/connect/UserEndpoint/getUserInfo',method:'POST'}});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.profileSessionCookieAssociated,null);
});
test('pending RPC snapshot and later session-cookie projection use source timestamps not arrival order',()=>{
  const driver=new FixtureUiDriver({request:async()=>({})},config);
  const main=(id,path)=>driver.event('Network.requestWillBeSent',{requestId:id,request:{url:config.origin+path,method:'POST'}});
  const response=(id,path,timestamp)=>driver.event('Network.responseReceived',{requestId:id,timestamp,response:{url:config.origin+path,status:200,headers:{Result:'success'}}});
  const extra=id=>driver.event('Network.responseReceivedExtraInfo',{requestId:id,statusCode:200,headers:{'Set-Cookie':'JSESSIONID=PRIVATE'},blockedCookies:[]});
  main('rpc','/connect/Test/a');driver.cookieObservation.snapshotPending();assert.equal(driver.authDiagnostics.cookieDiagnostics.loginSubmittedWithPendingRpc,true);
  main('login','/login');response('login','/login',10);extra('login');
  response('rpc','/connect/Test/a',9);extra('rpc');assert.equal(driver.authDiagnostics.cookieDiagnostics.otherSessionCookieSetAfterLogin,false); // Late event, earlier network response.
  driver.event('Network.loadingFinished',{requestId:'rpc',encodedDataLength:0});driver.cookieObservation.snapshotPending();assert.equal(driver.authDiagnostics.cookieDiagnostics.loginSubmittedWithPendingRpc,false);
  main('later','/connect/Test/b');response('later','/connect/Test/b',11);
  assert.equal(driver.authDiagnostics.cookieDiagnostics.otherSessionCookieSetAfterLogin,null);extra('later');assert.equal(driver.authDiagnostics.cookieDiagnostics.otherSessionCookieSetAfterLogin,true);
  driver.event('Network.responseReceivedExtraInfo',{requestId:'later',statusCode:200,headers:{'Set-Cookie':34},blockedCookies:[]});
  assert.equal(driver.authDiagnostics.cookieDiagnostics.otherSessionCookieSetAfterLogin,null);
  assert(!JSON.stringify([...driver.cookieObservation.metadata.values()]).includes('PRIVATE'));
  for(let i=0;i<256;i++)driver.cookieObservation.pendingRpc.add('pending'+i);
  driver.cookieObservation.output.otherSessionCookieSetAfterLogin=false;
  driver.cookieObservation.main('overflow','profile',false,true);
  assert.equal(driver.authDiagnostics.cookieDiagnostics.otherSessionCookieSetAfterLogin,null);
  driver.cookieObservation.snapshotPending();assert.equal(driver.authDiagnostics.cookieDiagnostics.loginSubmittedWithPendingRpc,null);
});
test('cookie projections ignore anonymous/external requests and bounded overflow stays unknown',()=>{
  const driver=new FixtureUiDriver({request:async()=>({})},config);
  for(let i=0;i<300;i++)driver.event('Network.requestWillBeSent',{requestId:'asset'+i,request:{url:config.origin+'/asset'+i,method:'GET'}});
  for(let i=0;i<300;i++)driver.event('Network.requestWillBeSentExtraInfo',{requestId:'asset'+i,headers:{Cookie:'PRIVATE'},associatedCookies:[]});
  assert(driver.cookieObservation.ignored.size<=256);assert(driver.cookieObservation.earlyRequest.size<=4);
  for(let i=0;i<8;i++)driver.event('Network.responseReceivedExtraInfo',{requestId:'unknown'+i,statusCode:200,headers:{'Set-Cookie':'JSESSIONID=PRIVATE'},blockedCookies:[]});
  driver.event('Network.requestWillBeSent',{requestId:'unknown7',request:{url:config.origin+'/login',method:'POST'}});
  assert.deepEqual(driver.authDiagnostics.cookieDiagnostics,unknownCookies);
  driver.event('Network.requestWillBeSent',{requestId:'external',request:{url:'https://external.invalid/login',method:'POST'}});
  driver.event('Network.responseReceivedExtraInfo',{requestId:'external',statusCode:200,headers:{'Set-Cookie':'JSESSIONID=PRIVATE'},blockedCookies:[]});
  assert.deepEqual(driver.authDiagnostics.cookieDiagnostics,unknownCookies);
  driver.event('Network.requestWillBeSent',{requestId:'redirect',request:{url:config.origin+'/login',method:'POST'}});
  driver.event('Network.requestWillBeSent',{requestId:'redirect',redirectResponse:{url:config.origin+'/login',status:302},request:{url:config.origin+'/',method:'GET'}});
  driver.event('Network.responseReceivedExtraInfo',{requestId:'redirect',statusCode:200,headers:{'Set-Cookie':'JSESSIONID=PRIVATE'},blockedCookies:[]});
  assert.deepEqual(driver.authDiagnostics.cookieDiagnostics,unknownCookies);
});
test('userinfo body diagnostic is one correlated post-login completed response under original deadline',async()=>{
  const calls=[];const cdp={request:async(method,params,timeout)=>{calls.push({method,params,timeout});return {body:'null',base64Encoded:false};}};
  const driver=new FixtureUiDriver(cdp,config);driver.postSubmitDeadline=performance.now()+1234;
  const observe=(id,origin=config.origin)=>{
    driver.event('Network.requestWillBeSent',{requestId:id,request:{url:origin+'/connect/UserEndpoint/getUserInfo',method:'POST'}});
    driver.event('Network.responseReceived',{requestId:id,response:{url:origin+'/connect/UserEndpoint/getUserInfo',status:200,headers:{Cookie:'PRIVATE'}}});
    driver.event('Network.loadingFinished',{requestId:id,encodedDataLength:4});
  };
  observe('before');observe('external','https://external.invalid');assert.equal(calls.length,0);
  driver.event('Network.requestWillBeSent',{requestId:'late-before',request:{url:config.origin+'/connect/UserEndpoint/getUserInfo',method:'POST'}});
  driver.authDiagnostics.loginPostStatus=200;driver.authDiagnostics.loginResultHeader='success';driver.authStage='profile-proof';
  driver.event('Network.responseReceived',{requestId:'late-before',response:{url:config.origin+'/connect/UserEndpoint/getUserInfo',status:200}});
  driver.event('Network.loadingFinished',{requestId:'late-before',encodedDataLength:4});assert.equal(calls.length,0);
  observe('accepted');observe('second');await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls.length,1);assert.equal(calls[0].method,'Network.getResponseBody');assert.deepEqual(calls[0].params,{requestId:'accepted'});assert(calls[0].timeout<=1234);
  assert.equal(driver.authDiagnostics.userInfoIdentity,'null');assert(!JSON.stringify(safeAuthDiagnostics(driver.authDiagnostics)).includes('PRIVATE'));
});
test('only correlated same-origin login POST status survives response and redirect events',()=>{
  const driver=new FixtureUiDriver({request:async()=>({})},config);
  driver.event('Network.responseReceived',{requestId:'get',response:{url:config.origin+'/login',status:200,headers:{Cookie:'secret'}}});
  assert.equal(driver.authDiagnostics.loginPostStatus,null);
  driver.event('Fetch.requestPaused',{requestId:'fetch',request:{url:config.origin+'/login',method:'POST',postData:'secret'}});
  driver.event('Network.requestWillBeSent',{requestId:'post',request:{url:config.origin+'/login',method:'POST',postData:'secret'}});
  driver.event('Network.requestWillBeSent',{requestId:'post',redirectResponse:{url:config.origin+'/login',status:302,headers:{Location:'secret'}},request:{url:config.origin+'/',method:'GET'}});
  driver.event('Network.responseReceived',{requestId:'post',response:{url:config.origin+'/',status:200}});
  assert.equal(driver.authDiagnostics.loginPostObserved,true);assert.equal(driver.authDiagnostics.loginPostStatus,302);assert.equal(driver.loginRequestIds.size,0);
  assert(!JSON.stringify(safeAuthDiagnostics(driver.authDiagnostics)).includes('secret'));
  driver.event('Network.requestWillBeSent',{requestId:'user',request:{url:config.origin+'/connect/UserEndpoint/getUserInfo',method:'POST',postData:'secret'}});
  driver.event('Network.responseReceived',{requestId:'user',response:{url:config.origin+'/connect/UserEndpoint/getUserInfo',status:401,headers:{'Set-Cookie':'secret'}}});
  assert.equal(driver.authDiagnostics.userInfoStatus,401);assert.equal(driver.userInfoRequestIds.size,0);
});
test('navigation progress retains only fixed route indexes, stages and boolean gates',()=>{
  const flags=Object.fromEntries(['pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'].map(key=>[key,false]));
  assert.deepEqual(navigationProgress(1,'content',flags),{routeIndex:1,stage:'content',readiness:flags});
  for(const args of [[0,'navigate'],[4,'navigate'],[1,'secret'],[1,'content',{...flags,coverPresent:'secret'}],[1,'content',{...flags,url:'secret'}]])assert.throws(()=>navigationProgress(...args));
});
test('successful route emits bounded stage sequence and keeps readiness out of timing schema',async()=>{
  const body=Buffer.alloc(371528),progress=[];
  const diagnostic=Object.fromEntries(['pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'].map(key=>[key,true]));
  const cdp={request:async(method)=>{
    if(method==='Page.navigate'){cdp.onEvent('Network.responseReceived',{requestId:'cover',response:{url:config.origin+'/images/cover/7',status:200}});return {};}
    if(method==='Network.getResponseBody')return {body:body.toString('base64'),base64Encoded:true};
    if(method==='Runtime.evaluate')return {result:{value:{ready:true,diagnostic,loadMs:1,coverNaturalWidth:320,coverNaturalHeight:480}}};
    throw new Error('Unexpected unit command');
  }};
  const result=await new FixtureUiDriver(cdp,{...config,coverSha256:createHash('sha256').update(body).digest('hex'),onProgress:row=>progress.push(row)}).navigate('/library/27003');
  assert.deepEqual(progress.map(row=>row.stage),['navigate','content','cover-body','complete']);assert(progress.every(row=>row.routeIndex===1));
  assert.equal(result.coverSha256Matches,true);assert(!('diagnostic' in result));assert.deepEqual(progress[1].readiness,diagnostic);
});
test('interception is enabled before login navigation; no auth API bypass',async()=>{
  const calls=[];const cdp={request:async(method,params)=>{
    if(method==='Runtime.evaluate'&&params.expression.includes('usernameLength:u.value.length')){
      cdp.onEvent('Network.requestWillBeSent',{requestId:'accepted',request:{url:config.origin+'/login',method:'POST'}});
      cdp.onEvent('Network.responseReceived',{requestId:'accepted',response:{url:config.origin+'/login',status:200,headers:{Result:'success'}}});
    }
    calls.push([method,params]);return method==='Runtime.evaluate'?{result:{value:params.expression.includes('usernameLength:u.value.length')?{clicked:true,usernameLength:'fixture-user'.length,passwordLength:'private-random-secret-12345'.length}:params.expression.includes('document.readyState')?{ready:true,diagnostic:readyAuth}:params.expression.includes('document.baseURI')?{ready:true,diagnostic:readyProfile}:params.expression.includes('Object.values(diagnostic)')?{ready:true,diagnostic:{identityRow:true,adminItem:true,signOutItem:true}}:/\.click\(\)|\.focus\(\)|new Promise/.test(params.expression)?true:{ready:true}}}:{};
  }};
  assert.deepEqual(await new FixtureUiDriver(cdp,config).authenticate('fixture-user','private-random-secret-12345'),{normalLoginAutoRedirectObserved:true,fixtureProfileVerified:true});
  assert.deepEqual(calls.slice(0,4).map(c=>c[0]),['Page.enable','Runtime.enable','Network.enable','Fetch.enable']);
  assert.equal(calls[4][0],'Page.addScriptToEvaluateOnNewDocument');assert.equal(calls[5][0],'Page.navigate');assert.equal(calls[5][1].url,config.origin+'/login');
  assert.ok(calls.every(c=>!String(c[1]?.url).includes('/connect/')));
  const typed=calls.filter(([method])=>method==='Input.insertText');
  assert.deepEqual(typed.map(([,params])=>params.text),['fixture-user','private-random-secret-12345']);
  const expressions=calls.filter(([method])=>method==='Runtime.evaluate').map(([,params])=>params.expression);
  assert(expressions.every(expression=>!expression.includes('private-random-secret-12345')&&!expression.includes('fixture-user')));
  for(const row of typed){const index=calls.indexOf(row);assert.match(calls[index-1][1].expression,/\.focus\(\)/);assert.match(calls[index+1][1].expression,/requestAnimationFrame/);}
  const click=calls.findIndex(([,params])=>params.expression?.includes('.click()'));
  assert(click>calls.indexOf(typed[1])+1);
  assert.deepEqual(calls.filter(([method])=>method==='Page.navigate').map(([,params])=>params.url),[config.origin+'/login']);
  assert(expressions.some(expression=>expression.includes("==='My Profile'")&&expression.includes('targets[0].click()')));
});
test('profile identity probe reads only fixed fixture identity, never submits profile forms or loads covers',()=>{
  const profile=fixtureProfileExpression(config.origin),identity=fixtureIdentityMenuExpression();
  assert(profile.includes('/settings/profile'));assert(!profile.includes('.value'));assert(!profile.includes('.click()'));assert(!profile.includes('images/cover'));
  assert(identity.includes('Signed in as fixture-admin'));assert(identity.includes('Administration'));assert(identity.includes('Sign Out'));assert(!/\.value\b/.test(identity));
  const evaluateIdentity=labels=>vm.runInNewContext(identity,{document:{querySelectorAll:()=>labels.map(textContent=>({textContent}))}}).ready;
  assert.equal(evaluateIdentity(['Signed in as fixture-admin','Administration','Sign Out']),true);
  assert.equal(evaluateIdentity(['Signed in as another-account','Administration','Sign Out']),false);
  assert.equal(evaluateIdentity(['Signed in as fixture-admin','Sign Out']),false);
});
test('profile DOM diagnostics distinguish multiple triggers and preserve strict rejection',()=>{
  const origin=config.origin;let count=2;
  const document={baseURI:origin+'/',querySelector:selector=>selector.includes('Password')||selector.includes('passwordRepeat')?{type:'password'}:{},querySelectorAll:selector=>selector==='h2'?[{textContent:'My Profile'}]:selector.startsWith('nav ')?Array.from({length:count},()=>({getClientRects:()=>[1],querySelectorAll:()=>[{}],closest:()=>({})})):[]};
  const evaluate=()=>vm.runInNewContext(fixtureProfileExpression(origin),{document,location:{origin,pathname:'/settings/profile'},URL});
  const rejected=evaluate();assert.equal(rejected.ready,false);assert.equal(rejected.diagnostic.triggerCount,'multiple');assert.equal(rejected.diagnostic.baseRootMatches,true);
  count=1;assert.equal(evaluate().ready,true);assert.equal(evaluate().diagnostic.identityRow,false);
});
test('source-backed profile trigger rejects hidden, missing-avatar and ambiguous wrappers',async()=>{
  let expression;const driver=new FixtureUiDriver({request:async(method,params)=>{expression=params.expression;return {result:{value:true}};}},config);
  await driver.openProfileMenu();
  let clicks=0;
  const wrapper=(visible,avatars)=>({getClientRects:()=>visible?[1]:[],querySelectorAll:selector=>{assert.equal(selector,'button.size-8');return Array.from({length:avatars},()=>({}));},click:()=>{clicks++;}});
  const evaluate=rows=>vm.runInNewContext(expression,{location:{origin:config.origin,pathname:'/settings/profile'},document:{querySelectorAll:selector=>{assert.equal(selector,'nav [data-slot="trigger"]');return rows;}}});
  for(const rows of [[],[wrapper(false,1)],[wrapper(true,0)],[wrapper(true,2)],[wrapper(true,1),wrapper(true,1)]])assert.equal(evaluate(rows),false);
  assert.equal(clicks,0);assert.equal(evaluate([wrapper(true,0),wrapper(true,1)]),true);assert.equal(clicks,1);
});
test('late accepted login stays within original budget and explicitly retains missing automatic landing',async()=>{
  let timer,accepted=false,profileStarted=false;const documentNavigations=[];
  const cdp={request:async(method,params)=>{
    if(method==='Page.navigate'){documentNavigations.push(params.url);assert.equal(params.url,config.origin+'/login');return {};}
    if(method!=='Runtime.evaluate')return {};
    if(params.expression.includes('usernameLength:u.value.length')){
      cdp.onEvent('Network.requestWillBeSent',{requestId:'late',request:{url:config.origin+'/login',method:'POST'}});
      timer=setTimeout(()=>{accepted=true;cdp.onEvent('Network.responseReceived',{requestId:'late',response:{url:config.origin+'/login',status:200,headers:{Result:'success'}}});},3200);
      return {result:{value:{clicked:true,usernameLength:'fixture-user'.length,passwordLength:'private-random-secret-12345'.length}}};
    }
    if(params.expression.includes('document.readyState'))return {result:{value:{ready:false,diagnostic:{...readyAuth,loginPath:true,usernamePresent:true,passwordPresent:true,submitPresent:true}}}};
    if(params.expression.includes('document.baseURI'))return {result:{value:{ready:true,diagnostic:readyProfile}}};
    if(params.expression.includes('Object.values(diagnostic)'))return {result:{value:{ready:true,diagnostic:{identityRow:true,adminItem:true,signOutItem:true}}}};
    if(params.expression.includes("==='My Profile'")&&params.expression.includes('targets[0].click()')){assert.equal(accepted,true);profileStarted=true;return {result:{value:true}};}
    return {result:{value:/\.click\(\)|\.focus\(\)|new Promise/.test(params.expression)?true:{ready:true}}};
  }};
  try{assert.deepEqual(await new FixtureUiDriver(cdp,config).authenticate('fixture-user','private-random-secret-12345'),{normalLoginAutoRedirectObserved:false,fixtureProfileVerified:true});assert.equal(profileStarted,true);assert.deepEqual(documentNavigations,[config.origin+'/login']);}finally{clearTimeout(timer);}
});
test('optional redirect observation uses original auth budget even with submillisecond observation window',async()=>{
  const original=globalThis.performance;let clock=0,observationClock=false,observationStart=false,redirectTimeout;
  globalThis.performance={now:()=>{if(observationClock&&!observationStart){observationStart=true;return 0;}return observationClock?2999.5:clock;}};
  const cdp={request:async(method,params,timeout)=>{
    if(method!=='Runtime.evaluate')return {};
    let value=true;
    if(params.expression.includes('usernameLength:u.value.length')){
      cdp.onEvent('Network.requestWillBeSent',{requestId:'accepted',request:{url:config.origin+'/login',method:'POST'}});
      cdp.onEvent('Network.responseReceived',{requestId:'accepted',response:{url:config.origin+'/login',status:200,headers:{Result:'success'}}});
      observationClock=true;value={clicked:true,usernameLength:12,passwordLength:26};
    }else if(params.expression.includes('document.readyState')){
      redirectTimeout=timeout;observationClock=false;clock=3001;value={ready:false,diagnostic:readyAuth};
    }else if(params.expression.includes('document.baseURI'))value={ready:true,diagnostic:readyProfile};
    else if(params.expression.includes('Object.values(diagnostic)'))value={ready:true,diagnostic:{identityRow:true,adminItem:true,signOutItem:true}};
    else if(!/\.click\(\)|\.focus\(\)|new Promise/.test(params.expression))value={ready:true};
    return {result:{value}};
  }};
  try{
    const result=await new FixtureUiDriver(cdp,config).authenticate('fixture-user','private-random-secret-12345');
    assert.equal(result.normalLoginAutoRedirectObserved,false);assert.equal(result.fixtureProfileVerified,true);
    assert.equal(redirectTimeout,12000); // 0.5ms observation remains; original 15s budget still governs CDP.
  }finally{globalThis.performance=original;}
});
test('invalid credentials and missing normal form fail before typing or submission',async()=>{
  const calls=[];const driver=new FixtureUiDriver({request:async(method,params)=>{calls.push([method,params]);return {result:{value:params.expression?.includes('.focus()')?false:{ready:true}}};}},config);
  await assert.rejects(driver.authenticate('fixture','short'),/credential boundary/);assert.equal(calls.length,0);
  await assert.rejects(driver.authenticate('fixture','private-random-secret-12345'),/Normal UI login unavailable/);
  assert.equal(driver.authStage,'submit');assert(!calls.some(([method])=>method==='Input.insertText'));
});
test('logout uses original profile menu and verifies login UI, never a logout API',async()=>{
  const calls=[];const cdp={request:async(method,params)=>{calls.push([method,params]);return {result:{value:params.expression.includes('.click()')?true:{ready:true}}};}};
  assert.deepEqual(await new FixtureUiDriver(cdp,config).logout(),{loggedOut:true});
  assert.equal(calls.length,4);assert(calls.every(([method])=>method==='Runtime.evaluate'));
  assert.match(calls[0][1].expression,/nav \[data-slot/);assert.match(calls[2][1].expression,/Sign Out/);
  assert(calls[0][1].expression.includes('button.size-8'));assert(calls[0][1].expression.includes('length===1'));
  assert(!calls[0][1].expression.includes('[aria-haspopup="dialog"]'));assert(!calls[0][1].expression.includes('[aria-haspopup="listbox"]'));
  assert.match(calls[3][1].expression,/pathname==='\/login'/);assert(!JSON.stringify(calls).includes('/logout'));
});
test('ambiguous or absent profile menu fails closed before any logout attempt',async()=>{
  const calls=[];const driver=new FixtureUiDriver({request:async(method,params)=>{calls.push([method,params]);return {result:{value:false}};}},config);
  await assert.rejects(driver.logout(),/^Error: Normal UI logout unavailable$/);assert.equal(calls.length,1);
});
test('external requests fail closed; headers and arbitrary response fields never retained',async()=>{
  const calls=[];const cdp={request:async(m,p)=>{calls.push([m,p]);return {};}};const driver=new FixtureUiDriver(cdp,config);
  driver.event('Fetch.requestPaused',{requestId:'owned1',request:{url:'https://external.invalid/',headers:{Cookie:'PRIVATE'}}});
  assert.equal(calls[0][0],'Fetch.failRequest');assert.equal(JSON.stringify(calls).includes('PRIVATE'),false);
  driver.event('Network.responseReceived',{requestId:'cover1',response:{url:config.origin+'/images/cover/7',status:200,headers:{Cookie:'PRIVATE'}}});
  assert.equal(JSON.stringify(driver.cover).includes('PRIVATE'),false);
});
test('production IDs and fourth navigation denied; connected runtime remains disabled',async()=>{
  const driver=new FixtureUiDriver({request:async()=>{}},config);
  await assert.rejects(driver.navigate('/game/2055'),/bound/);driver.navigations=3;
  await assert.rejects(driver.navigate('/game/33000'),/bound/);assert.throws(connectAndMeasure,/disabled/);
});
test('all commands share the remaining total budget and expiry prevents forwarding',async()=>{
  const calls=[];const driver=new FixtureUiDriver({request:async(m,p,t)=>{calls.push([m,t]);return {}; }},config);
  driver.totalDeadline=performance.now()+1000;
  await driver.enableBeforeAuth();await driver.request('Page.navigate',{});await driver.request('Network.getResponseBody',{});
  assert.equal(calls.length,7);assert(calls.every(([,t])=>t>0&&t<=1000));
  driver.totalDeadline=performance.now()-1;
  await assert.rejects(driver.request('Page.navigate'),/deadline/);
  driver.event('Fetch.requestPaused',{requestId:'owned',request:{url:config.origin+'/'}});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(driver.failure,true);assert.equal(calls.length,7);
});

test('classified continuation and denial failures stay blocking with exactly one attempt and sanitized evidence',async()=>{
 for(const allowed of [true,false]){
  const calls=[];let driver;
  const cdp={request:async(method,params)=>{
   calls.push({method,params});const failure=new Error('Invalid InterceptionId. PRIVATE_COOKIE=secret');
   recordFirstAuthCdpFailure(driver.authDiagnostics,method,failure);throw failure;
  }};
  driver=new FixtureUiDriver(cdp,config);
  driver.event('Fetch.requestPaused',{requestId:'owned-request',request:{url:allowed?config.origin+'/login':'https://external.invalid/',headers:{Cookie:'PRIVATE_COOKIE=secret'},postData:'secret'}});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(driver.failure,true);assert.equal(calls.length,1);
  assert.equal(calls[0].method,allowed?'Fetch.continueRequest':'Fetch.failRequest');
  await assert.rejects(driver.poll('fixed expression'),/Origin interception failed/);
  assert.equal(calls.length,1); // no automatic retry, bypass, or extra CDP request
  const evidence=safeAuthDiagnostics(driver.authDiagnostics);
  assert.equal(evidence.interceptionDiagnostics.failedOperation,allowed?'continue':'deny');
  assert.equal(evidence.cdpFailure,'interception-invalid-id');assert.equal(evidence.cdpMethod,'request-interception');
  assert(!JSON.stringify(evidence).includes('PRIVATE'));assert(!JSON.stringify(evidence).includes('secret'));
  assert(!JSON.stringify(calls).includes('PRIVATE'));assert(!JSON.stringify(calls).includes('secret'));
  assert.throws(()=>safeAuthDiagnostics({...driver.authDiagnostics,cdpFailure:'arbitrary-private-message'}));
 }
});

test('interception lifecycle counters are bounded observations, never retry authority or raw IDs',async()=>{
 const calls=[];const driver=new FixtureUiDriver({request:async(method)=>{calls.push(method);}},config);
 for(let i=0;i<260;i++){
  driver.event('Fetch.requestPaused',{requestId:'PRIVATE-ID',request:{url:config.origin+'/'}});
  driver.event('Network.loadingFailed',{requestId:'PRIVATE-ID',canceled:true,errorText:'PRIVATE-TEXT'});
 }
 await new Promise(resolve=>setImmediate(resolve));
 const diagnostic=safeAuthDiagnostics(driver.authDiagnostics).interceptionDiagnostics;
 assert.deepEqual(diagnostic,{failedOperation:'none',failedRequestCanceled:null,pausedEvents:256,duplicatePausedIds:256,canceledNetworkEvents:256,verifiedCanceledImageContinuations:0,truncated:true});
 assert.equal(calls.length,260); // observation never suppresses/deduplicates a paused event
 assert(!JSON.stringify(diagnostic).includes('PRIVATE'));
 for(const mutation of [{failedOperation:'PRIVATE'}, {pausedEvents:257}, {duplicatePausedIds:-1},{canceledNetworkEvents:true},{truncated:1},{rawId:'PRIVATE'}])
  assert.throws(()=>safeAuthDiagnostics({...driver.authDiagnostics,interceptionDiagnostics:{...diagnostic,...mutation}}));
});

test('failed paused request cancellation correlation is specific, bounded, and does not permit retry',async()=>{
 for(const canceled of [true,false]){
  let reject;const driver=new FixtureUiDriver({request:()=>new Promise((_,r)=>{reject=r;})},config);
  driver.event('Fetch.requestPaused',{requestId:'PRIVATE-FETCH',networkId:'PRIVATE-NETWORK',request:{url:config.origin+'/'}});
  driver.event('Network.loadingFailed',{requestId:canceled?'PRIVATE-NETWORK':'UNRELATED',canceled:true});
  reject(new Error('Invalid InterceptionId.'));await new Promise(resolve=>setImmediate(resolve));
  const d=safeAuthDiagnostics(driver.authDiagnostics).interceptionDiagnostics;
  assert.equal(d.failedRequestCanceled,canceled?true:null);assert.equal(d.failedOperation,'continue');assert.equal(driver.failure,true);
  assert(!JSON.stringify(d).includes('PRIVATE'));
 }
});

test('truncated or missing cancellation correlation stays unknown rather than false',async()=>{
 let reject;const driver=new FixtureUiDriver({request:()=>new Promise((_,r)=>{reject=r;})},config);
 for(let i=0;i<257;i++)driver.event('Network.loadingFailed',{requestId:'unrelated-'+i,canceled:true});
 driver.event('Fetch.requestPaused',{requestId:'PRIVATE',networkId:'unobserved',request:{url:config.origin+'/'}});
 reject(new Error('Invalid InterceptionId.'));await new Promise(resolve=>setImmediate(resolve));
 assert.equal(safeAuthDiagnostics(driver.authDiagnostics).interceptionDiagnostics.failedRequestCanceled,null);
 for(const bad of [1,'raw-secret'])assert.throws(()=>safeAuthDiagnostics({...driver.authDiagnostics,interceptionDiagnostics:{...driver.authDiagnostics.interceptionDiagnostics,failedRequestCanceled:bad}}));
});

test('route shell and static error observations never satisfy actual content readiness',async()=>{
 const location={pathname:'/game/33000',origin:config.origin,href:config.origin+'/game/33000'};
 const document={images:[],querySelector:()=>({}),querySelectorAll:selector=>selector==='h1'?[{textContent:'404 – Quest Failed'}]:[]};
 const value=await vm.runInNewContext(observedRouteExpression('/game/33000',7),{location,document,URL});
 assert.equal(value.ready,false);assert.equal(value.diagnostic.contentReady,false);
 assert.deepEqual(JSON.parse(JSON.stringify(value.observation)),{domRootPresent:true,navPresent:true,signInPresent:true,staticErrorViewPresent:true});
 assert(!JSON.stringify(value).includes('Quest'));
});

test('navigation observations classify exact RPC lifecycle and exceptions without bodies or raw identifiers',async()=>{
 const progress=[];const driver=new FixtureUiDriver({request:async()=>({})},{...config,onProgress:v=>progress.push(v)});
 driver.poll=async(expression,observe)=>{
  for(const [i,path] of ['/connect/LibraryEndpoint/getAll','/connect/GameEndpoint/getAll','/connect/UserEndpoint/getUserInfo'].entries()){
   driver.event('Network.requestWillBeSent',{requestId:'PRIVATE-'+i,request:{url:config.origin+path,method:'POST',postData:'PRIVATE-BODY'}});
   driver.event('Network.responseReceived',{requestId:'PRIVATE-'+i,response:{url:config.origin+path,status:i===1?403:200,headers:i===1?{'X-Gameyfin-Fixture-Proxy-Deny':'capacity'}:{}}});
   driver.event(i===2?'Network.loadingFailed':'Network.loadingFinished',{requestId:'PRIVATE-'+i});
  }
  driver.event('Runtime.exceptionThrown',{exceptionDetails:{text:'PRIVATE-ERROR'}});
  for(const id of ['older','latest'])driver.event('Network.requestWillBeSent',{requestId:id,request:{url:config.origin+'/connect/LibraryEndpoint/getAll',method:'POST'}});
  driver.event('Network.responseReceived',{requestId:'latest',response:{url:config.origin+'/connect/LibraryEndpoint/getAll',status:403}});
  driver.event('Network.loadingFinished',{requestId:'latest'});
  driver.event('Network.responseReceived',{requestId:'older',response:{url:config.origin+'/connect/LibraryEndpoint/getAll',status:200}});
  driver.event('Network.loadingFinished',{requestId:'older'});
  const diagnostic=Object.fromEntries(['pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'].map(k=>[k,k==='pathMatches']));
  observe({ready:false,diagnostic,observation:{domRootPresent:true,navPresent:true,signInPresent:false,staticErrorViewPresent:false}});
  throw new Error('bounded fixture stop');
 };
 await assert.rejects(driver.navigate('/game/33000'),/bounded fixture stop/);
 const observation=progress.at(-1).observation;
 assert.deepEqual(observation.library,{status:'4xx',completion:'finished',httpStatus:403,proxyDeny:'none'});
 assert.deepEqual(observation.game,{status:'4xx',completion:'finished',httpStatus:403,proxyDeny:'capacity'});
 assert.deepEqual(observation.profile,{status:'2xx',completion:'failed',httpStatus:200,proxyDeny:'none'});
 assert.equal(observation.uncaughtExceptionSeen,true);assert(!JSON.stringify(progress).includes('PRIVATE'));
 for(let i=0;i<40;i++)driver.event('Network.requestWillBeSent',{requestId:'bounded-'+i,request:{url:config.origin+'/connect/GameEndpoint/getAll',method:'POST'}});
 assert.equal(driver.navigationRequestCount,32);assert(driver.navigationRequests.size<=32);assert.equal(driver.navigationState.truncated,true);
 for(const bad of [{domRootPresent:1},{uncaughtExceptionSeen:'PRIVATE'},{raw:'PRIVATE'},{game:{status:403,completion:'finished'}},{game:{status:'2xx',completion:'PRIVATE'}}])assert.throws(()=>navigationObservation({...observation,...bad}));
 const inherited=Object.assign(Object.create({status:'2xx',completion:'finished'}),{raw:'PRIVATE',other:'PRIVATE'});
 assert.throws(()=>navigationObservation({...observation,game:inherited}));
 const hidden=Object.defineProperty({status:'2xx',completion:'finished'},'raw',{value:'PRIVATE',enumerable:false});
 assert.deepEqual(navigationObservation({...observation,game:hidden}).game,{status:'2xx',completion:'finished'});
});

test('proxy observations accept bounded counters only, never raw denial text',()=>{
 const value={requests:256,active:8,queued:8,peakActive:8,peakQueued:8,sockets:24,reservedSockets:8,peakSockets:32,outboundSockets:0,peakOutboundSockets:8,idleSocketsReclaimed:0,socketRefusals:0,deniedCapacity:1,deniedRequestBudget:0,deniedPolicy:0,deniedDeadline:0,deniedClosed:0};
 assert.deepEqual(safeProxyDiagnostics(value),value);
 for(const bad of [{requests:257},{active:9},{queued:9},{sockets:25},{peakSockets:33},{reservedSockets:9},{outboundSockets:1},{outboundSockets:9},{peakOutboundSockets:9},{peakOutboundSockets:true},{idleSocketsReclaimed:257},{socketRefusals:true},{deniedPolicy:true},{raw:'secret'}])assert.throws(()=>safeProxyDiagnostics({...value,...bad}));
 const inherited=Object.assign(Object.create({requests:1}),value);delete inherited.requests;inherited.raw='PRIVATE';assert.throws(()=>safeProxyDiagnostics(inherited));
});
