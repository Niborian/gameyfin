// Pure outbound policy for a future owned HTTP proxy. No listener or connection.
// HTTP/WS handshakes pass BEFORE forwarding/DNS. CONNECT permits exact fixture only.
export const RUNTIME_ENABLED=false;
export function fixtureTarget(request,origin){
  const expected=new URL(origin);
  if(expected.protocol!=='http:'||expected.hostname!=='127.0.0.1'||!expected.port||expected.username||expected.password||expected.pathname!=='/'||expected.search||expected.hash)throw new Error('Fixture origin boundary');
  if(!Number.isInteger(Number(expected.port))||Number(expected.port)<49152||Number(expected.port)>65535)throw new Error('Fixture port boundary');
  if(!request||!['GET','POST','HEAD','OPTIONS','PUT','DELETE','PATCH'].includes(request.method)||request.method==='CONNECT')throw new Error('Proxy method boundary');
  if(typeof request.url!=='string'||request.url.length>4096||/[\x00-\x20\x7f\\]/.test(request.url))throw new Error('Proxy URL boundary');
  // Reject raw authority aliases before URL normalization (including numeric aliases).
  const prefix=origin+'/';const websocketOrigin='ws://'+expected.host;
  if(!request.url.startsWith(prefix)&&!(request.upgrade!==undefined&&request.url.startsWith(websocketOrigin+'/')))throw new Error('Proxy origin boundary');
  const target=new URL(request.url);
  if((target.origin!==expected.origin&&!(request.upgrade!==undefined&&target.origin===websocketOrigin))||target.username||target.password||target.hash)throw new Error('Proxy origin boundary');
  if(request.host!==expected.host)throw new Error('Proxy Host boundary');
  if(request.upgrade!==undefined&&request.upgrade.toLowerCase()!=='websocket')throw new Error('Proxy upgrade boundary');
  if(request.upgrade!==undefined&&request.method!=='GET')throw new Error('WebSocket method boundary');
  return {hostname:'127.0.0.1',port:Number(expected.port),path:target.pathname+target.search,websocket:request.upgrade!==undefined};
}
export async function guardedForward(request,origin,forward){
  const target=fixtureTarget(request,origin); // Never resolve caller-provided hostname.
  return forward(target);
}
export function fixtureConnectTarget(request,origin){
  // Chrome tunnels even cleartext ws through an HTTP proxy. Permit only this
  // reviewed fixture authority; never parse/resolve a caller-supplied host.
  const expected=new URL(origin);fixtureTarget({method:'GET',url:origin+'/',host:expected.host},origin);
  if(request?.method!=='CONNECT'||request.url!==expected.host||request.host!==expected.host)throw new Error('CONNECT fixture boundary');
  return {host:'127.0.0.1',port:Number(expected.port)};
}
export function chromeProxyFlags(port){
  if(!Number.isSafeInteger(port)||port<49152||port>65535)throw new Error('Proxy port boundary');
  return [`--proxy-server=http://127.0.0.1:${port}`,'--proxy-bypass-list=<-loopback>','--disable-quic','--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1'];
}
export function launchProxy(){throw new Error('Owned bounded proxy runtime disabled pending review');}
