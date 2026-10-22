"""One retained public image / fresh-profile original-UI pass on hosted CI only.

No application build, scan, provider compilation, production or private binaries.
"""
import argparse,datetime,hashlib,io,json,os,re,secrets,shutil,signal,stat,subprocess,tarfile,time,urllib.error,urllib.parse,urllib.request,zipfile
from pathlib import Path,PurePosixPath
from owned_app import OwnedApp,command,hosted_only,read_regular
from hosted_browser import HostedBrowser,reap_owned_process
from ownership_index import create,cleanup_current,job_identity
from fixture_case import verify_sources
from throughput_case import metrics

SOURCE='81f85eb4c5c9cfe5e0c4b4cb0b9420ea9e04f798'
IMAGE='sha256:9ff08089c7bf46a62325766175bf20011dff2c1000370bcaa5dcae342ff7882d'
ARTIFACT=12307145129;RUN=39511221286;SIZE=336289455
ZIP_SHA='4c45acbfe7f371de40dc706999847cc59639c42e5f8aaf30d1843e8156c5059d'
SCOPE='Public81 retained-image original-UI fresh-profile pass only; not same-profile relogin, scanning, performance, private integration or release acceptance'
API='https://api.github.com/repos/Niborian/gameyfin'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def api(path):
    token=os.environ.get('GH_TOKEN','')
    if not token:raise ValueError('Read-only artifact token required')
    request=urllib.request.Request(API+path,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()).open(request,timeout=15) as response:
        raw=response.read(256*1024+1)
        if len(raw)>256*1024:raise ValueError('Metadata bound')
        return json.loads(raw)

def input_root():
    hosted_only();parent=Path(os.environ['RUNNER_TEMP']).resolve()
    if any(p.is_symlink() for p in [parent,*parent.parents]):raise ValueError('Regular hosted temp')
    return parent/('gameyfin-public81-ui-inputs-'+job_identity())

def write(path,value):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'w') as stream:json.dump(value,stream,sort_keys=True,indent=2,allow_nan=False)

def exact_inputs(root):
    if root!=input_root() or root.is_symlink() or root.resolve()!=root:raise ValueError('Exact input root')
    marker=json.loads(read_regular(root/'OWNER.json',1024));info=root.stat(follow_symlinks=False)
    if marker!={'job':job_identity(),'device':info.st_dev,'inode':info.st_ino}:raise ValueError('Input root identity')

