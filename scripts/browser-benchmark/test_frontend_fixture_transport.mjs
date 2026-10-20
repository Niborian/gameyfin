import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {fixedFixtureTransports} from './frontend_fixture_transport.mjs';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
const origin='http://127.0.0.1:55201';
test('fixed selected address refuses aliases, foreign targets and wrong HTTP authority before any app call',()=>{
 let calls=0;const deps={request:()=>{calls++;},dial:()=>{calls++;}};
 for(const ip of ['10.0.0.200','127.0.0.1','172.15.0.1','172.32.0.1','172.16.00.1','172.16.0.256','::1','::ffff:172.16.0.1','fixture.local',null])assert.throws(()=>fixedFixtureTransports(origin,ip,deps));
 const transport=fixedFixtureTransports(origin,'172.18.0.2',deps);
 const good={hostname:'127.0.0.1',port:55201,path:'/connect/GameEndpoint/getAll',method:'POST',headers:{host:'127.0.0.1:55201'},agent:false};
 for(const patch of [{hostname:'10.0.0.200'},{port:8080},{path:'http://foreign/'},{path:'/a\\b'},{headers:{host:'foreign'}},{agent:true},{method:'CONNECT'}])assert.throws(()=>transport.request({...good,...patch},()=>{}));
 for(const target of [{host:'foreign',port:55201},{host:'127.0.0.1',port:8080},{hostname:'127.0.0.1',port:55201}])assert.throws(()=>transport.dial(target));
 assert.equal(calls,0);
});
test('fixed transport forwards only selected address while preserving browser Host, cookies and separate response cookies',async()=>{
 let selectedCalls=0;const app=http.createServer((req,res)=>{assert.equal(req.headers.host,'127.0.0.1:55201');assert.equal(req.headers.cookie,'fixture=synthetic');res.setHeader('Set-Cookie',['a=1','b=2']);res.end('ok');});
 await new Promise(r=>app.listen(0,'127.0.0.1',r));
 const transport=fixedFixtureTransports(origin,'172.18.0.2',{request:(options,reply)=>{selectedCalls++;assert.equal(options.hostname,'172.18.0.2');assert.equal(options.port,8080);assert.equal(options.agent,false);return http.request({...options,hostname:'127.0.0.1',port:app.address().port},reply);}});
 const proxy=new LoopbackProxy(origin,transport);await proxy.listen(0);
 try{const result=await new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port:proxy.server.address().port,path:origin+'/connect/GameEndpoint/getAll',method:'POST',headers:{Host:'127.0.0.1:55201',Cookie:'fixture=synthetic'}},res=>{res.resume();res.on('end',()=>resolve({status:res.statusCode,cookies:res.headers['set-cookie']}));});req.on('error',reject);req.end();});assert.deepEqual(result,{status:200,cookies:['a=1','b=2']});assert.equal(selectedCalls,1);assert(proxy.diagnostics().peakOutboundSockets<=8);
 }finally{proxy.close();await new Promise(r=>app.close(r));}
});
test('tunnel transport maps only reviewed literal authority without DNS or caller options',()=>{
 const calls=[];const transport=fixedFixtureTransports(origin,'172.31.255.254',{dial:options=>{calls.push(options);return 'owned';}});
 assert.equal(transport.dial({host:'127.0.0.1',port:55201,arbitrary:'ignored'}),'owned');assert.deepEqual(calls,[{host:'172.31.255.254',port:8080}]);
});
