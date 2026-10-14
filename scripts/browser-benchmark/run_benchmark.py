"""Reviewed-source hostedCI original-app/UI benchmark; no publish or production."""
import argparse,hashlib,json,re,sys,time
from pathlib import Path
from fixture_case import cover_route,verify_sources
from fixture_adapter import expected_ids,validate_enrichment
from hosted_browser import HostedBrowser
from owned_app import OwnedApp,command,hosted_only
from throughput_case import balanced_pairs,metrics,run_case

CONTROL='344de32e7939d8223c8e023781b3cb8738610cea'
CANDIDATE='a567d5e6e682fe6236b7908e8b1ef06722584caa'

def responsiveness_accepted(reports,waiting_authentication):
    return (len(reports)==6 and all(row['report'].get('cleanAcceptance') is True for row in reports)
        and isinstance(waiting_authentication,dict)
        and set(waiting_authentication)=={'normalLoginAutoRedirectObserved','fixtureProfileVerified'}
        and all(value is True for value in waiting_authentication.values()))

def validate_receipt(receipt,control_image,candidate_image):
    if receipt.get('controlSource')!=CONTROL or receipt.get('candidateSource')!=CANDIDATE:
        raise ValueError('Exact reviewed source pair required')
    if receipt.get('entryByteParity') is not True or receipt.get('normalizationPerformed') is not False or receipt.get('differingResourceEntries')!=[]:
        raise ValueError('Exact original UI/dependency resource byte parity required')
    for arm,image in [('control',control_image),('candidate',candidate_image)]:
        if not re.fullmatch('sha256:[a-f0-9]{64}',image) or receipt.get(arm+'ImageId')!=image or not re.fullmatch('[a-f0-9]{64}',receipt.get(arm+'JarSha256','')):
            raise ValueError('Immutable image/JAR receipt identity required')
    return receipt

def image_source(image,source):
    rows=json.loads(command('image','inspect',image))
    if len(rows)!=1 or rows[0]['Id']!=image or rows[0].get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')!=source:
        raise ValueError('Actual locally built image source mismatch')

def waiting_validation(app,before):
    # Separate SINGLE library/game held correctness case, not all132 refresh.
    deadline=time.monotonic()+90
    while True:
        current=metrics(app.client)
        if current['failed']!=before['failed']:raise ValueError('Held scan failed')
        if current['completed']-before['completed']==1 and current['active']==0 and current['draining']==0:break
        if time.monotonic()>=deadline:raise TimeoutError('Held scan completion/quiescence deadline')
        time.sleep(.25)
    games=app.client.request('/connect/GameEndpoint/getAll',{})
    if len(games)!=132 or {g['id'] for g in games}!=expected_ids():raise ValueError('Held fixture retained catalog identity')
    manifest=app.fixture['manifest'];target=next(g for g in games if g['id']==33000)
    target_seed=next(g for g in manifest['games'] if g['gameId']==33000)
    validate_enrichment([target],{'games':[target_seed]},expected_count=1)
    by_id={g['id']:g for g in games}
    for seed in manifest['games']:
        pinned=[v for v in by_id[seed['gameId']]['variants'] if v['id']==seed['variantId']]
        if len(pinned)!=1 or pinned[0].get('default') is not True or pinned[0].get('defaultLocked') is not True:
            raise ValueError('Held fixture pinned defaults changed')
    verify_sources(app.root/'fixture',manifest);app.client.image_gate(app.cover_path)
    downloads=app.client.downloads(manifest);gate=app.final_gate()
    return {'scanCompleted':True,'failedLibraries':0,'retainedGameCount':132,'target33000Enriched':True,
        'all132PinnedDefaultsPresent':True,'sourceFileCount':924,'sourceHashesUnchanged':True,'coverAssociationStable':True,
        'unexpectedErrorCount':gate['unexpectedErrorCount'],'oomKilled':gate['oomKilled'],
        'exactDownloadChecks':[d['selectedBytesMatch'] for d in downloads]}

