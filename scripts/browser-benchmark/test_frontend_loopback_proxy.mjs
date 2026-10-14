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
test('actual HTTP relay preserves fixture session cookies and separate Set-Cookie headers',async()=>{
  const seen=[];
  const setCookies=['JSESSIONID=rotated-fixture; Path=/; HttpOnly; SameSite=Lax','fixture-extra=constant; Path=/; HttpOnly'];
  const upstream=http.createServer((req,res)=>{
    seen.push({cookie:req.headers.cookie,source:req.headers.source,path:req.url});
    req.resume();res.writeHead(200,{'Result':'success','Set-Cookie':setCookies});res.end('null');
  });
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:54321';
  // Only the test's upstream transport substitutes its ephemeral local port.
  // Runtime target validation, Host handling and response forwarding are real.
  const proxy=new LoopbackProxy(origin,{request:(options,reply)=>{
    assert.equal(options.hostname,'127.0.0.1');assert.equal(options.port,54321);
    return http.request({...options,port:upstream.address().port},reply);
  }});
  const port=await proxy.listen();
  const request=(path,cookie)=>new Promise((resolve,reject)=>{
    const req=http.request({host:'127.0.0.1',port,path:origin+path,method:'POST',headers:{Host:'127.0.0.1:54321',Cookie:cookie,source:'typescript'}},res=>{
      res.resume();res.on('end',()=>resolve({status:res.statusCode,cookies:res.headers['set-cookie'],result:res.headers.result}));
    });req.setTimeout(1000,()=>req.destroy(new Error('Fixture relay deadline')));req.on('error',reject);req.end();
  });
  try{
    const login=await request('/login','JSESSIONID=initial-fixture');
    assert.deepEqual(login,{status:200,cookies:setCookies,result:'success'});
    await request('/connect/UserEndpoint/getUserInfo','JSESSIONID=rotated-fixture');
    assert.deepEqual(seen,[{cookie:'JSESSIONID=initial-fixture',source:'typescript',path:'/login'},{cookie:'JSESSIONID=rotated-fixture',source:'typescript',path:'/connect/UserEndpoint/getUserInfo'}]);
  }finally{proxy.close();await new Promise(resolve=>upstream.close(resolve));}
});
