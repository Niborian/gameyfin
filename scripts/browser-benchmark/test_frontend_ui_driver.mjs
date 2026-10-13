import test from 'node:test';import assert from 'node:assert/strict';
import {FixtureUiDriver,connectAndMeasure} from './frontend_ui_driver.mjs';
const config={origin:'http://127.0.0.1:53123',coverId:7,coverSha256:'0'.repeat(64)};
test('interception is enabled before login navigation; no auth API bypass',async()=>{
  const calls=[];const cdp={request:async(method,params)=>{
    calls.push([method,params]);return method==='Runtime.evaluate'?{result:{value:/\.click\(\)|\.focus\(\)|new Promise/.test(params.expression)?true:{ready:true}}}:{};
  }};
  await new FixtureUiDriver(cdp,config).authenticate('fixture-user','private-random-secret-12345');
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
});
test('invalid credentials and missing normal form fail before typing or submission',async()=>{
  const calls=[];const driver=new FixtureUiDriver({request:async(method,params)=>{calls.push([method,params]);return {result:{value:params.expression?.includes('.focus()')?false:{ready:true}}};}},config);
  await assert.rejects(driver.authenticate('fixture','short'),/credential boundary/);assert.equal(calls.length,0);
  await assert.rejects(driver.authenticate('fixture','private-random-secret-12345'),/Normal UI login unavailable/);
  assert.equal(driver.authStage,'submit');assert(!calls.some(([method])=>method==='Input.insertText'));
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
