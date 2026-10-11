"""Exact hosted-CI synthetic application lifetime; never server/local Docker.

The separate browser launcher validates the same root/identity/caps/mounts.
Parent finally + independent per-case watchdog + workflow always cleanup.
"""
import argparse,base64,json,os,re,secrets,shutil,stat,subprocess,sys,time
from pathlib import Path
from fixture_case import FixtureClient,generate_fixture

JVM='-Xms32m -Xmx320m -XX:+UseG1GC -XX:G1HeapRegionSize=1m -XX:+UseStringDeduplication -XX:+UseCompactObjectHeaders -XX:G1PeriodicGCInterval=0 -XX:MinHeapFreeRatio=40 -XX:MaxHeapFreeRatio=70 -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+ExitOnOutOfMemoryError'

def hosted_only():
    if os.name!='posix' or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':
        raise ValueError('Disposable GitHub-hosted Linux only; not local/server Docker')

def command(*args,timeout=30):
    return subprocess.run(['docker',*map(str,args)],check=True,capture_output=True,text=True,timeout=timeout).stdout

def exact_root(root,owner):
    root=Path(root)
    if not re.fullmatch('[a-f0-9]{16}',owner) or not root.is_absolute() or root.name!='gameyfin-browser-fixture-'+owner:
        raise ValueError('Exact fixture identity')
    if root.resolve()!=root or any(p.is_symlink() for p in [root,*root.parents]):raise ValueError('Non-symlink fixture root')
    if read_regular(root/'OWNER',64).decode()!='benchmark-27-'+owner:raise ValueError('Fixture owner marker')
    saved=json.loads(read_regular(root/'IDENTITY.json',256))
    observed=root.stat(follow_symlinks=False)
    if saved!={'owner':owner,'device':observed.st_dev,'inode':observed.st_ino}:raise ValueError('Creation device/inode identity changed')
    return root

