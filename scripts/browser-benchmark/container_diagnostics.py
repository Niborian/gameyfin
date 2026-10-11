"""Bounded nonsecret Docker contract evidence BEFORE a guard can reject it."""
import json,re
from pathlib import Path

CAPS={'ALL','SYS_ADMIN','CAP_SYS_ADMIN','CHOWN','CAP_CHOWN','DAC_READ_SEARCH','CAP_DAC_READ_SEARCH'}

def diagnostic(row,owner,kind,image):
    if kind not in ('application','browser','helper'):raise ValueError('Diagnostic kind')
    h=row.get('HostConfig',{});c=row.get('Config',{})
    def number(key):
        value=h.get(key)
        return value if type(value) is int and 0<=value<=2**63-1 else None
    def enum(key,allowed):
        value=h.get(key)
        if isinstance(value,str) and value in allowed:return value
        if type(value) is str and value.startswith('container:'):return 'container'
        if key=='NetworkMode' and value=='gameyfin-browser-net-'+owner:return 'exact-owned-network'
        return 'unexpected'
    def capabilities(key):
        value=h.get(key)
        if value is None:return {'count':0,'values':[]}
        if not isinstance(value,list):return {'count':None,'values':['unexpected-type']}
        return {'count':len(value),'values':[v if isinstance(v,str) and v in CAPS else 'unexpected' for v in value[:16]]}
    def security_options():
        values=h.get('SecurityOpt')
        if values is None:return {'count':0,'categories':[]}
        if not isinstance(values,list):return {'count':None,'categories':['unexpected-type']}
        known={'no-new-privileges':'no-new-privileges','no-new-privileges:true':'no-new-privileges',
            'no-new-privileges=true':'no-new-privileges','seccomp=unconfined':'seccomp-unconfined',
            'seccomp=default':'seccomp-default','apparmor=unconfined':'apparmor-unconfined','apparmor=docker-default':'apparmor-docker-default'}
        return {'count':len(values),'categories':[known.get(v,'unexpected') if isinstance(v,str) else 'unexpected-type' for v in values[:16]]}
    actual_id=row.get('Id');actual_image=row.get('Image')
    return {'scope':'Docker configured contract only, not application CPU/RAM measurements',
        'kind':kind,'containerId':actual_id if isinstance(actual_id,str) and re.fullmatch('[a-f0-9]{64}',actual_id) else None,
        'imageMatches':actual_image==image,'ownerMatches':c.get('Labels',{}).get('gameyfin.browser.owner' if kind=='browser' else 'gameyfin.fixture.owner')==
            (owner if kind=='browser' else 'benchmark-27-'+owner),
        'configuredUser':c.get('User') if c.get('User') in ('1337:1337','10042:10042','0:0') else 'unexpected',
        'resources':{key:number(key) for key in ('NanoCpus','Memory','MemorySwap','PidsLimit','ShmSize')},
        'capAdd':capabilities('CapAdd'),'capDrop':capabilities('CapDrop'),
        'securityOptions':security_options(),
        'appArmorProfile':row.get('AppArmorProfile') if row.get('AppArmorProfile') in ('','docker-default','unconfined') else 'unexpected',
        'namespaces':{key:enum(key,allowed) for key,allowed in {
            'NetworkMode':{'none','host','bridge','default'},'PidMode':{'','host'},
            'IpcMode':{'','private','host','shareable','none'},'UTSMode':{'','host'},'CgroupnsMode':{'','private','host'}}.items()},
        'booleans':{key:h.get(key) if type(h.get(key)) is bool else None for key in ('Privileged','ReadonlyRootfs','Init','PublishAllPorts')},
        'publishedPortCount':len(h.get('PortBindings') or {}),'mountCount':len(row.get('Mounts',[])),
        'mountTypes':[m.get('Type') if m.get('Type') in ('bind','tmpfs','volume') else 'unexpected' for m in row.get('Mounts',[])[:16]],
        'running':row.get('State',{}).get('Running') is True,'oomKilled':row.get('State',{}).get('OOMKilled') is True}

def capture(path,row,owner,kind,image):
    path=Path(path)
    if path.suffix!='.json' or path.is_symlink() or path.parent.is_symlink():raise ValueError('Regular JSON diagnostic destination')
    value=diagnostic(row,owner,kind,image);data=json.dumps(value,indent=2)
    if len(data.encode())>8192:raise ValueError('Diagnostic byte bound')
    path.parent.mkdir(parents=True,exist_ok=True)
    pending=path.with_suffix('.diagnostic-pending')
    pending.write_text(data);pending.replace(path)
    return value
