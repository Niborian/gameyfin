import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';import net from 'node:net';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
import {EventEmitter} from 'node:events';
function fakeHttp(url='http://127.0.0.1:54321/connect/GameEndpoint/getAll'){
 const req=Object.assign(new EventEmitter(),{method:'POST',url,headers:{host:'127.0.0.1:54321'},rawHeaders:['Host','127.0.0.1:54321'],pause(){this.paused=true;},resume(){this.paused=false;},pipe(){},destroy(){this.aborted=true;this.emit('aborted');}});
 const res=Object.assign(new EventEmitter(),{destroyed:false,headersSent:false,writeHead(status,headers){this.status=status;this.headers=headers;this.headersSent=true;},end(){this.emit('close');},destroy(){if(!this.destroyed){this.destroyed=true;this.emit('close');}}});
 return {req,res};
}
function fakeSocket(){return Object.assign(new EventEmitter(),{destroyed:false,setTimeout(){return this;},pipe(){return this;},write(){},destroy(){if(!this.destroyed){this.destroyed=true;this.emit('close');}}});}
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

test('eight held permitted requests queue ninth FIFO without rejecting or exceeding active cap',async()=>{
 const held=[];let admitted;const ready=new Promise(resolve=>{admitted=resolve;});
 const upstream=http.createServer((req,res)=>{req.resume();held.push(res);if(held.length===8)admitted();});
 await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
 const origin='http://127.0.0.1:54321';const proxy=new LoopbackProxy(origin,{deadlineMs:5000,request:(options,reply)=>http.request({...options,port:upstream.address().port},reply)});
 const port=await proxy.listen();const clients=[];
 const request=()=>new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port,path:origin+'/connect/GameEndpoint/getAll',method:'POST',headers:{Host:'127.0.0.1:54321'}},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});clients.push(req);req.setTimeout(2000,()=>req.destroy(new Error('Synthetic deadline')));req.on('error',reject);req.end('{}');});
 let pending=[];let timer;
 try{
  pending=Array.from({length:8},request);
  await Promise.race([ready,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Held-request deadline')),2000);})]);clearTimeout(timer);
  assert.equal(proxy.active,8);
  const ninthSeen=new Promise(resolve=>proxy.server.once('request',resolve));const ninth=request();ninth.catch(()=>{});await ninthSeen;
  assert.equal(proxy.queue?.length,1);assert.equal(held.length,8);
  held[0].end('null');
  const admittedNinth=new Promise(resolve=>upstream.once('request',()=>resolve()));
  await admittedNinth;assert.equal(held.length,9);assert(proxy.active<=8);
  held[8].end('null');assert.equal(await ninth,200);
  for(const res of held)res.end('null');assert.deepEqual(await Promise.all(pending),Array(8).fill(200));
 }finally{clearTimeout(timer);for(const res of held)res.destroy();for(const req of clients)req.destroy();proxy.close();await Promise.allSettled(pending);await new Promise(resolve=>upstream.close(resolve));}
});

test('queue fullness, foreign origin and total count reject before forwarding with fixed markers',()=>{
 let forwards=0;const proxy=new LoopbackProxy('http://127.0.0.1:54321',{request:()=>{forwards++;throw new Error();}});proxy.active=8;
 try{
  for(let i=0;i<8;i++){const pair=fakeHttp();proxy.handle(pair.req,pair.res);}
  const full=fakeHttp();proxy.handle(full.req,full.res);assert.equal(full.res.status,403);assert.equal(full.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'capacity');
  const foreign=fakeHttp('http://external.invalid/private');proxy.handle(foreign.req,foreign.res);assert.equal(foreign.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'policy');
  assert.equal(proxy.queue.length,8);assert.equal(proxy.count,10);assert.equal(forwards,0);
  proxy.count=256;const budget=fakeHttp();proxy.handle(budget.req,budget.res);assert.equal(budget.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'request-budget');assert.equal(proxy.count,256);
  assert.equal(proxy.diagnostics().deniedCapacity,1);assert.equal(proxy.diagnostics().deniedPolicy,1);assert.equal(proxy.diagnostics().deniedRequestBudget,1);
 }finally{proxy.close();}assert.equal(proxy.queue.length,0);assert.equal(forwards,0);
});