def execute(args):
    hosted_only();receipt=validate_receipt(json.loads(Path(args.receipt).read_text()),args.control_image,args.candidate_image)
    image_source(args.control_image,CONTROL);image_source(args.candidate_image,CANDIDATE)
    output=Path(args.output)
    if output.exists() or output.is_symlink():raise ValueError('New sanitized benchmark output required')
    output.parent.mkdir(parents=True,exist_ok=True)
    from ownership_index import create
    create()
    reports=[]
    summary={'scope':'Hosted synthetic original-UI benchmark; no real provider traffic, production or cutover acceptance',
        'sourceReceipt':receipt,'applicationResources':{'cpuLimit':.5,'memoryMiB':1024,'heapMiB':320},
        'browserResources':{'cpuLimit':1,'memoryMiB':768,'excludedFromAppSavings':True},
        'pluginSha256':{name:hashlib.sha256(Path(getattr(args,name)).read_bytes()).hexdigest() for name in ('metadata_plugin','waiting_plugin','direct_plugin')},
        'results':reports}
    def persist():
        data=json.dumps(summary,indent=2)
        if len(data.encode())>4*1024*1024:raise ValueError('Sanitized report byte bound')
        pending=output.with_suffix('.pending');pending.write_text(data);pending.replace(output)
    def browser(app,phase):
        cover_id=int(app.cover_path.rsplit('/',1)[1])
        value=HostedBrowser(app.owner,args.browser_image,app.network,app.ip,cover_id,app.fixture['image']['sha256'],
            app_id=app.id,app_image=app.image,fixture_root=app.root,waiting=app.waiting_root is not None,
            diagnostic_output=output.with_name('browser-'+app.owner+'-'+phase+'.json'))
        try:value.authenticate('fixture-admin',app.password);return value
        except Exception:value.close();raise
    def arm(arm,pair,position):
        image=args.control_image if arm=='control' else args.candidate_image
        row={'pair':pair,'position':position,'arm':arm,'source':CONTROL if arm=='control' else CANDIDATE,'image':image}
        reports.append(row);persist()
        def retain_partial(report):
            row['report']=report
            persist()
        try:
            with OwnedApp(image,args.direct_plugin,args.metadata_plugin,expected_jar_sha=receipt[arm+'JarSha256'],
                diagnostic_output=output.with_name(f'application-{pair}-{position}-{arm}.json')) as app:
                row['fixtureManifestSha256']=app.fixture['manifestSha256']
                row['report']=run_case(app.client,app.fixture,app.cover_path,lambda phase:browser(app,phase),app.resources,
                    lambda:verify_sources(app.root/'fixture',app.fixture['manifest']),persist_partial=retain_partial)
                row['finalGate']=app.final_gate()
            row['exactOwnedCleanupPassed']=True;persist()
            return row['report']
        except Exception as error:
            row['failureClass']=type(error).__name__;persist();raise
    try:
        balanced=balanced_pairs(arm)
        summary['balancedCaseOrder']=balanced['caseOrder'];persist()
        sys.path.insert(0,str(Path(__file__).with_name('overlap')))
        from original_app_waiting_case import run_case as held_case
        with OwnedApp(args.candidate_image,args.direct_plugin,args.waiting_plugin,
            expected_jar_sha=receipt['candidateJarSha256'],waiting=True,diagnostic_output=output.with_name('application-waiting.json')) as app:
            before=metrics(app.client)
            if before['active'] or before['draining']:raise ValueError('Held fixture starts nonidle')
            with browser(app,'waiting') as held_browser:
                summary['waitingAuthenticationEvidence']=dict(held_browser.authenticationEvidence)
                persist()
                prerequisites={'syntheticFixtureOnly':True,'normalAdminAuthenticated':True,'browserAlreadyAuthenticated':True,
                    'originalGameyfinUi':True,'boundedBrowserOperationSeconds':15,'publishedPorts':False,'cpuLimit':.5,
                    'memoryMiB':1024,'heapMiB':320,'waitingProviderOptIn':True,'scanLibraryId':27003,'gameId':33000}
                def finish_waiting_case():
                    # held_case releases the provider before this callback.
                    # Logout already shared the held 15s sample budget. Remove
                    # its owned container before lengthy scan validation.
                    held_browser.close()
                    return waiting_validation(app,before)
                summary['waitingProviderCorrectness']=held_case(app.waiting_root,app.client.request,held_browser.sample,
                    finish_waiting_case,prerequisites)
        summary['waitingExactOwnedCleanupPassed']=True
        summary['allCasesCompleted']=True
        summary['responsivenessAccepted']=responsiveness_accepted(reports,summary.get('waitingAuthenticationEvidence'))
        persist()
        if not summary['responsivenessAccepted']:raise ValueError('All measured cases retained but responsiveness overlap/deadline acceptance unmet')
    except Exception as error:
        summary['failureClass']=type(error).__name__;summary.setdefault('allCasesCompleted',False);persist();raise

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cleanup-only',action='store_true')
    for name in ('control-image','candidate-image','browser-image','metadata-plugin','waiting-plugin','direct-plugin','output','receipt'):
        parser.add_argument('--'+name)
    args=parser.parse_args()
    if args.cleanup_only:
        from ownership_index import cleanup_current
        hosted_only();cleanup_current()
        return
    if any(getattr(args,n) is None for n in ('control_image','candidate_image','browser_image','metadata_plugin','waiting_plugin','direct_plugin','output','receipt')):
        parser.error('All execution inputs required except in cleanup-only mode')
    try:execute(args)
    except Exception as error:
        print(json.dumps({'benchmarkFailed':True,'failureClass':type(error).__name__}));raise SystemExit(1)
if __name__=='__main__':main()
