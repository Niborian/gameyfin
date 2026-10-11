// Controller-only private stdin. Credentials never enter child argv/env/evidence.
export async function readPrivateInput(input,{deadlineMs=1000}={}){
  if(!Number.isInteger(deadlineMs)||deadlineMs<1||deadlineMs>5000)throw new Error('Private input deadline');
  const parts=[];let size=0;
  try{
    const raw=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>finish(new Error('Private input unavailable')),deadlineMs);
      const data=chunk=>{const b=Buffer.from(chunk);parts.push(b);size+=b.length;if(size>1024)finish(new Error('Private input boundary'));};
      const end=()=>finish();const error=()=>finish(new Error('Private input unavailable'));
      function finish(err){clearTimeout(timer);input.off('data',data);input.off('end',end);input.off('error',error);input.pause();if(err)reject(err);else resolve(Buffer.concat(parts));}
      input.on('data',data);input.once('end',end);input.once('error',error);input.resume();
    });
    try{
      const lines=raw.toString('utf8').split('\n');
      if(lines.length!==3||lines[2]!==''||lines[0].length<1||lines[0].length>128||lines[1].length<16||lines[1].length>256||lines.some(v=>/[\x00-\x1f\x7f]/.test(v)))throw new Error('Private input boundary');
      return {username:lines[0],password:lines[1]};
    }finally{raw.fill(0);}
  }finally{for(const p of parts)p.fill(0);}
}