def download(root):
    run=api('/actions/runs/'+str(RUN));meta=api('/actions/artifacts/'+str(ARTIFACT))
    if run.get('head_sha')!=SOURCE or run.get('status')!='completed' or run.get('conclusion')!='success' or run.get('head_repository',{}).get('id')!=1260847994:raise ValueError('Exact successful public source run')
    if meta.get('id')!=ARTIFACT or meta.get('name')!='staging-image-'+SOURCE or meta.get('size_in_bytes')!=SIZE or meta.get('digest')!='sha256:'+ZIP_SHA or meta.get('expired') is not False or meta.get('workflow_run',{}).get('id')!=RUN or meta.get('workflow_run',{}).get('head_sha')!=SOURCE:raise ValueError('Immutable artifact metadata')
    if datetime.datetime.fromisoformat(meta['expires_at'].replace('Z','+00:00'))<=datetime.datetime.now(datetime.timezone.utc):raise ValueError('Artifact expired')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect());url=API+'/actions/artifacts/'+str(ARTIFACT)+'/zip';headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']};response=None
    for _ in range(3):
        try:response=opener.open(urllib.request.Request(url,headers=headers),timeout=15);break
        except urllib.error.HTTPError as error:
            if error.code not in (301,302,303,307,308):raise ValueError('Artifact download rejected') from None
            url=error.headers.get('Location','');parsed=urllib.parse.urlsplit(url)
            if parsed.scheme!='https' or parsed.username or parsed.password or parsed.port not in (None,443) or not parsed.hostname or not (parsed.hostname.endswith('.blob.core.windows.net') or parsed.hostname.endswith('.githubusercontent.com')):raise ValueError('Artifact redirect boundary')
            headers={} # Never forward the GitHub token to signed blob storage.
    if response is None:raise ValueError('Artifact redirect bound')
    archive=root/'artifact.zip';digest=hashlib.sha256();count=0;started=time.monotonic()
    with response,archive.open('xb') as output:
        while True:
            chunk=response.read(1024*1024)
            if not chunk:break
            count+=len(chunk)
            if count>SIZE or time.monotonic()-started>120:raise ValueError('Artifact download bound')
            digest.update(chunk);output.write(chunk)
    if count!=SIZE or digest.hexdigest()!=ZIP_SHA:raise ValueError('Artifact archive integrity')
    names={'gameyfin-staging-image.tar','gameyfin-staging-image.sha256','gameyfin-staging-image.txt'}
    with zipfile.ZipFile(archive) as zipped:
        entries=zipped.infolist()
        if len(entries)!=3 or {e.filename for e in entries}!=names or sum(e.file_size for e in entries)>600*1024**2:raise ValueError('Exact bounded artifact members')
        for entry in entries:
            mode=entry.external_attr>>16
            if entry.is_dir() or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0,stat.S_IFREG) or entry.file_size>(600*1024**2 if entry.filename.endswith('.tar') else 4096):raise ValueError('Regular artifact member')
            with zipped.open(entry) as source,(root/entry.filename).open('xb') as output:
                total=0
                while chunk:=source.read(1024*1024):
                    total+=len(chunk)
                    if total>entry.file_size:raise ValueError('Artifact member inflation')
                    output.write(chunk)
    archive_hash=hash_file(root/'gameyfin-staging-image.tar')
    if (root/'gameyfin-staging-image.sha256').read_text().strip()!=archive_hash+'  gameyfin-staging-image.tar':raise ValueError('Recorded tar checksum')
    if (root/'gameyfin-staging-image.txt').read_text().splitlines()!=['source_head='+SOURCE,'image_id='+IMAGE]:raise ValueError('Recorded image/source binding')
    with tarfile.open(root/'gameyfin-staging-image.tar') as saved:
        entries=[]
        for entry in saved:
            entries.append(entry)
            if len(entries)>512:raise ValueError('Docker save member count bound')
        names=[e.name for e in entries]
        if len(entries)>512 or len(set(names))!=len(names) or any(not regular_tar(e,allow_directory=True) for e in entries):raise ValueError('Docker save outer archive boundary')
        manifest_raw=saved.extractfile('manifest.json').read(65537)
        if len(manifest_raw)>65536:raise ValueError('Saved image manifest byte bound')
        manifest=json.loads(manifest_raw)
        if not isinstance(manifest,list) or len(manifest)!=1:raise ValueError('Single saved image required')
        configuration=saved.extractfile(manifest[0]['Config']).read(262145)
        if len(configuration)>262144 or 'sha256:'+hashlib.sha256(configuration).hexdigest()!=IMAGE:raise ValueError('Saved image configuration digest')
    command('load','--input',root/'gameyfin-staging-image.tar',timeout=60)
    row=json.loads(command('image','inspect',IMAGE))[0];labels=row.get('Config',{}).get('Labels',{})
    if row.get('Id')!=IMAGE or labels.get('org.opencontainers.image.revision')!=SOURCE or labels.get('org.opencontainers.image.version')!='2.4.3' or row.get('Config',{}).get('Volumes'):raise ValueError('Exact public image source/version/volume boundary')
    return {'artifactId':ARTIFACT,'runId':RUN,'artifactZipSha256':ZIP_SHA,'tarSha256':archive_hash,'source':SOURCE,'imageId':IMAGE,'version':'2.4.3'}

