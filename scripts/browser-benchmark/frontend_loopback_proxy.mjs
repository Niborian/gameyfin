// Owned local HTTP/WebSocket barrier. Explicit construction only; no automatic listener.
import http from 'node:http';import net from 'node:net';
import {fixtureTarget,fixtureConnectTarget} from './frontend_proxy_policy.mjs';
export class LoopbackProxy {
  constructor(origin,{deadlineMs=90000,dial=net.connect,request=http.request}={}){
    fixtureTarget({method:'GET',url:origin+'/',host:new URL(origin).host},origin);
    if(!Number.isInteger(deadlineMs)||deadlineMs<1||deadlineMs>90000)throw new Error('Proxy deadline boundary');
    this.origin=origin;this.dial=dial;this.request=request;this.sockets=new Set();this.closed=false;this.active=0;this.count=0;this.bytes=0;this.budgetExceeded=false;this.fixtureConnectDenied=0;this.upgrades=0;
    this.server=http.createServer({maxHeaderSize:16384},(req,res)=>this.handle(req,res));
    this.server.requestTimeout=15000;this.server.headersTimeout=15000;
    this.server.on('connection',s=>this.track(s));
    this.server.on('connect',(req,s,head)=>this.connect(req,s,head));
    this.server.on('upgrade',(req,s,head)=>this.upgrade(req,s,head));
    this.server.on('clientError',(_,s)=>s.destroy());
    this.timer=setTimeout(()=>this.close(),deadlineMs);
  }
  track(socket){if(this.closed||this.sockets.size>=32){socket.destroy();return socket;}this.sockets.add(socket);socket.setTimeout(15000,()=>socket.destroy());socket.on('close',()=>this.sockets.delete(socket));socket.on('error',()=>{});return socket;}
  consume(bytes){this.bytes+=bytes;if(this.bytes>64*1024*1024){this.budgetExceeded=true;this.close();return false;}return true;}
  target(req){
    if(this.closed||this.count++>=256||this.active>=8)throw new Error('Proxy bound');
    if(req.rawHeaders.filter((v,i)=>i%2===0&&v.toLowerCase()==='host').length!==1)throw new Error('Proxy Host boundary');
    return fixtureTarget({method:req.method,url:req.url,host:req.headers.host,upgrade:req.headers.upgrade},this.origin);
  }
  headers(req){const h={...req.headers};delete h['proxy-authorization'];delete h['proxy-connection'];return h;}
  connect(req,socket,head){
    let target;try{
      if(this.closed||this.count++>=256||this.active>=8||head.length>16384||req.rawHeaders.filter((v,i)=>i%2===0&&v.toLowerCase()==='host').length!==1)throw new Error();
      target=fixtureConnectTarget({method:req.method,url:req.url,host:req.headers.host},this.origin);
    }catch{if(req.url===new URL(this.origin).host)this.fixtureConnectDenied++;socket.destroy();return;}
    this.active++;let ended=false;const done=()=>{if(!ended){ended=true;this.active--;}};
    const upstream=this.track(this.dial(target));upstream.setTimeout(15000,()=>upstream.destroy());
    upstream.on('connect',()=>{socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length){if(!this.consume(head.length)){upstream.destroy();return;}upstream.write(head);}socket.pipe(upstream);upstream.pipe(socket);});
    let total=head.length;for(const s of [socket,upstream]){s.on('data',chunk=>{total+=chunk.length;if(!this.consume(chunk.length)||total>16*1024*1024){socket.destroy();upstream.destroy();}});s.on('close',()=>{socket.destroy();upstream.destroy();done();});}
  }
  handle(req,res){
    let target;try{target=this.target(req);if(target.websocket)throw new Error();}catch{res.writeHead(403);res.end();return;}
    this.active++;let ended=false;const done=()=>{if(!ended){ended=true;this.active--;}};
    // Target is always literal loopback; no caller hostname ever enters DNS/dial.
    const upstream=this.request({...target,method:req.method,headers:this.headers(req),agent:false},reply=>{
      let bytes=0;res.writeHead(reply.statusCode,reply.headers);
      reply.on('data',chunk=>{bytes+=chunk.length;if(!this.consume(chunk.length)||bytes>16*1024*1024){upstream.destroy();res.destroy();}});
      reply.on('error',()=>res.destroy());reply.pipe(res);
    });
    upstream.on('socket',s=>this.track(s));upstream.setTimeout(15000,()=>upstream.destroy());
    upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();done();});
    res.on('close',()=>{upstream.destroy();done();});let bytes=0;
    req.on('data',chunk=>{bytes+=chunk.length;if(!this.consume(chunk.length)||bytes>2*1024*1024){upstream.destroy();req.destroy();res.destroy();}});
    req.pipe(upstream);
  }
  upgrade(req,socket,head){
    this.upgrades++;
    let target;try{target=this.target(req);if(!target.websocket||head.length>16384)throw new Error();}catch{socket.destroy();return;}
    this.active++;let ended=false;const done=()=>{if(!ended){ended=true;this.active--;}};
    const upstream=this.track(this.dial({host:'127.0.0.1',port:target.port}));
    upstream.setTimeout(15000,()=>upstream.destroy());
    upstream.on('connect',()=>{
      const headers=this.headers(req);const lines=Object.entries(headers).map(([k,v])=>`${k}: ${Array.isArray(v)?v.join(', '):v}`);
      upstream.write(`${req.method} ${target.path} HTTP/1.1\r\n${lines.join('\r\n')}\r\n\r\n`);
      if(head.length)upstream.write(head);socket.pipe(upstream);upstream.pipe(socket);
    });
    let total=0;for(const s of [socket,upstream]){s.on('data',chunk=>{total+=chunk.length;if(!this.consume(chunk.length)||total>16*1024*1024){socket.destroy();upstream.destroy();}});s.on('close',()=>{socket.destroy();upstream.destroy();done();});}
  }
  async listen(port=0){if(this.closed||!Number.isInteger(port)||port!==0&&(port<49152||port>65535))throw new Error('Proxy port boundary');await new Promise((resolve,reject)=>{this.server.once('error',()=>reject(new Error('Owned proxy startup failed')));this.server.listen(port,'127.0.0.1',resolve);});return this.server.address().port;}
  close(){if(this.closed)return;this.closed=true;clearTimeout(this.timer);for(const s of this.sockets)s.destroy();this.server.close();}
}