test('queued deadline, client close and proxy close never forward canceled work',async()=>{
 let forwards=0;const proxy=new LoopbackProxy('http://127.0.0.1:54321',{requestDeadlineMs:20,request:()=>{forwards++;throw new Error();}});proxy.active=8;
 const expired=fakeHttp();const ended=new Promise(resolve=>expired.res.once('close',resolve));proxy.handle(expired.req,expired.res);
 await ended;assert.equal(expired.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'deadline');assert.equal(proxy.queue.length,0);
 const canceled=fakeHttp();proxy.handle(canceled.req,canceled.res);canceled.res.destroy();assert.equal(proxy.queue.length,0);
 const closed=fakeHttp();proxy.handle(closed.req,closed.res);proxy.close();assert.equal(closed.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'closed');assert.equal(proxy.queue.length,0);
 proxy.active=0;proxy.drain();assert.equal(forwards,0);
});

test('expired queue entry cannot obtain a fresh deadline on admission',()=>{
 let forwards=0;const proxy=new LoopbackProxy('http://127.0.0.1:54321',{request:()=>{forwards++;throw new Error();}});proxy.active=8;
 try{const pair=fakeHttp();proxy.handle(pair.req,pair.res);proxy.queue[0].deadline=performance.now()-1;proxy.active=0;proxy.drain();assert.equal(forwards,0);assert.equal(pair.res.headers['X-Gameyfin-Fixture-Proxy-Deny'],'deadline');assert.equal(proxy.queue.length,0);}finally{proxy.close();}
});

test('CONNECT and WebSocket terminal sockets both release capacity and drain HTTP queue once',()=>{
 for(const kind of ['connect','upgrade']){
  const upstream=fakeSocket();let forwarded=0;const proxy=new LoopbackProxy('http://127.0.0.1:54321',{dial:()=>upstream,request:()=>{forwarded++;throw new Error('Synthetic forward failure');}});proxy.active=7;
  const socket=fakeSocket();const req={method:kind==='connect'?'CONNECT':'GET',url:kind==='connect'?'127.0.0.1:54321':'ws://127.0.0.1:54321/HILLA/push',headers:{host:'127.0.0.1:54321',...(kind==='upgrade'?{upgrade:'websocket'}:{})},rawHeaders:['Host','127.0.0.1:54321']};
  try{proxy[kind](req,socket,Buffer.alloc(0));assert.equal(proxy.active,8);const pair=fakeHttp();proxy.handle(pair.req,pair.res);assert.equal(proxy.queue.length,1);upstream.destroy();assert.equal(forwarded,1);assert.equal(proxy.queue.length,0);assert.equal(proxy.active,7);upstream.emit('close');assert.equal(proxy.active,7);}finally{proxy.close();}
 }
});

test('upstream cannot forge the local proxy deny marker and request deadline cannot be raised',()=>{
 assert.throws(()=>new LoopbackProxy('http://127.0.0.1:54321',{requestDeadlineMs:15001}));
 const reply=Object.assign(new EventEmitter(),{headers:{'x-gameyfin-fixture-proxy-deny':'capacity','set-cookie':['a=1','b=2']},statusCode:200,pipe(res){res.end();}});
 const upstream=Object.assign(new EventEmitter(),{setTimeout(){},destroy(){}});
 const proxy=new LoopbackProxy('http://127.0.0.1:54321',{request:(_options,callback)=>{queueMicrotask(()=>callback(reply));return upstream;}});
 const pair=fakeHttp();proxy.handle(pair.req,pair.res);
 return new Promise(resolve=>queueMicrotask(()=>{assert.equal(pair.res.headers['x-gameyfin-fixture-proxy-deny'],undefined);assert.deepEqual(pair.res.headers['set-cookie'],['a=1','b=2']);proxy.close();resolve();}));
});

