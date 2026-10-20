// Fixed selected-container transport; browser authority remains literal loopback.
import http from 'node:http';
import net from 'node:net';
import {fixtureTarget} from './frontend_proxy_policy.mjs';
export function fixedFixtureTransports(origin,appIp,{request=http.request,dial=net.connect}={}){
  const authority=new URL(origin);
  fixtureTarget({method:'GET',url:origin+'/',host:authority.host},origin);
  if(net.isIP(appIp)!==4||!/^172\.(1[6-9]|2\d|3[01])\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$/.test(appIp))throw new Error('Selected fixture address boundary');
  const fixed=options=>{if(!options||options.hostname!=='127.0.0.1'||options.port!==Number(authority.port))throw new Error('Selected fixture transport boundary');};
  return {
    request(options,reply){
      fixed(options);
      const target=fixtureTarget({method:options.method,url:origin+options.path,host:options.headers?.host,upgrade:options.headers?.upgrade},origin);
      if(target.websocket||options.agent!==false)throw new Error('Selected fixture HTTP boundary');
      return request({hostname:appIp,port:8080,path:target.path,method:options.method,headers:options.headers,agent:false},reply);
    },
    dial(options){
      if(!options||options.host!=='127.0.0.1'||options.port!==Number(authority.port))throw new Error('Selected fixture tunnel boundary');
      fixtureTarget({method:'GET',url:origin+'/',host:authority.host},origin);
      return dial({host:appIp,port:8080});
    }
  };
}