def hash_file(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        while chunk:=stream.read(1024*1024):digest.update(chunk)
    return digest.hexdigest()

def regular_tar(entry,allow_directory=False):
    return bool(re.fullmatch(r'[A-Za-z0-9_./-]{1,256}',entry.name)) and not PurePosixPath(entry.name).is_absolute() and '..' not in PurePosixPath(entry.name).parts and (entry.isfile() or allow_directory and entry.isdir()) and 0<=entry.size<=600*1024**2

def validate_probe(row,plan):
    from cap_policy import caps_match
    host=row.get('HostConfig',{});state=row.get('State',{});config=row.get('Config',{})
    if row.get('Id')!=plan['id'] or row.get('Name')!='/'+plan['name'] or row.get('Image')!=IMAGE or config.get('Labels',{}).get('gameyfin.public81.probe')!=plan['owner'] or config.get('Entrypoint')!=['/bin/true'] or config.get('Cmd') not in (None,[]) or config.get('User')!='1337:1337':raise ValueError('Exact never-started image probe identity')
    if state.get('Status')!='created' or state.get('Running') is not False or state.get('StartedAt')!='0001-01-01T00:00:00Z' or row.get('RestartCount')!=0 or row.get('Mounts') or host.get('NetworkMode')!='none' or host.get('Privileged') or host.get('PortBindings') or host.get('ReadonlyRootfs') is not True or host.get('RestartPolicy')!={'Name':'no','MaximumRetryCount':0} or not caps_match(host.get('CapDrop'),['ALL']) or not caps_match(host.get('CapAdd'),[]) or host.get('PidMode','')!='' or host.get('Memory')!=64*1024**2 or host.get('MemorySwap')!=64*1024**2 or host.get('NanoCpus')!=100000000 or host.get('PidsLimit')!=16 or host.get('SecurityOpt') not in (['no-new-privileges'],['no-new-privileges=true'],['no-new-privileges:true']):raise ValueError('Never-started read-only image probe boundary')

def cleanup_probe(root):
    exact_inputs(root);path=root/'image-probe.json'
    if not path.exists():return
    plan=json.loads(read_regular(path,2048))
    if set(plan)!={'id','name','owner','image'} or plan['image']!=IMAGE or not re.fullmatch('[a-f0-9]{16}',plan['owner']) or plan['name']!='gameyfin-public81-image-probe-'+plan['owner'] or plan['id'] is not None and not re.fullmatch('[a-f0-9]{64}',plan['id']):raise ValueError('Image probe durable intent')
    cid_path=root/'image-probe-cid.json'
    if cid_path.exists():
        known=json.loads(read_regular(cid_path,2048))
        if set(known)!=set(plan) or any(known[k]!=plan[k] for k in ('owner','name','image')) or not re.fullmatch('[a-f0-9]{64}',known.get('id','')):raise ValueError('Recorded immutable probe identity')
        plan=known
    identities=command('ps','-aq','--no-trunc','--filter','name=^/'+plan['name']+'$').split()
    if len(identities)>1:raise ValueError('Ambiguous image probe')
    if not identities and plan['id'] is None:raise ValueError('Unknown create outcome retained')
    if identities:
        identity=identities[0]
        if plan['id'] is not None and plan['id']!=identity:raise ValueError('Image probe CID disagreement')
        bound={**plan,'id':identity};row=json.loads(command('inspect',identity))[0];validate_probe(row,bound);plan=bound
        command('rm',identity)
    if plan['id'] is not None and plan['id'] in command('ps','-aq','--no-trunc').split():raise ValueError('Never-started probe removal unproven')
    write(root/'image-probe-cleanup.json',{'complete':True,'neverStarted':True});path.unlink()

def cp_bytes(identity,path,bound):
    process=subprocess.Popen(['docker','cp',identity+':'+path,'-'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL);chunks=[];total=0;deadline=time.monotonic()+20
    import selectors
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout,selectors.EVENT_READ)
            while True:
                if time.monotonic()>=deadline:raise TimeoutError('Image inventory read deadline')
                if not selector.select(max(0,deadline-time.monotonic())):raise TimeoutError('Image inventory read deadline')
                chunk=os.read(process.stdout.fileno(),65536)
                if not chunk:break
                total+=len(chunk)
                if total>bound:raise ValueError('Image inventory byte bound')
                chunks.append(chunk)
        if process.wait(timeout=max(.01,deadline-time.monotonic())):raise ValueError('Image inventory copy failed')
        return b''.join(chunks)
    finally:reap_owned_process(process)

def inventory(root):
    owner=secrets.token_hex(8);plan={'owner':owner,'name':'gameyfin-public81-image-probe-'+owner,'id':None,'image':IMAGE};write(root/'image-probe.json',plan)
    try:
        identity=command('create','--pull','never','--name',plan['name'],'--label','gameyfin.public81.probe='+owner,'--network','none','--read-only','--restart','no','--user','1337:1337','--cap-drop','ALL','--security-opt','no-new-privileges','--cpus','.1','--memory','64m','--memory-swap','64m','--pids-limit','16','--entrypoint','/bin/true',IMAGE).strip()
        if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Image probe full CID')
        plan['id']=identity;write(root/'image-probe-cid.json',plan);validate_probe(json.loads(command('inspect',identity))[0],plan)
        def export(path,bound):
            with tarfile.open(fileobj=io.BytesIO(cp_bytes(identity,path,bound))) as archive:
                entries=[]
                for entry in archive:
                    entries.append(entry)
                    if len(entries)>34:raise ValueError('Selected image file count bound')
                if not 1<=len(entries)<=34 or len({e.name for e in entries})!=len(entries) or any(not regular_tar(e,allow_directory=True) for e in entries):raise ValueError('Regular bounded selected image files')
                return [(e.name,archive.extractfile(e).read(bound+1)) for e in entries if e.isfile()]
        application=export('/opt/gameyfin/application.jar',32*1024**2)
        if len(application)!=1 or PurePosixPath(application[0][0]).name!='application.jar':raise ValueError('Exact application JAR')
        jar=application[0][1];(root/'application.jar').write_bytes(jar)
        with zipfile.ZipFile(io.BytesIO(jar)) as zipped:
            entries=zipped.infolist()
            if len(entries)>8000 or len({e.filename for e in entries})!=len(entries) or sum(e.file_size for e in entries)>128*1024**2:raise ValueError('Application resource bound')
            mf=zipped.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','').replace('\n ','')
            matches=re.findall(r'(?<!\S)lib/h2-[0-9.]+\.jar(?!\S)',mf)
            if len(matches)!=1:raise ValueError('Exact H2 classpath dependency')
            version=zipped.read('META-INF/build-info.properties').decode()
            if version.splitlines().count('build.version=2.4.3')!=1:raise ValueError('Application build version')
            ui=[e for e in entries if not e.is_dir() and e.filename.startswith('META-INF/VAADIN/')]
            if not ui or sum(e.file_size for e in ui)>64*1024**2 or any(e.file_size>8*1024**2 for e in ui):raise ValueError('Original compiled UI resources')
            ui_hash=hashlib.sha256(json.dumps(sorted((e.filename,hashlib.sha256(zipped.read(e)).hexdigest()) for e in ui),separators=(',',':')).encode()).hexdigest()
        h2='/opt/gameyfin/'+matches[0]
        if len(export(h2,8*1024**2))!=1:raise ValueError('Image H2 library missing')
        plugins=root/'plugins';plugins.mkdir(mode=0o700);plugin_hashes={};direct=None
        for name,data in export('/opt/gameyfin/plugins/.',64*1024**2):
            filename=PurePosixPath(name).name
            if not re.fullmatch(r'[A-Za-z0-9_.-]{1,96}\.jar',filename) or filename in plugin_hashes:raise ValueError('Original plugin names')
            with zipfile.ZipFile(io.BytesIO(data)) as plugin:
                manifest=plugin.read('META-INF/MANIFEST.MF').decode()
                if re.search(r'^Plugin-Id: org\.gameyfin\.plugins\.download\.direct\s*$',manifest,re.M):direct=plugins/filename
            (plugins/filename).write_bytes(data);plugin_hashes[filename]=hashlib.sha256(data).hexdigest()
        if direct is None or not plugin_hashes:raise ValueError('Original direct plugin required')
        return {'jarSha256':hashlib.sha256(jar).hexdigest(),'uiResourceTreeSha256':ui_hash,'uiResourceCount':len(ui),'buildVersion':'2.4.3','h2Jar':h2,'pluginSha256':plugin_hashes},direct,tuple(plugins.iterdir())
    finally:cleanup_probe(root)

def cleanup_all():
    root=input_root()
    try:cleanup_current()
    finally:
        if root.exists():cleanup_probe(root)
    if root.exists():
        exact_inputs(root);ids=command('ps','-aq','--no-trunc').split()
        if len(ids)>128:raise ValueError('Disposable runner container count bound')
        rows=json.loads(command('inspect',*ids)) if ids else []
        if any(Path(m.get('Source','/')).resolve()==root or Path(m.get('Source','/')).resolve().is_relative_to(root) for row in rows for m in row.get('Mounts',[])):raise ValueError('Owned image inputs still referenced')
        exact_inputs(root);shutil.rmtree(root)

def execute(browser_image,output,ui_rounds=1):
    if type(ui_rounds) is not int or ui_rounds not in (1,3):raise ValueError('UI round count')
    hosted_only();root=input_root();root.mkdir(mode=0o700);info=root.stat();write(root/'OWNER.json',{'job':job_identity(),'device':info.st_dev,'inode':info.st_ino});create()
    report={'scope':SCOPE,'harnessSource':os.environ.get('GITHUB_SHA'),'complete':False,'sameProfileReloginProven':False,'scanProven':False,'performanceProven':False,'cleanupConfirmed':False};stage='artifact'
    if ui_rounds==3:report['scope']='Public81 retained-image three-cycle same-page/profile original-UI login/browse/logout only; not scanning, performance, private integration or release acceptance'
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(TimeoutError('Controller deadline')));signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('Controller deadline')));signal.alarm(570)
    try:
        report['artifact']=download(root);stage='image-inventory';evidence,direct,plugins=inventory(root);report['imageInventory']=evidence;stage='application'
        with OwnedApp(IMAGE,direct,None,expected_jar_sha=evidence['jarSha256'],ui_only=True,h2_jar=evidence['h2Jar'],original_plugins=plugins,watchdog_seconds=480,diagnostic_output=output.with_name('application.json')) as app:
            before=metrics(app.client)
            if any(before[k] for k in ('active','draining','completed','failed')):raise ValueError('UI-only case must not scan')
            stage='browser';cover_id=int(app.cover_path.rsplit('/',1)[1])
            with HostedBrowser(app.owner,browser_image,app.network,app.ip,cover_id,app.fixture['image']['sha256'],app_id=app.id,app_image=IMAGE,fixture_root=app.root,ui_version=True,ui_rounds=ui_rounds,diagnostic_output=output.with_name('browser.json')) as browser:
                if ui_rounds==3:report['sameProfileRounds']=[]
                for round_index in range(1,ui_rounds+1):
                    browser.authenticate('fixture-admin',app.password);report['authenticationEvidence']=dict(browser.authenticationEvidence)
                    if browser.authenticationEvidence['normalLoginAutoRedirectObserved'] is not True or browser.uiVersionVerified is not True:raise ValueError('Automatic original-UI landing/version required')
                    report['uiVersionVerified']=True;report['navigation']=browser.sample();report['normalUiLogoutPassed']=browser.authenticated is False
                    if ui_rounds==3:report['sameProfileRounds'].append({'round':round_index,'authenticationEvidence':dict(browser.authenticationEvidence),'uiVersionVerified':True,'navigation':report['navigation'],'normalUiLogoutPassed':report['normalUiLogoutPassed']})
            report['browserCleanupConfirmed']=True;stage='final-gates';verify_sources(app.root/'fixture',app.fixture['manifest']);after=metrics(app.client)
            if ui_rounds==3:report['sameProfileReloginProven']=len(report['sameProfileRounds'])==3 and all(r['round']==i and r['normalUiLogoutPassed'] is True and r['uiVersionVerified'] is True and all(r['authenticationEvidence'].get(k) is True for k in ('normalLoginAutoRedirectObserved','fixtureProfileVerified')) and all(r['navigation'].get(k) is True for k in ('originalUiContentReady','coverDecoded','coverSha256Matches')) and len(r['navigation']['navigations'])==3 for i,r in enumerate(report['sameProfileRounds'],1))
            if any(after[k]!=before[k] for k in ('active','draining','completed','failed')):raise ValueError('UI-only scan counters changed')
            report['sourceIntegrityUnchanged']=True;report['noScansStarted']=True;report['health']=app.final_gate()
        report['applicationCleanupConfirmed']=True
    except Exception as error:
        known={'Artifact download rejected':'artifact-http','Artifact archive integrity':'artifact-digest','Recorded tar checksum':'tar-digest','Exact H2 classpath dependency':'h2-classpath','Original compiled UI resources':'ui-resources','Original direct plugin required':'direct-plugin','UI-only case must not scan':'scan-observed','Automatic original-UI landing/version required':'landing-or-version'}
        report['failure']={'stage':stage,'category':type(error).__name__ if type(error).__name__ in ('ValueError','RuntimeError','TimeoutError','TimeoutExpired','CalledProcessError','OSError') else 'other','reasonCode':known.get(str(error),'other')}
        raise
    finally:
        try:cleanup_all();report['cleanupConfirmed']=True
        finally:
            signal.alarm(0);report['complete']='failure' not in report and (ui_rounds==1 or report.get('sameProfileReloginProven') is True) and all(report.get(k) is True for k in ('applicationCleanupConfirmed','browserCleanupConfirmed','cleanupConfirmed','uiVersionVerified','normalUiLogoutPassed','noScansStarted','sourceIntegrityUnchanged')) and all(report.get('authenticationEvidence',{}).get(k) is True for k in ('normalLoginAutoRedirectObserved','fixtureProfileVerified'));output.parent.mkdir(parents=True,exist_ok=True);write(output,report)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--browser-image');parser.add_argument('--output',type=Path);parser.add_argument('--cleanup-only',action='store_true');parser.add_argument('--ui-rounds',type=int,choices=(1,3),default=1);args=parser.parse_args();hosted_only()
    if args.cleanup_only:cleanup_all()
    else:execute(args.browser_image,args.output,args.ui_rounds)
if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Focused public UI pass failed; sanitized evidence/owned cleanup only') from None
