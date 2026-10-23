"""Local draft hosted-only technical diagnostic; never app build/publish/deploy."""
import argparse,hashlib,json,os,signal,subprocess,sys,time,zipfile
from pathlib import Path
from retained_scan_image import SOURCE,IMAGE,input_root,write,download,inventory,cleanup_all,hash_file
from owned_app import OwnedApp,hosted_only
from ownership_index import create,job_identity,index_path
from hosted_browser import HostedBrowser
from throughput_case import metrics
from fixture_case import verify_sources
from concurrent_scrape_probe import scrape,concurrent_probe

def build_provider(root,evidence):
    base=Path(__file__).resolve().parent;deps=evidence['fixtureDependencies']
    if set(deps)!={'plugin-api','pf4j','kotlin-stdlib'}:raise ValueError('Exact image dependency contract')
    for row in deps.values():
        path=Path(row['path'])
        if path.parent!=root/'fixture-dependencies' or path.is_symlink() or hash_file(path)!=row['sha256']:raise ValueError('Exact extracted dependency bytes')
    home=Path(os.environ['JAVA_HOME']);javac=home/'bin/javac';java=home/'bin/java'
    if not javac.is_file()or not java.is_file():raise ValueError('Explicit hosted JDK required for fixture only')
    output=root/'positive-metadata-fixture.jar'
    command=[sys.executable,'-B',str(base/'build_fixture.py'),'--javac',str(javac),'--java',str(java),
        '--output',str(output),*sum((['--'+name,row['path']]for name,row in deps.items()),[])]
    subprocess.run(command,check=True,capture_output=True,timeout=120)
    with zipfile.ZipFile(output)as jar:
        manifest=jar.read('META-INF/MANIFEST.MF')
        if b'Plugin-Id: org.gameyfin.experiments.fixture.metadata\n'not in manifest:raise ValueError('Exact positive fixture manifest')
    return output,{'fixtureJarSha256':hash_file(output),'fixtureManifestSha256':hashlib.sha256(manifest).hexdigest(),
        'sourceSha256':{name:hash_file(base/name)for name in ('build_fixture.py','FixtureMetadataPlugin.java','FixtureProviderTest.java')},
        'dependencySha256':{name:row['sha256']for name,row in deps.items()},'applicationRebuilt':False}

def plugin_proof(app,evidence,provider,provider_sha):
    expected={**evidence['pluginSha256'],provider.name:provider_sha}
    actual={path.name:hash_file(path)for path in (app.root/'plugins').glob('*.jar')if path.is_file()and not path.is_symlink()}
    if actual!=expected:raise ValueError('Original bundle or fixture JAR changed in actual application mount')
    return {'allOriginalPluginJarsUnchanged':True,'fixtureJarUnchanged':True,'jarCount':len(expected)}

