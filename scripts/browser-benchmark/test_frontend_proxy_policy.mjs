import test from 'node:test';import assert from 'node:assert/strict';
import {fixtureTarget,fixtureConnectTarget,guardedForward,chromeProxyFlags,launchProxy} from './frontend_proxy_policy.mjs';
const origin='http://127.0.0.1:54321',base={method:'GET',url:origin+'/socket',host:'127.0.0.1:54321'};
test('HTTP and WebSocket share the exact literal fixture dial target',()=>{
  assert.deepEqual(fixtureTarget(base,origin),{hostname:'127.0.0.1',port:54321,path:'/socket',websocket:false});
  assert.equal(fixtureTarget({...base,upgrade:'websocket'},origin).websocket,true);
  assert.equal(fixtureTarget({...base,url:'ws://127.0.0.1:54321/socket',upgrade:'websocket'},origin).websocket,true);
});
test('CONNECT permits only exact fixture authority and Host, literal target',()=>{
 assert.deepEqual(fixtureConnectTarget({method:'CONNECT',url:'127.0.0.1:54321',host:'127.0.0.1:54321'},origin),{host:'127.0.0.1',port:54321});
 for(const url of ['external.invalid:443','127.0.0.1:54322','localhost:54321','2130706433:54321','127.0.0.1:54321/','user@127.0.0.1:54321','[::1]:54321'])assert.throws(()=>fixtureConnectTarget({method:'CONNECT',url,host:url},origin));
 assert.throws(()=>fixtureConnectTarget({method:'CONNECT',url:'127.0.0.1:54321',host:'external.invalid'},origin));
});
test('denied HTTP, WebSocket, CONNECT and authority variants never invoke forwarding',async()=>{
  let calls=0;const forward=()=>++calls;
  for(const item of [
    {...base,url:'http://example.org/socket'}, {...base,url:'http://example.org/socket',upgrade:'websocket'},
    {...base,method:'CONNECT',url:'example.org:443'}, {...base,url:'https://127.0.0.1:54321/socket'},
    {...base,url:'http://localhost:54321/socket'}, {...base,url:'http://2130706433:54321/socket'},
    {...base,url:'http://127.0.0.1:54322/socket'}, {...base,host:'example.org'},
    {...base,url:'http://user@127.0.0.1:54321/socket'}, {...base,url:origin+'/x#fragment'},
    {...base,url:origin+'/x\\evil'}, {...base,upgrade:'h2c'}, {...base,method:'POST',upgrade:'websocket'},
  ])await assert.rejects(guardedForward(item,origin,forward));
  assert.equal(calls,0);
});
test('proxy flags have no DIRECT fallback and remove implicit loopback bypass',()=>{
  const flags=chromeProxyFlags(54322);assert(flags.includes('--proxy-bypass-list=<-loopback>'));
  assert(!flags.join(' ').includes('direct://'));assert.throws(launchProxy);
});
