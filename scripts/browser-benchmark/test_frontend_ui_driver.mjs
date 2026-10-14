import test from 'node:test';import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import vm from 'node:vm';
import {FixtureUiDriver,connectAndMeasure,navigationProgress,authReadiness,safeAuthDiagnostics,loginResultHeader,fixtureProfileExpression,fixtureIdentityMenuExpression,profileReadiness,profileDiagnostic} from './frontend_ui_driver.mjs';
const config={origin:'http://127.0.0.1:53123',coverId:7,coverSha256:'0'.repeat(64)};
const readyAuth={originMatches:true,loginPath:false,usernamePresent:false,passwordPresent:false,submitPresent:false,submitDisabled:false,invalidCredentialsDisplayed:false,documentReady:'complete'};
const readyProfile={originMatches:true,profilePath:true,loginPath:false,myProfileHeading:true,usernamePresent:true,emailPresent:true,newPasswordPresent:true,passwordRepeatPresent:true,baseRootMatches:true,identityRow:true,adminItem:true,signOutItem:true,triggerCount:'one'};
test('authentication diagnostics expose only status, length matches and fixed readiness',()=>{
  const row={loginPostObserved:true,loginPostStatus:401,submittedUsernameLengthMatches:true,submittedPasswordLengthMatches:true,cdpFailure:'none',cdpMethod:'none',loginResultHeader:'missing',userInfoStatus:null,readiness:readyAuth,profile:{stage:'not-started',readiness:null}};
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
  assert.equal(calls.filter(([method])=>method==='Page.navigate').at(-1)[1].url,config.origin+'/settings/profile');
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
  const document={baseURI:origin+'/',querySelector:selector=>selector.includes('Password')||selector.includes('passwordRepeat')?{type:'password'}:{},querySelectorAll:selector=>selector==='h2'?[{textContent:'My Profile'}]:selector.startsWith('nav ')?Array.from({length:count},()=>({getClientRects:()=>[1]})):[]};
  const evaluate=()=>vm.runInNewContext(fixtureProfileExpression(origin),{document,location:{origin,pathname:'/settings/profile'},URL});
  const rejected=evaluate();assert.equal(rejected.ready,false);assert.equal(rejected.diagnostic.triggerCount,'multiple');assert.equal(rejected.diagnostic.baseRootMatches,true);
  count=1;assert.equal(evaluate().ready,true);assert.equal(evaluate().diagnostic.identityRow,false);
});
test('late accepted login stays within original budget and explicitly retains missing automatic landing',async()=>{
  let timer,accepted=false,profileStarted=false;
  const cdp={request:async(method,params)=>{
    if(method==='Page.navigate'&&params.url.endsWith('/settings/profile')){assert.equal(accepted,true);profileStarted=true;return {};}
    if(method!=='Runtime.evaluate')return {};
    if(params.expression.includes('usernameLength:u.value.length')){
      cdp.onEvent('Network.requestWillBeSent',{requestId:'late',request:{url:config.origin+'/login',method:'POST'}});
      timer=setTimeout(()=>{accepted=true;cdp.onEvent('Network.responseReceived',{requestId:'late',response:{url:config.origin+'/login',status:200,headers:{Result:'success'}}});},3200);
      return {result:{value:{clicked:true,usernameLength:'fixture-user'.length,passwordLength:'private-random-secret-12345'.length}}};
    }
    if(params.expression.includes('document.readyState'))return {result:{value:{ready:false,diagnostic:{...readyAuth,loginPath:true,usernamePresent:true,passwordPresent:true,submitPresent:true}}}};
    if(params.expression.includes('document.baseURI'))return {result:{value:{ready:true,diagnostic:readyProfile}}};
    if(params.expression.includes('Object.values(diagnostic)'))return {result:{value:{ready:true,diagnostic:{identityRow:true,adminItem:true,signOutItem:true}}}};
    return {result:{value:/\.click\(\)|\.focus\(\)|new Promise/.test(params.expression)?true:{ready:true}}};
  }};
  try{assert.deepEqual(await new FixtureUiDriver(cdp,config).authenticate('fixture-user','private-random-secret-12345'),{normalLoginAutoRedirectObserved:false,fixtureProfileVerified:true});assert.equal(profileStarted,true);}finally{clearTimeout(timer);}
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
  assert.match(calls[0][1].expression,/nav \[aria-haspopup/);assert.match(calls[2][1].expression,/Sign Out/);
  assert(calls[0][1].expression.includes('[aria-haspopup="true"]'));assert(calls[0][1].expression.includes('[aria-haspopup="menu"]'));
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