test('diagnostic reproduction: total request budget rejects257th even with zero active',()=>{
 const proxy=new LoopbackProxy('http://127.0.0.1:54321');
 const req={method:'POST',url:'http://127.0.0.1:54321/connect/GameEndpoint/getAll',headers:{host:'127.0.0.1:54321'},rawHeaders:['Host','127.0.0.1:54321']};
 try{for(let i=0;i<256;i++)assert.equal(proxy.target(req).hostname,'127.0.0.1');assert.equal(proxy.active,0);assert.throws(()=>proxy.target(req),/Proxy bound/);}finally{proxy.close();}
});

test('request2MiB and response16MiB byte barriers still terminate owned forwarding',async()=>{
 for(const response of [false,true]){
  let callback;let destroyed=0;const upstream=Object.assign(new EventEmitter(),{setTimeout(){},destroy(){destroyed++;}});
  const proxy=new LoopbackProxy('http://127.0.0.1:54321',{request:(_options,cb)=>{callback=cb;return upstream;}});
  const pair=fakeHttp();proxy.handle(pair.req,pair.res);
  if(response){const reply=Object.assign(new EventEmitter(),{statusCode:200,headers:{},pipe(){}});callback(reply);reply.emit('data',Buffer.alloc(16*1024*1024+1));}
  else pair.req.emit('data',Buffer.alloc(2*1024*1024+1));
  assert(destroyed>0);assert.equal(pair.res.destroyed,true);assert.equal(proxy.active,0);proxy.close();
 }
});

test('late upstream response after canceled client cannot write or revive work',()=>{
 let callback;const upstream=Object.assign(new EventEmitter(),{setTimeout(){},destroy(){}});
 const proxy=new LoopbackProxy('http://127.0.0.1:54321',{request:(_options,cb)=>{callback=cb;return upstream;}});
 const pair=fakeHttp();proxy.handle(pair.req,pair.res);pair.res.destroy();let disposed=false;
 callback({destroy(){disposed=true;},get headers(){throw new Error('Late response must not be inspected');}});
 assert.equal(disposed,true);assert.equal(proxy.active,0);proxy.close();
});

test('CONNECT and upgrade socket-capacity or closed tracking refusal releases once without crash',()=>{
 for(const kind of ['connect','upgrade'])for(const closed of [false,true]){
  const upstream=fakeSocket();let proxy;let drained=0;
  proxy=new LoopbackProxy('http://127.0.0.1:54321',{dial:()=>{if(closed)proxy.close();return upstream;}});proxy.active=7;
  if(!closed){for(let i=0;i<32;i++)proxy.sockets.add(fakeSocket());proxy.queue.push({queued:true,req:{aborted:false},res:{destroyed:false},deadline:performance.now()+1000});proxy.forward=()=>{drained++;};}
  const socket=fakeSocket();const req={method:kind==='connect'?'CONNECT':'GET',url:kind==='connect'?'127.0.0.1:54321':'ws://127.0.0.1:54321/HILLA/push',headers:{host:'127.0.0.1:54321',...(kind==='upgrade'?{upgrade:'websocket'}:{})},rawHeaders:['Host','127.0.0.1:54321']};
  try{assert.doesNotThrow(()=>proxy[kind](req,socket,Buffer.alloc(0)));
   assert.equal(socket.destroyed,true);assert.equal(upstream.destroyed,true);assert.equal(proxy.active,7);assert.equal(drained,closed?0:1);assert.equal(proxy.denials[closed?'closed':'capacity'],1);
   upstream.emit('close');assert.equal(proxy.active,7);
  }finally{proxy.queue=[];proxy.close();}
 }
});
