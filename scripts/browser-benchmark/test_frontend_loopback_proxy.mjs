import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';import net from 'node:net';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
test('actual owned loopback listener rejects HTTP/WS/CONNECT without any upstream dial',async()=>{
  let calls=0;const proxy=new LoopbackProxy('http://127.0.0.1:54321',{dial:()=>{calls++;throw new Error();},request:()=>{calls++;throw new Error();}});
  const port=await proxy.listen();try{
    const status=await new Promise((resolve,reject)=>{http.get({host:'127.0.0.1',port,path:'http://external.invalid/x',headers:{host:'external.invalid'}},r=>{r.resume();resolve(r.statusCode);}).on('error',reject);});assert.equal(status,403);
    for(const wire of ['CONNECT external.invalid:443 HTTP/1.1\r\nHost: external.invalid\r\n\r\n','GET http://external.invalid/x HTTP/1.1\r\nHost: external.invalid\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n']){
      await new Promise((resolve,reject)=>{const s=net.connect({host:'127.0.0.1',port},()=>s.write(wire));s.setTimeout(1000,()=>{s.destroy();reject(new Error('Test deadline'));});s.on('close',resolve);s.on('error',reject);});
    }assert.equal(calls,0);
  }finally{proxy.close();}assert.equal(proxy.closed,true);
});
test('aggregate byte budget stops the entire owned proxy',()=>{
 const proxy=new LoopbackProxy('http://127.0.0.1:54321');
 assert.equal(proxy.consume(64*1024*1024),true);assert.equal(proxy.consume(1),false);
 assert.equal(proxy.closed,true);assert.equal(proxy.budgetExceeded,true);
});
