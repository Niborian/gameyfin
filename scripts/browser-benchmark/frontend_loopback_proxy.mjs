// Owned local HTTP/WebSocket barrier. Explicit construction only; no automatic listener.
import http from 'node:http';import net from 'node:net';
import {fixtureTarget,fixtureConnectTarget} from './frontend_proxy_policy.mjs';
export class LoopbackProxy {
  constructor(origin,{deadlineMs=90000,requestDeadlineMs=15000,dial=net.connect,request=http.request}={}){
    fixtureTarget({method:'GET',url:origin+'/',host:new URL(origin).host},origin);
    if(!Number.isInteger(deadlineMs)||deadlineMs<1||deadlineMs>90000)throw new Error('Proxy deadline boundary');
    if(!Number.isInteger(requestDeadlineMs)||requestDeadlineMs<1||requestDeadlineMs>15000)throw new Error('Request deadline boundary');
    this.origin=origin;this.dial=dial;this.request=request;this.sockets=new Set();this.closed=false;this.active=0;this.count=0;this.bytes=0;this.budgetExceeded=false;this.fixtureConnectDenied=0;this.upgrades=0;
    this.queue=[];this.requestDeadlineMs=requestDeadlineMs;this.peakActive=0;this.peakQueued=0;this.denials=Object.fromEntries(['capacity','request-budget','policy','deadline','closed'].map(k=>[k,0]));
    this.server=http.createServer({maxHeaderSize:16384},(req,res)=>this.handle(req,res));
    this.server.requestTimeout=15000;this.server.headersTimeout=15000;
    this.server.on('connection',s=>this.track(s));
    this.server.on('connect',(req,s,head)=>this.connect(req,s,head));
    this.server.on('upgrade',(req,s,head)=>this.upgrade(req,s,head));
    this.server.on('clientError',(_,s)=>s.destroy());
    this.timer=setTimeout(()=>this.close(),deadlineMs);
  }
  track(socket){if(this.closed||this.sockets.size>=32){socket.destroy();return;}this.sockets.add(socket);socket.setTimeout(15000,()=>socket.destroy());socket.on('close',()=>this.sockets.delete(socket));socket.on('error',()=>{});return socket;}
  consume(bytes){this.bytes+=bytes;if(this.bytes>64*1024*1024){this.budgetExceeded=true;this.close();return false;}return true;}
  rejection(reason){const error=new Error('Proxy bound');error.proxyReason=reason;return error;}
  recordDenial(reason){if(!Object.hasOwn(this.denials,reason))reason='policy';this.denials[reason]=Math.min(256,this.denials[reason]+1);return reason;}
  deny(res,reason){reason=this.recordDenial(reason);if(!res.destroyed&&!res.headersSent){res.writeHead(403,{'X-Gameyfin-Fixture-Proxy-Deny':reason});res.end();}else res.destroy();}
  diagnostics(){return {requests:this.count,active:this.active,queued:this.queue.length,peakActive:this.peakActive,peakQueued:this.peakQueued,...Object.fromEntries(Object.entries(this.denials).map(([k,v])=>['denied'+k.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join(''),v]))};}
  entered(){this.active++;this.peakActive=Math.max(this.peakActive,this.active);}
  released(){this.active--;this.drain();}
  drain(){while(!this.closed&&this.active<8&&this.queue.length){const entry=this.queue.shift();entry.queued=false;if(entry.res.destroyed||entry.req.aborted||performance.now()>=entry.deadline){entry.finish('deadline');continue;}this.forward(entry);}}
  target(req){
    if(this.closed)throw this.rejection('closed');if(this.count>=256)throw this.rejection('request-budget');this.count++;
    try{if(req.rawHeaders.filter((v,i)=>i%2===0&&v.toLowerCase()==='host').length!==1)throw new Error();return fixtureTarget({method:req.method,url:req.url,host:req.headers.host,upgrade:req.headers.upgrade},this.origin);}catch{throw this.rejection('policy');}
  }
  headers(req){const h={...req.headers};delete h['proxy-authorization'];delete h['proxy-connection'];return h;}
  connect(req,socket,head){
    let target;try{
      if(this.closed)throw this.rejection('closed');if(this.count>=256)throw this.rejection('request-budget');this.count++;
      if(this.active>=8)throw this.rejection('capacity');if(head.length>16384||req.rawHeaders.filter((v,i)=>i%2===0&&v.toLowerCase()==='host').length!==1)throw this.rejection('policy');
      target=fixtureConnectTarget({method:req.method,url:req.url,host:req.headers.host},this.origin);
    }catch(error){this.recordDenial(error.proxyReason);if(req.url===new URL(this.origin).host)this.fixtureConnectDenied++;socket.destroy();return;}
    this.entered();let ended=false;const done=()=>{if(!ended){ended=true;this.released();}};
    let upstream;try{upstream=this.track(this.dial(target));}catch{socket.destroy();done();return;}
    if(!upstream){this.recordDenial(this.closed?'closed':'capacity');socket.destroy();done();return;}upstream.setTimeout(15000,()=>upstream.destroy());
    upstream.on('connect',()=>{socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length){if(!this.consume(head.length)){upstream.destroy();return;}upstream.write(head);}socket.pipe(upstream);upstream.pipe(socket);});
    let total=head.length;for(const s of [socket,upstream]){s.on('data',chunk=>{total+=chunk.length;if(!this.consume(chunk.length)||total>16*1024*1024){socket.destroy();upstream.destroy();}});s.on('close',()=>{socket.destroy();upstream.destroy();done();});}
  }
  handle(req,res){
    const deadline=performance.now()+this.requestDeadlineMs;
    let target;try{target=this.target(req);if(target.websocket)throw this.rejection('policy');}catch(error){this.deny(res,error.proxyReason);return;}
    const entry={req,res,target,deadline,queued:false,started:false,ended:false,upstream:null,timer:null};
    entry.finish=reason=>{if(entry.ended)return;entry.ended=true;clearTimeout(entry.timer);if(entry.queued){this.queue=this.queue.filter(x=>x!==entry);entry.queued=false;}entry.upstream?.destroy();if(reason)this.deny(res,reason);if(entry.started)this.released();};
    entry.timer=setTimeout(()=>entry.finish('deadline'),Math.max(1,deadline-performance.now()));
    res.on('close',()=>entry.finish(null));req.on('aborted',()=>{entry.finish(null);res.destroy();});
    if(this.active>=8){if(this.queue.length>=8){entry.finish('capacity');return;}entry.queued=true;req.pause();this.queue.push(entry);this.peakQueued=Math.max(this.peakQueued,this.queue.length);return;}
    this.forward(entry);
  }
  forward(entry){
    const {req,res,target}=entry;if(entry.ended)return;entry.started=true;this.entered();
    // Target is always literal loopback; no caller hostname ever enters DNS/dial.
    let upstream;try{upstream=this.request({...target,method:req.method,headers:this.headers(req),agent:false},reply=>{
      if(entry.ended||res.destroyed){reply.destroy?.();return;}
      // This marker is local-proxy authority, never an upstream assertion.
      const headers={...reply.headers};for(const key of Object.keys(headers))if(key.toLowerCase()==='x-gameyfin-fixture-proxy-deny')delete headers[key];
      let bytes=0;res.writeHead(reply.statusCode,headers);
      reply.on('data',chunk=>{bytes+=chunk.length;if(!this.consume(chunk.length)||bytes>16*1024*1024){upstream.destroy();res.destroy();}});
      reply.on('error',()=>res.destroy());reply.pipe(res);
    });}catch{if(!res.headersSent)res.writeHead(502);res.end();entry.finish(null);return;}
    entry.upstream=upstream;
    if(entry.ended){upstream.destroy();return;}
    upstream.on('socket',s=>this.track(s));upstream.setTimeout(Math.max(1,Math.ceil(entry.deadline-performance.now())),()=>entry.finish('deadline'));
    upstream.on('error',()=>{if(entry.ended)return;if(!res.headersSent&&!res.destroyed)res.writeHead(502);res.end();entry.finish(null);});let bytes=0;
    req.on('data',chunk=>{bytes+=chunk.length;if(!this.consume(chunk.length)||bytes>2*1024*1024){upstream.destroy();req.destroy();res.destroy();}});
    req.pipe(upstream);req.resume();
  }
  upgrade(req,socket,head){
    this.upgrades++;
    let target;try{target=this.target(req);if(!target.websocket||head.length>16384)throw this.rejection('policy');if(this.active>=8)throw this.rejection('capacity');}catch(error){this.recordDenial(error.proxyReason);socket.destroy();return;}
    this.entered();let ended=false;const done=()=>{if(!ended){ended=true;this.released();}};
    let upstream;try{upstream=this.track(this.dial({host:'127.0.0.1',port:target.port}));}catch{socket.destroy();done();return;}
    if(!upstream){this.recordDenial(this.closed?'closed':'capacity');socket.destroy();done();return;}
    upstream.setTimeout(15000,()=>upstream.destroy());
    upstream.on('connect',()=>{
      const headers=this.headers(req);const lines=Object.entries(headers).map(([k,v])=>`${k}: ${Array.isArray(v)?v.join(', '):v}`);
      upstream.write(`${req.method} ${target.path} HTTP/1.1\r\n${lines.join('\r\n')}\r\n\r\n`);
      if(head.length)upstream.write(head);socket.pipe(upstream);upstream.pipe(socket);
    });
    let total=0;for(const s of [socket,upstream]){s.on('data',chunk=>{total+=chunk.length;if(!this.consume(chunk.length)||total>16*1024*1024){socket.destroy();upstream.destroy();}});s.on('close',()=>{socket.destroy();upstream.destroy();done();});}
  }
  async listen(port=0){if(this.closed||!Number.isInteger(port)||port!==0&&(port<49152||port>65535))throw new Error('Proxy port boundary');await new Promise((resolve,reject)=>{this.server.once('error',()=>reject(new Error('Owned proxy startup failed')));this.server.listen(port,'127.0.0.1',resolve);});return this.server.address().port;}
  close(){if(this.closed)return;this.closed=true;clearTimeout(this.timer);for(const entry of [...this.queue])entry.finish('closed');for(const s of this.sockets)s.destroy();this.server.close();}
}
