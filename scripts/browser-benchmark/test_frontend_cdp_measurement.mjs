import test from 'node:test';
import assert from 'node:assert/strict';
import {BoundedCdp,allowRequest,loginExpression,routeExpression,sanitizeMetrics,coverHashMatches,launchRuntime,RUNTIME_ENABLED} from './frontend_cdp_measurement.mjs';
test('runtime disabled; external origins and production destinations denied',()=>{
  assert.equal(RUNTIME_ENABLED,false);assert.throws(launchRuntime);
  const origin='http://127.0.0.1:53123';
  assert.equal(allowRequest(origin+'/game/33000',origin),true);
  for(const url of ['http://10.0.0.200:31005/game/2055','https://example.com/','http://127.0.0.1:53124','file:///C:/private'])assert.equal(allowRequest(url,origin),false);
  assert.throws(()=>allowRequest(origin,'http://0.0.0.0:53123'));
});
test('normal UI login expression stays private; exact fixture DOM and cover checks',()=>{
  const expression=loginExpression('fixture-user','private-random-secret-12345');
  assert.match(expression,/input\[name=/);assert.match(expression,/\.click\(\)/);
  assert.throws(()=>routeExpression('/game/2055',1));
  assert.match(routeExpression('/game/33000',7),/image\.decode\(\)/);
  assert.match(routeExpression('/library/27003',7),/\/images\/cover\/7/);
});
test('strict sanitized schema never silently emits credentials or arbitrary error text',()=>{
  assert.deepEqual(sanitizeMetrics({loadMs:42,coverSha256Matches:true}),{loadMs:42,coverSha256Matches:true});
  for(const value of [{url:'http://secret'},{cookie:'private'},{loadMs:NaN},{loadMs:-1},{exception:'private-random-secret-12345'}])assert.throws(()=>sanitizeMetrics(value));
  assert.throws(()=>coverHashMatches('body',false,'0'.repeat(64)));
});
test('request IDs correlate; error bodies are never propagated',async()=>{
  let sent;const transport={send:m=>{sent=JSON.parse(m);},close:()=>{}};
  const cdp=new BoundedCdp(transport,50);const result=cdp.request('Page.enable');
  transport.onMessage(JSON.stringify({id:sent.id,result:{accepted:true}}));assert.deepEqual(await result,{accepted:true});
  const error=cdp.request('Page.enable');transport.onMessage(JSON.stringify({id:sent.id,error:{message:'secret'}}));await assert.rejects(error,/^Error: CDP operation failed$/);cdp.close();
});
test('deadline closes only owned transport and rejects pending commands',async()=>{
  let closed=0;const transport={send:()=>{},close:()=>closed++};const cdp=new BoundedCdp(transport,5);
  await assert.rejects(cdp.request('Page.enable'),/deadline/);assert.equal(closed,1);cdp.close();assert.equal(closed,1);
  await assert.rejects(cdp.request('Browser.close'),/boundary/);
});
