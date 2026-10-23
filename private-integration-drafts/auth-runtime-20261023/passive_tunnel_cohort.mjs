// Passive diagnostic only: no socket write, close, destroy, retry, or network operation.
import {LoopbackProxy} from '../../scripts/browser-benchmark/frontend_loopback_proxy.mjs';
export function bookmarkBrowserTunnelCohort(proxy){
  if(!(proxy instanceof LoopbackProxy)||proxy.closed||!(proxy.inbound instanceof Map)||!(proxy.sockets instanceof Set))throw new Error('Owned proxy cohort boundary');
  const members=[];
  for(const [socket,state] of proxy.inbound){
    if(state.tunnel!==true)continue;
    if(members.length>=16||!proxy.sockets.has(socket)||typeof socket.once!=='function'||typeof socket.removeListener!=='function'||typeof socket.destroyed!=='boolean'||typeof socket.closed!=='boolean')throw new Error('Tunnel cohort bound');
    const member={socket,closeObserved:false};
    member.onClose=()=>{member.closeObserved=true;};
    members.push(member);
  }
  // Validate the entire cohort before adding any listeners; rollback partial attachment.
  try { for(const member of members)member.socket.once('close',member.onClose); }
  catch(error){for(const member of members)member.socket.removeListener('close',member.onClose);throw error;}
  let disposed=false;
  return {
    inspect(){
      if(disposed)throw new Error('Disposed tunnel cohort');
      let open=0,closed=0,unknown=0;
      for(const member of members){
        if(member.closeObserved)closed++;
        else if(member.socket.destroyed===false&&member.socket.closed===false)open++;
        else unknown++;
      }
      return {existing:members.length,open,closed,unknown,upstreamClosureProven:false};
    },
    dispose(){for(const member of members)member.socket.removeListener('close',member.onClose);members.length=0;disposed=true;}
  };
}