def execute(browser_image,output):
    hosted_only();root=input_root()
    if output.exists()or output.is_symlink()or output.with_suffix('.pending').exists()or output.with_suffix('.pending').is_symlink()or any(p.is_symlink()for p in(output.parent,*output.parent.parents)):raise ValueError('New regular technical receipt required')
    if root.exists()or root.is_symlink()or index_path().exists():raise ValueError('Exclusive current-job inputs required')
    report={'scope':'One retained-image ordinary scan technical probe; not paired performance or cutover acceptance',
        'source':SOURCE,'image':IMAGE,'complete':False,'performanceAccepted':False,'continuousOverlapProven':False,'cleanupConfirmed':False}
    stage='input-ownership';app=None;root_created=False
    def persist():
        data=json.dumps(report,allow_nan=False)
        if len(data.encode())>1024*1024:raise ValueError('Technical diagnostic receipt bound')
        output.parent.mkdir(parents=True,exist_ok=True)
        pending=output.with_suffix('.pending')
        with pending.open('x')as stream:stream.write(data)
        pending.replace(output)
    def timeout(*args):raise TimeoutError('Existing public controller570second deadline')
    signal.signal(signal.SIGTERM,timeout);signal.signal(signal.SIGALRM,timeout);signal.alarm(570)
    try:
        root.mkdir(mode=0o700);root_created=True;info=root.stat()
        write(root/'OWNER.json',{'job':job_identity(),'device':info.st_dev,'inode':info.st_ino});create()
        stage='artifact';report['artifact']=download(root);stage='image-inventory'
        def retain_inventory(observation):report['inventoryObservation']=observation;persist()
        evidence,direct,plugins=inventory(root,on_observation=retain_inventory)
        report['inventory']={k:v for k,v in evidence.items()if k!='fixtureDependencies'}
        stage='fixture-provider';provider,report['providerBuild']=build_provider(root,evidence);persist()
        stage='application'
        with OwnedApp(IMAGE,direct,provider,expected_jar_sha=evidence['jarSha256'],
            retained_scan={'inventory':evidence,'plugins':plugins},diagnostic_output=output.with_name('application.json'))as app:
            verify_sources(app.root/'fixture',app.fixture['manifest'])
            report['pluginBefore']=plugin_proof(app,evidence,provider,report['providerBuild']['fixtureJarSha256'])
            before=metrics(app.client)
            if any(before[k]for k in ('active','draining','completed','failed')):raise ValueError('Fresh unscanned fixture required')
            stage='quiescent-probes';report['quiescentProbes']=[scrape(app.ip,kind)for kind in ('filtered','unfiltered')];persist()
            stage='original-browser'
            with HostedBrowser(app.owner,browser_image,app.network,app.ip,int(app.cover_path.rsplit('/',1)[1]),
                app.fixture['image']['sha256'],app_id=app.id,app_image=IMAGE,fixture_root=app.root,
                diagnostic_output=output.with_name('browser.json'))as browser:
                browser.authenticate('fixture-admin',app.password)
                if browser.authenticationEvidence!={'normalLoginAutoRedirectObserved':True,'fixtureProfileVerified':True}:raise ValueError('Normal UI identity and landing required')
                stage='concurrent-scan-browser';started=time.monotonic();report['resourceBefore']=app.resources()
                def retain_concurrent(value):report['concurrent']=value;persist()
                report['concurrent']=concurrent_probe(app.client.scan,browser.sample,lambda kind:scrape(app.ip,kind),on_observation=retain_concurrent);persist()
            report['browserCleanupConfirmed']=True;stage='scan-completion'
            deadline=started+180
            while True:
                after=metrics(app.client)
                if after['failed']!=before['failed']:raise ValueError('Ordinary scan failed')
                completed=after['completed']-before['completed']
                if completed==4 and after['active']==0 and after['draining']==0:break
                if completed>4 or time.monotonic()>=deadline:raise ValueError('Exact four scan completions deadline')
                time.sleep(1)
            report['resourceAfter']=app.resources();report['completionObservedSeconds']=time.monotonic()-started
            report['functional']=app.client.validate(app.fixture,app.cover_path)
            verify_sources(app.root/'fixture',app.fixture['manifest']);report['sourceIntegrityUnchanged']=True
            report['pluginAfter']=plugin_proof(app,evidence,provider,report['providerBuild']['fixtureJarSha256'])
            stage='final-gate'
            def retain_gate(gate):report['finalGate']=gate;persist()
            app.final_gate(on_observation=retain_gate)
        report['appCleanupConfirmed']=True
    except BaseException as error:
        report['failure']={'stage':stage,'category':type(error).__name__ if type(error).__name__ in ('ValueError','RuntimeError','TimeoutError','OSError') else 'other'}
        raise
    finally:
        report['appCleanupOutcome']='complete'if getattr(app,'cleanup_completed',None)is True else'incomplete'if getattr(app,'cleanup_completed',None)is False else'not-observed'
        cleanup_error=None
        try:
            if root_created:cleanup_all();report['cleanupConfirmed']=True;report['cleanupOutcome']='complete'
            else:report['cleanupOutcome']='not-observed'
        except BaseException as error:
            cleanup_error=error;report['cleanupConfirmed']=False;report['cleanupOutcome']='unknown-retained'
        finally:
            signal.alarm(0);report['complete']='failure'not in report and all(report.get(k)is True for k in('browserCleanupConfirmed','appCleanupConfirmed','sourceIntegrityUnchanged','cleanupConfirmed'))
            persist()
        if cleanup_error is not None:raise RuntimeError('Owned cleanup uncertain; scoped inputs retained')from None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--browser-image');parser.add_argument('--output',type=Path)
    parser.add_argument('--parent-reviewed-one-app',action='store_true');parser.add_argument('--cleanup-only',action='store_true');args=parser.parse_args()
    hosted_only()
    if args.cleanup_only:
        if args.browser_image or args.output or args.parent_reviewed_one_app:raise ValueError('Exact cleanup-only mode')
        cleanup_all();return
    if not args.parent_reviewed_one_app or not args.browser_image or not args.output:raise ValueError('Explicit reviewed one-app inputs required')
    execute(args.browser_image,args.output)

if __name__=='__main__':
    try:main()
    except BaseException:raise SystemExit('One-app diagnostic failed; retain only bounded sanitized receipts')from None