def read_regular(path,bound):
    fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0))
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_size>bound:raise ValueError('Regular nonlinked bounded owned marker required')
        data=os.read(fd,bound+1)
        if len(data)>bound:raise ValueError('Owned marker byte bound')
        after=Path(path).stat(follow_symlinks=False)
        if (after.st_dev,after.st_ino,after.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError('Owned marker changed while reading')
        return data
    finally:os.close(fd)

def validate_owned(row,root,owner,image,expected_id):
    exact_root(root,owner)
    if not re.fullmatch('[a-f0-9]{64}',expected_id) or row.get('Id')!=expected_id or row.get('Image')!=image:
        raise ValueError('Exact immutable container identity')
    if row.get('Config',{}).get('Labels',{}).get('gameyfin.fixture.owner')!='benchmark-27-'+owner:
        raise ValueError('Container owner mismatch')
    if not re.fullmatch('/gameyfin-benchmark-(?:app|helper)-'+owner+'(?:-[a-f0-9]{8})?',row.get('Name','')):
        raise ValueError('Container name mismatch')
    host=row.get('HostConfig',{})
    if host.get('Privileged') or host.get('PidMode')=='host' or host.get('NetworkMode')=='host' or host.get('PortBindings'):
        raise ValueError('Fixture namespace/exposure mismatch')
    for mount in row.get('Mounts',[]):
        source=Path(mount.get('Source',''))
        if mount.get('Type')!='bind' or not source.is_absolute() or source.resolve()!=source or not source.is_relative_to(root):
            raise ValueError('Out-of-scope fixture mount')
    return expected_id

def intent_contract(name,image,user,network,mounts,entry,args,*,cpu=.25,memory=256,pids=64,caps=()):
    return {'name':'/'+name,'image':image,'user':user,'network':network,
        'nanoCpus':int(cpu*1e9),'memoryBytes':memory*1024*1024,'pids':pids,'capAdd':sorted(caps),
        'entrypoint':[entry],'cmd':list(map(str,args)),
        'mounts':sorted([{'source':str(source),'target':target,'rw':not readonly} for source,target,readonly in mounts],key=lambda x:x['target'])}

def persist_ledger(root,ledger):
    pending=root/'ledger.pending';pending.write_text(json.dumps(ledger));pending.replace(root/'ledger.json')

def validate_intent(row,root,owner,intent):
    identity=row.get('Id','');validate_owned(row,root,owner,intent['image'],identity)
    if row.get('Name')!=intent['name']:raise ValueError('Creation intent exact name')
    config=row.get('Config',{});host=row.get('HostConfig',{})
    if config.get('User')!=intent['user'] or config.get('Entrypoint')!=intent['entrypoint'] or config.get('Cmd')!=intent['cmd']:
        raise ValueError('Creation intent user/command contract')
    if host.get('NetworkMode')!=intent['network'] or host.get('NanoCpus')!=intent['nanoCpus'] or host.get('Memory')!=intent['memoryBytes'] or host.get('MemorySwap')!=intent['memoryBytes'] or host.get('PidsLimit')!=intent['pids']:
        raise ValueError('Creation intent resource/network contract')
    if set(host.get('CapDrop') or [])!={'ALL'} or sorted(host.get('CapAdd') or [])!=intent['capAdd'] or host.get('CgroupnsMode')!='private' or host.get('PidMode','')!='' or host.get('IpcMode','') not in ('','private') or host.get('UTSMode','')!='' or host.get('PublishAllPorts'):
        raise ValueError('Creation intent capabilities/namespaces')
    actual=sorted([{'source':m.get('Source'),'target':m.get('Destination'),'rw':m.get('RW')} for m in row.get('Mounts',[])],key=lambda x:x['target'])
    if actual!=intent['mounts']:raise ValueError('Creation intent mount contract')
    return identity

def recover_intents(root,owner,ledger):
    intents=ledger.get('intents',[])
    if not isinstance(intents,list) or len(intents)>16:raise ValueError('Bounded creation intent ledger')
    ids=command('ps','-aq','--no-trunc').split();rows=json.loads(command('inspect',*ids)) if ids else []
    for intent in intents:
        matches=[row for row in rows if row.get('Name')==intent['name']]
        if len(matches)>1:raise ValueError('Ambiguous creation intent')
        if not matches:continue
        identity=validate_intent(matches[0],root,owner,intent)
        existing=[r for r in ledger['containers'] if r['id']==identity]
        if existing and existing!=[{'id':identity,'image':intent['image']}]:raise ValueError('Recorded immutable identity disagrees')
        if not existing:ledger['containers'].append({'id':identity,'image':intent['image']})
    persist_ledger(root,ledger)
    return ledger

def cleanup(root,owner,*,remove_root=False):
    root=exact_root(Path(root),owner);ledger=json.loads(read_regular(root/'ledger.json',65536))
    from hosted_browser import cleanup_known_browser
    cleanup_known_browser(root,owner)
    if ledger.get('owner')!=owner:raise ValueError('Ledger owner mismatch')
    ledger=recover_intents(root,owner,ledger)
    for resource in reversed(ledger['containers']):
        ids=command('ps','-aq','--no-trunc').split()
        if resource['id'] not in ids:continue
        row=json.loads(command('inspect',resource['id']))[0]
        command('rm','--force',validate_owned(row,root,owner,resource['image'],resource['id']))
    network=ledger['network']
    if network in command('network','ls','--format','{{.Name}}').splitlines():
        rows=json.loads(command('network','inspect',network))
        if len(rows)!=1 or rows[0].get('Name')!='gameyfin-browser-net-'+owner or rows[0].get('Labels',{}).get('gameyfin.fixture.owner')!='benchmark-27-'+owner or rows[0].get('Containers'):
            raise ValueError('Network cleanup scope or still-attached browser')
        command('network','rm',rows[0]['Id'])
    if remove_root:
        ids=command('ps','-aq','--no-trunc').split()
        rows=json.loads(command('inspect',*ids)) if ids else []
        for row in rows:
            for mount in row.get('Mounts',[]):
                source=Path(mount.get('Source','/')).resolve()
                if source==root or source.is_relative_to(root):raise ValueError('Fixture still referenced; retain')
        # App-owned private H2/log/data files need ownership restored before
        # runner-user deletion. Exact leaf mounts only, never root/host paths.
        if ledger['containers']:
            image=ledger['containers'][0]['image'];name='gameyfin-benchmark-helper-'+owner+'-'+secrets.token_hex(4)
            leaves=[x for x in ('db','data','logs','plugins','plugindata','waiting') if (root/x).exists()]
            if any((root/x).is_symlink() or not (root/x).is_dir() for x in leaves):raise ValueError('Restore ownership leaf scope')
            mounts=sum((['--mount',f'type=bind,src={root/x},dst=/restore/{x}'] for x in leaves),[])
            restore_args=['-R','-h','-P',str(os.getuid())+':'+str(os.getgid()),*('/restore/'+x for x in leaves)]
            ledger.setdefault('intents',[]).append(intent_contract(name,image,'0:0','none',[(root/x,'/restore/'+x,False) for x in leaves],
                'chown',restore_args,memory=128,pids=32,caps=('CHOWN','DAC_READ_SEARCH')))
            persist_ledger(root,ledger)
            identity=command('create','--pull','never','--name',name,'--label','gameyfin.fixture.owner=benchmark-27-'+owner,
                '--network','none','--cgroupns','private','--cpus','.25','--memory','128m','--memory-swap','128m','--pids-limit','32',
                '--user','0:0','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','DAC_READ_SEARCH',
                *mounts,'--entrypoint','chown',image,*restore_args).strip()
            if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Ownership helper exact ID')
            ledger['containers'].append({'id':identity,'image':image})
            pending=root/'ledger.pending';pending.write_text(json.dumps(ledger));pending.replace(root/'ledger.json')
            try:command('start','--attach',identity,timeout=30)
            finally:
                row=json.loads(command('inspect',identity))[0];command('rm','--force',validate_owned(row,root,owner,image,identity))
        # Recheck identity and ALL stopped/running references after helper removal.
        exact_root(root,owner)
        ids=command('ps','-aq','--no-trunc').split();rows=json.loads(command('inspect',*ids)) if ids else []
        if any(Path(m.get('Source','/')).resolve()==root or Path(m.get('Source','/')).resolve().is_relative_to(root) for d in rows for m in d.get('Mounts',[])):
            raise ValueError('Fixture reference appeared before deletion; retain')
        exact_root(root,owner)
        # Only freshly generated synthetic root after complete reference inventory.
        shutil.rmtree(root)

class OwnedApp:
    def __init__(self,image,direct_plugin,metadata_plugin,*,expected_jar_sha,waiting=False):
        hosted_only()
        if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Immutable local image ID required')
        if not re.fullmatch('[a-f0-9]{64}',expected_jar_sha):raise ValueError('Built exact application JAR SHA required')
        self.image=image;self.owner=secrets.token_hex(8);self.network='gameyfin-browser-net-'+self.owner
        parent=Path(os.environ['RUNNER_TEMP']).resolve()
        if any(p.is_symlink() for p in [parent,*parent.parents]):raise ValueError('Private runner temp root')
        from ownership_index import index_path,planned,created
        self.root=parent/('gameyfin-browser-fixture-'+self.owner)
        planned(index_path(),self.root,self.owner)
        self.root.mkdir(mode=0o700)
        initial=self.root.stat(follow_symlinks=False);self.created_identity=(initial.st_dev,initial.st_ino)
        self.ledger={'owner':self.owner,'network':self.network,'containers':[],'intents':[]};self.id=None;self.watchdog=None
        try:
            created(index_path(),self.root,self.owner)
            (self.root/'OWNER').write_text('benchmark-27-'+self.owner)
            (self.root/'IDENTITY.json').write_text(json.dumps({'owner':self.owner,'device':initial.st_dev,'inode':initial.st_ino}))
            for leaf in ('db','data','logs','plugins','plugindata'):(self.root/leaf).mkdir(mode=0o755)
            for source in (direct_plugin,metadata_plugin):
                source=Path(source)
                if not source.is_file() or source.is_symlink():raise ValueError('Explicit freshly built fixture plugins')
                target=self.root/'plugins'/source.name;shutil.copyfile(source,target);target.chmod(0o644)
            self.fixture=generate_fixture(self.root/'fixture')
            (self.root/'fixture').chmod(0o755);(self.root/'fixture/seed.sql').chmod(0o644)
            self.waiting_root=None
            if waiting:
                (self.root/'waiting').mkdir(mode=0o755)
                self.waiting_root=self.root/'waiting'/('gameyfin-overlap-fixture-'+self.owner)
                self.waiting_root.mkdir(mode=0o777);self.waiting_root.chmod(0o777)
            self.write_ledger()
            command('network','create','--internal','--label','gameyfin.fixture.owner=benchmark-27-'+self.owner,self.network)
            self.watchdog=subprocess.Popen([sys.executable,str(Path(__file__)),'--watchdog','--root',str(self.root),'--owner',self.owner],
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            self.helper('chown',['-R','1337:1337',*('/owned/'+x for x in ('db','data','logs','plugins','plugindata')),
                *(['/owned/waiting'] if waiting else [])],caps=['CHOWN'],
                mounts=[(self.root/x,'/owned/'+x,False) for x in ('db','data','logs','plugins','plugindata',*(['waiting'] if waiting else []))])
            env=self.root/'private.env';env.write_text('APP_KEY='+base64.b64encode(secrets.token_bytes(32)).decode()+'\nJDK_JAVA_OPTIONS='+JVM+
                (' -Dgameyfin.fixture.waitingRoot=/waiting-fixture/'+self.waiting_root.name if waiting else '')+'\n');env.chmod(0o600)
            mounts=[]
            for leaf in ('db','data','logs','plugins','plugindata'):
                mounts+=['--mount',f'type=bind,src={self.root/leaf},dst=/opt/gameyfin/{leaf}']
            for leaf,destination in [('sources','/fixture'),('images','/fixture-images')]:
                mounts+=['--mount',f'type=bind,src={self.root/"fixture"/leaf},dst={destination},readonly']
            if waiting:mounts+=['--mount',f'type=bind,src={self.root/"waiting"},dst=/waiting-fixture']
            app_args=['-jar','application.jar','--spring.aot.enabled=false','--gameyfin.acquisition.enabled=false','--gameyfin.experiments.image-worker.enabled=false']
            app_mounts=[(self.root/x,'/opt/gameyfin/'+x,False) for x in ('db','data','logs','plugins','plugindata')]
            app_mounts.extend([(self.root/'fixture/sources','/fixture',True),(self.root/'fixture/images','/fixture-images',True)])
            if waiting:app_mounts.append((self.root/'waiting','/waiting-fixture',False))
            self.ledger['intents'].append(intent_contract('gameyfin-benchmark-app-'+self.owner,image,'1337:1337',self.network,app_mounts,
                'java',app_args,cpu=.5,memory=1024,pids=256));self.write_ledger()
            self.id=command('create','--pull','never','--name','gameyfin-benchmark-app-'+self.owner,'--label','gameyfin.fixture.owner=benchmark-27-'+self.owner,
                '--network',self.network,'--cgroupns','private','--user','1337:1337','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--cpus','.5','--memory','1024m','--memory-swap','1024m','--pids-limit','256','--restart','no',
                '--env-file',env,*mounts,'--entrypoint','java',image,*app_args).strip()
            self.record(self.id,image);command('start',self.id)
            digest=command('exec','--user','1337:1337',self.id,'sha256sum','/opt/gameyfin/application.jar').split()
            if digest!=[expected_jar_sha,'/opt/gameyfin/application.jar']:raise ValueError('Actual running app JAR differs from source receipt')
            row=self.inspect();self.ip=row['NetworkSettings']['Networks'][self.network]['IPAddress'];self.client=FixtureClient(self.ip)
            self.ready('/setup');self.password=self.client.bootstrap()
            command('stop','--time','20',self.id)
            if self.inspect()['State']['Running']:raise ValueError('No live H2 import')
            self.helper('java',['-Xmx128m','-cp','/opt/gameyfin/h2.jar','org.h2.tools.RunScript',
                '-url','jdbc:h2:/db/gameyfin_db;IFEXISTS=TRUE','-user','gfadmin','-password','gameyfin','-script','/seed/seed.sql'],user='1337:1337',
                mounts=[(self.root/'db','/db',False),(self.root/'fixture','/seed',True)])
            command('start',self.id);self.ready('/login');self.client.login(self.password);self.client.plugin_gate()
            self.cover_path=self.client.ingest_cover();self.client.image_gate(self.cover_path)
        except Exception:
            self.close();raise
    def write_ledger(self):
        pending=self.root/'ledger.pending';pending.write_text(json.dumps(self.ledger));pending.replace(self.root/'ledger.json')
    def record(self,identity,image):
        if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Created container ID')
        self.ledger['containers'].append({'id':identity,'image':image});self.write_ledger()
    def helper(self,entry,args,*,caps=(),user='0:0',mounts):
        name='gameyfin-benchmark-helper-'+self.owner+'-'+secrets.token_hex(4)
        self.ledger['intents'].append(intent_contract(name,self.image,user,'none',mounts,entry,args,caps=caps));self.write_ledger()
        identity=command('create','--pull','never','--name',name,'--label','gameyfin.fixture.owner=benchmark-27-'+self.owner,
            '--network','none','--cgroupns','private','--cpus','.25','--memory','256m','--memory-swap','256m','--pids-limit','64','--user',user,
            '--cap-drop','ALL',*sum((['--cap-add',cap] for cap in caps),[]),
            *sum((['--mount',f'type=bind,src={source},dst={destination}'+(',readonly' if readonly else '')] for source,destination,readonly in mounts),[]),
            '--entrypoint',entry,self.image,*args).strip()
        self.record(identity,self.image)
        try:command('start','--attach',identity,timeout=60)
        finally:
            row=json.loads(command('inspect',identity))[0]
            command('rm','--force',validate_owned(row,self.root,self.owner,self.image,identity))
    def ready(self,path):
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:self.client.page_csrf(path);return
            except (OSError,RuntimeError):time.sleep(1)
        raise TimeoutError('Actual application startup deadline')
    def inspect(self):
        row=json.loads(command('inspect',self.id))[0]
        validate_owned(row,self.root,self.owner,self.image,self.id);return row
    def resources(self):
        row=self.inspect();pid=row['State']['Pid']
        if not row['State']['Running'] or pid<=0:raise ValueError('Owned JVM not running')
        paths=[x.split(':',2)[2] for x in Path(f'/proc/{pid}/cgroup').read_text().splitlines() if x.startswith('0::')]
        if len(paths)!=1 or '..' in Path(paths[0]).parts:raise ValueError('Unified owned cgroup required')
        cg=Path('/sys/fs/cgroup')/paths[0].lstrip('/')
        cpu=dict(line.split() for line in (cg/'cpu.stat').read_text().splitlines());memory=dict(line.split() for line in (cg/'memory.stat').read_text().splitlines())
        match=re.search(r'^VmRSS:\s+([0-9]+)',Path(f'/proc/{pid}/status').read_text(),re.M)
        if not match:raise ValueError('Owned Java RSS unavailable')
        return {'cpuSeconds':int(cpu['usage_usec'])/1e6,'throttledSeconds':int(cpu['throttled_usec'])/1e6,
            'memoryBytes':int((cg/'memory.current').read_text()),'anonymousBytes':int(memory['anon']),
            'fileCacheBytes':int(memory['file']),'rssBytes':int(match[1])*1024,
            'containerLifetimePeakBytes':int((cg/'memory.peak').read_text())}
    def final_gate(self):
        row=self.inspect();logs=command('logs',self.id)
        errors=sum(bool(re.search(r'\bERROR\b',line)) for line in logs.splitlines())
        oom=sum(bool(re.search(r'\bOutOfMemoryError(?::|\s*$)',line)) for line in logs.splitlines())
        status,_,body=self.client.raw('/actuator/health',management=True)
        health=json.loads(body).get('status') if status==200 else None
        gate={'unexpectedErrorCount':errors,'oomLogCount':oom,'oomKilled':row['State']['OOMKilled'],'restartCount':row['RestartCount'],'health':health}
        if errors or oom or gate['oomKilled'] or gate['restartCount'] or health!='UP':raise ValueError('Final actual-app error/OOM/restart/health gate failed')
        return gate
    def close(self):
        success=False
        try:
            if (self.root/'ledger.json').exists():cleanup(self.root,self.owner,remove_root=True)
            else:
                # No container/network creation occurred before ledger exists.
                observed=self.root.stat(follow_symlinks=False)
                if self.root.is_symlink() or (observed.st_dev,observed.st_ino)!=self.created_identity:
                    raise ValueError('Initial preparation cleanup identity changed')
                shutil.rmtree(self.root)
            success=True
        finally:
            self.password=None
            if success and self.watchdog:
                self.watchdog.terminate()
                try:self.watchdog.wait(timeout=2)
                except subprocess.TimeoutExpired:self.watchdog.kill();self.watchdog.wait(timeout=2)
    def __enter__(self):return self
    def __exit__(self,*_):self.close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--watchdog',action='store_true');parser.add_argument('--cleanup-only',action='store_true')
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--owner',required=True);args=parser.parse_args()
    hosted_only()
    if args.watchdog:time.sleep(1200)
    elif not args.cleanup_only:raise ValueError('Explicit scoped cleanup mode required')
    cleanup(args.root,args.owner,remove_root=args.cleanup_only)
if __name__=='__main__':main()
