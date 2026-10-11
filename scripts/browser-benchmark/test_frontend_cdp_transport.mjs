import test from 'node:test';import assert from 'node:assert/strict';import {connectCdp} from './frontend_cdp_transport.mjs';
class FakeSocket extends EventTarget{constructor(url){super();this.url=url;queueMicrotask(()=>this.dispatchEvent(new Event('open')));}send(frame){this.frame=frame;}close(){this.didClose=true;}}
test('CDP handshake binds exact loopback port/page and denies external before construction',async()=>{
 const transport=await connectCdp('ws://127.0.0.1:54322/devtools/page/owned-123',{port:54322,WebSocketImpl:FakeSocket});transport.send('{}');transport.close();
 let constructors=0;class Denied{constructor(){constructors++;}}
 for(const url of ['ws://example.org:54322/devtools/page/owned','ws://127.0.0.1:54323/devtools/page/owned','ws://127.0.0.1:54322/devtools/browser/owned','ws://user@127.0.0.1:54322/devtools/page/owned'])await assert.rejects(connectCdp(url,{port:54322,WebSocketImpl:Denied}));assert.equal(constructors,0);
});
