// Private loopback CDP transport. Never prints/retains credential-bearing frames.
export async function connectCdp(webSocketUrl,{port,WebSocketImpl=WebSocket,timeoutMs=3000}={}){
  const target=new URL(webSocketUrl);
  if(!Number.isInteger(port)||port<49152||port>65535||target.protocol!=='ws:'||target.hostname!=='127.0.0.1'||target.port!==String(port)||target.username||target.password||target.search||target.hash||!/^\/devtools\/page\/[A-Za-z0-9-]{1,128}$/.test(target.pathname))throw new Error('Private CDP boundary');
  if(!Number.isInteger(timeoutMs)||timeoutMs<1||timeoutMs>3000)throw new Error('Private CDP deadline');
  const socket=new WebSocketImpl(target.href);let closed=false;let handshakeTimer;
  const transport={onMessage:null,send(frame){if(closed||typeof frame!=='string'||frame.length>65536)throw new Error('Private CDP frame boundary');socket.send(frame);},close(){if(closed)return;closed=true;clearTimeout(handshakeTimer);socket.close();}};
  socket.addEventListener('message',event=>{if(!closed&&typeof event.data==='string'&&event.data.length<=3*1024*1024)transport.onMessage?.(event.data);else transport.close();});
  await new Promise((resolve,reject)=>{
    handshakeTimer=setTimeout(()=>{transport.close();reject(new Error('Private CDP unavailable'));},timeoutMs);
    socket.addEventListener('open',()=>{clearTimeout(handshakeTimer);resolve();},{once:true});
    socket.addEventListener('error',()=>{transport.close();reject(new Error('Private CDP unavailable'));},{once:true});
    socket.addEventListener('close',()=>{closed=true;clearTimeout(handshakeTimer);reject(new Error('Private CDP unavailable'));},{once:true});
  });return transport;
}
