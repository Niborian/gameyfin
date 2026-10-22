"""Exact-owned hosted smoke; no application/source/auth mounts or local Docker."""
import argparse,json,os,re,secrets,subprocess,sys,time
from pathlib import Path
from hosted_browser import command,creation_args,read_state,CREATION_PLAN
from owned_container_watchdog import validate,validate_cleanup_scope

def ledger_path(cancel_probe=False):
    if type(cancel_probe) is not bool:raise ValueError('Mode boundary')
    if os.name!='posix' or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':raise ValueError('Hosted only')
    values=[os.environ.get(k,'') for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_JOB')]
    if any(not re.fullmatch('[A-Za-z0-9_-]{1,64}',x) for x in values):raise ValueError('Job identity')
    root=Path(os.environ['RUNNER_TEMP']).resolve()
    if any(x.is_symlink() for x in [root,*root.parents]):raise ValueError('Regular private temp')
    return root/(('gameyfin-chrome-cancel-probe-' if cancel_probe else 'gameyfin-chrome-smoke-')+'-'.join(values)+'.json')

def budget_command(deadline,*args):
    remaining=deadline-time.monotonic()
    if remaining<=0:raise TimeoutError('Controller deadline')
    return subprocess.run(['docker',*args],check=True,capture_output=True,text=True,timeout=min(10,remaining)).stdout

def mode_plan(cancel_probe):
    if type(cancel_probe) is not bool:raise ValueError('Mode boundary')
    return ['/opt/gameyfin-browser/'+('cdp_cancel_probe.mjs' if cancel_probe else 'chrome_smoke.mjs')]

def cleanup(path,cancel_probe=False,deadline=None):
    if path!=(ledger_path(True) if cancel_probe else ledger_path()):raise ValueError('Exact current-job ledger')
    call=(lambda *args:budget_command(deadline,*args)) if deadline is not None else command
    if not path.exists():return
    plan=read_state(path,4096)
    if set(plan)!={'owner','image','id','contract','entrypoint','cmd'}|({'purpose'} if cancel_probe else set()) or (plan['id'] is not None and (not isinstance(plan['id'],str) or not re.fullmatch('[a-f0-9]{64}',plan['id']))):raise ValueError('Strict intent schema')
    owner=plan['owner'];image=plan['image'];network='gameyfin-browser-net-'+owner;name=('gameyfin-browser-cancel-probe-' if cancel_probe else 'gameyfin-browser-')+owner
    if plan.get('contract')!=CREATION_PLAN or plan.get('entrypoint')!=['node'] or plan.get('cmd')!=mode_plan(cancel_probe) or cancel_probe and plan.get('purpose')!='chrome-cancel-probe-only':raise ValueError('Reviewed creation intent')
    if not re.fullmatch('[a-f0-9]{16}',owner) or not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Intent identity')
    identities=call('ps','-aq','--no-trunc').split()
    for identity in identities:
        row=json.loads(call('inspect',identity))[0]
        if row.get('Name')!='/'+name and identity!=plan.get('id'):continue
        if plan.get('id') is not None and identity!=plan['id']:raise ValueError('Immutable smoke identity')
        if cancel_probe:validate_cleanup_scope(row,identity,owner,image,network_none=True)
        else:validate_cleanup_scope(row,identity,owner,image)
        if row['Config'].get('Entrypoint')!=plan['entrypoint'] or row['Config'].get('Cmd')!=plan['cmd']:raise ValueError('Smoke entry identity')
        call('rm','--force',identity)
        if identity in call('ps','-aq','--no-trunc').split():raise ValueError('Exact removed-container confirmation')
    networks=[] if cancel_probe else call('network','ls','--filter','name=^'+network+'$','--format','{{.ID}}').split()
    for identity in networks:
        row=json.loads(call('network','inspect',identity))[0]
        if row.get('Name')!=network or row.get('Driver')!='bridge' or not row.get('Internal') or row.get('Labels',{}).get('gameyfin.browser.owner')!=owner or row.get('Containers'):raise ValueError('Exact isolated smoke network')
        call('network','rm',row['Id'])
    if plan.get('id') is not None and plan['id'] in call('ps','-aq','--no-trunc').split():raise ValueError('Recorded ID still present')
    path.unlink()

def run(image,output,cancel_probe=False):
    # Reserve four seconds for reaping the independently retained watchdog.
    deadline=time.monotonic()+96 if cancel_probe else None;call=(lambda *args:budget_command(deadline,*args)) if cancel_probe else command
    path=ledger_path(True) if cancel_probe else ledger_path();owner=secrets.token_hex(8);network='gameyfin-browser-net-'+owner
    if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Immutable image')
    plan={'owner':owner,'image':image,'id':None,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':mode_plan(cancel_probe)}
    if cancel_probe:plan['purpose']='chrome-cancel-probe-only'
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'w') as stream:json.dump(plan,stream)
    watchdog=None;receipt=None;accepted=False
    try:
        watchdog=subprocess.Popen([sys.executable,__file__,'--watchdog']+(['--cancel-probe'] if cancel_probe else []),stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        if not cancel_probe:call('network','create','--internal','--label','gameyfin.browser.owner='+owner,network)
        args=creation_args(owner,image,network,'172.22.0.2',1,'a'*64)
        args=args[:-4] # remove image and fixture-only three arguments
        if cancel_probe:
            args[args.index('--network')+1]='none';args[args.index('--name')+1]='gameyfin-browser-cancel-probe-'+owner;args+=['--label','gameyfin.browser.purpose=cancel-probe-only']
        args+=['--entrypoint','node',image,*plan['cmd']]
        identity=call(*args).strip()
        if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Full created identity')
        plan['id']=identity;pending=path.with_suffix('.pending')
        fd=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
        with os.fdopen(fd,'w') as stream:json.dump(plan,stream)
        pending.replace(path)
        row=json.loads(call('inspect',identity))[0]
        if cancel_probe:validate(row,identity,owner,image,network_none=True)
        else:validate(row,identity,owner,image)
        h=row['HostConfig']
        if h.get('ShmSize')!=128*1024**2 or h.get('Tmpfs')!={'/tmp':'rw,nosuid,nodev,size=128m,mode=1777'} or h.get('SecurityOpt') not in (['no-new-privileges'],['no-new-privileges=true'],['no-new-privileges:true']):raise ValueError('Matched smoke readonly/tmpfs/security boundary')
        if row['Config'].get('Entrypoint')!=plan['entrypoint'] or row['Config'].get('Cmd')!=plan['cmd']:raise ValueError('Known smoke command')
        if not cancel_probe:
            net=json.loads(call('network','inspect',network))[0]
            if net.get('Name')!=network or net.get('Driver')!='bridge' or net.get('Labels',{}).get('gameyfin.browser.owner')!=owner or not net.get('Internal') or set(net.get('Containers',{})) not in (set(),{identity}):raise ValueError('No unexpected smoke peers')
        result=subprocess.run(['docker','start','--attach',identity],capture_output=True,text=True,timeout=min(92,max(0.01,deadline-time.monotonic()-8)) if cancel_probe else 25)
        if len(result.stdout.encode())>(16384 if cancel_probe else 8192):raise ValueError('Smoke receipt bound')
        receipt=json.loads(result.stdout)
        if cancel_probe:validate_probe_receipt(receipt)
        else:validate_receipt(receipt)
        output.parent.mkdir(parents=True,exist_ok=True)
        if cancel_probe:write_probe_output(output.with_suffix('.provisional.json'),receipt)
        else:output.write_text(json.dumps(receipt,indent=2))
        if result.returncode or (not receipt['complete'] if cancel_probe else not receipt['started'] or not receipt['sandboxVerified']):raise RuntimeError('Chrome smoke rejected')
        accepted=True
    finally:
        try:
            if cancel_probe:cleanup(path,True,deadline)
            else:cleanup(path)
        finally:
            if watchdog:
                watchdog.terminate()
                try:watchdog.wait(timeout=2)
                except subprocess.TimeoutExpired:watchdog.kill();watchdog.wait(timeout=2)
    if cancel_probe and accepted:
        final={**receipt,'containerCleanupConfirmed':True};validate_probe_receipt(final,controller=True);write_probe_output(output,final)

def write_probe_output(path,row):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'w') as stream:json.dump(row,stream,indent=2,allow_nan=False)

def validate_probe_receipt(row,*,controller=False):
    from hosted_browser import validate_eligibility_diagnostic
    keys={'scope','complete','sandboxVerified','cases','failure','cleanup','listenersClosed'}|({'containerCleanupConfirmed'} if controller else set())
    if type(controller) is not bool or not isinstance(row,dict) or controller and row.get('containerCleanupConfirmed') is not True:raise ValueError('Probe controller cleanup proof')
    if not isinstance(row,dict) or set(row)!=keys or row['scope']!='Owned Chrome-only cancellation ordering; not application or performance acceptance' or any(type(row[k]) is not bool for k in ('complete','sandboxVerified','listenersClosed')) or row['failure'] not in ('none','probe-failed-or-not-reproduced'):raise ValueError('Probe receipt schema')
    if not isinstance(row['cleanup'],dict) or set(row['cleanup'])!={'browserClosed','profileRemoved','forcedTermination','complete'} or any(type(v) is not bool for v in row['cleanup'].values()) or row['cleanup']['complete']!=(row['cleanup']['browserClosed'] and row['cleanup']['profileRemoved'] and not row['cleanup']['forcedTermination']):raise ValueError('Probe cleanup schema')
    cases=row['cases']
    if not isinstance(cases,list) or len(cases)>2:raise ValueError('Probe cases bound')
    for index,case in enumerate(cases):
        if not isinstance(case,dict) or set(case)!={'kind','complete','category','terminalCancellationAccepted','matchingCancellationObserved','continueCommands','eligibility'} or case['kind']!=('current-document','old-loader')[index] or any(type(case[k]) is not bool for k in ('complete','terminalCancellationAccepted','matchingCancellationObserved')) or case['category'] not in ('none','interception-invalid-id','execution-context-destroyed','execution-context-missing','target-closed','protocol-timeout','other') or type(case['continueCommands']) is not int or case['continueCommands']!=1:raise ValueError('Probe case schema')
        e=validate_eligibility_diagnostic(case['eligibility'])
        expected=case['category']=='interception-invalid-id' and case['matchingCancellationObserved'] and (case['terminalCancellationAccepted'] and e['reason']=='eligible' if index==0 else not case['terminalCancellationAccepted'] and e['loaderMatches'] is False)
        if case['complete'] and not expected:raise ValueError('Probe false case completion')
    if row['complete'] and not (row['sandboxVerified'] and row['listenersClosed'] and row['cleanup']['complete'] and row['failure']=='none' and len(cases)==2 and all(c['complete'] for c in cases)):raise ValueError('Probe false completion')
    return row

def validate_receipt(row):
    required={'scope','chromeCacheVersion','started','sandboxVerified','exitCode','signal','signalScope','deadlineTriggered','exitObservedBeforeCleanup','failurePhase','fatalFlags','zygoteChecks','fatalExcerpt','before','after'}
    if not isinstance(row,dict) or set(row) not in (required,required|{'failure'}):raise ValueError('Smoke schema')
    if row['scope']!='Chrome-only startup diagnostic; not app performance' or row['signalScope']!='Includes diagnostic shutdown; not automatic crash attribution' or any(type(row[k]) is not bool for k in ('started','sandboxVerified','deadlineTriggered','exitObservedBeforeCleanup')):raise ValueError('Smoke flags')
    if row['failurePhase'] not in (None,'launch','endpoint','connect','sandbox'):raise ValueError('Failure phase')
    if row['chromeCacheVersion'] is not None and (not isinstance(row['chromeCacheVersion'],str) or not re.fullmatch(r'\d{1,5}\.\d{1,5}\.\d{1,5}\.\d{1,5}',row['chromeCacheVersion'])):raise ValueError('Chrome cache version boundary')
    if row['signal'] not in ('none','other','SIGABRT','SIGTRAP','SIGSYS','SIGSEGV','SIGKILL','SIGTERM'):raise ValueError('Signal enum')
    if row['exitCode'] is not None and (type(row['exitCode']) is not int or not 0<=row['exitCode']<=255):raise ValueError('Exit code')
    flags={'pthreadCreate','resourceUnavailable','checkFailed','seccomp','ptrace','credentialsSource','zygoteSource'}
    if not isinstance(row['fatalFlags'],dict) or set(row['fatalFlags'])!=flags or any(type(x) is not bool for x in row['fatalFlags'].values()):raise ValueError('Fatal hints')
    checks=row['zygoteChecks'];names={'processValid','socketpair','receiveProcessId','bootPid','realPid'}
    if not isinstance(checks,dict) or set(checks)!=names|{'errno','sourceLine'} or any(type(checks[x]) is not bool for x in names) or (checks['errno'] is not None and (type(checks['errno']) is not int or checks['errno'] not in (1,11,13,28,38,95))) or (checks['sourceLine'] is not None and (type(checks['sourceLine']) is not int or not 0<=checks['sourceLine']<=100000)):raise ValueError('Known zygote predicate hints')
    excerpt=row['fatalExcerpt']
    if not isinstance(excerpt,dict) or set(excerpt)!={'lines','rejected'} or type(excerpt['rejected']) is not bool or not isinstance(excerpt['lines'],list) or len(excerpt['lines'])>16:raise ValueError('Excerpt boundary')
    forbidden=r'password|passwd|secret|token|auth|cookie|credential|csrf|(?:api|client|private)[_ -]?key|client[_-]?id|session|\bsid\b|\bkey\b|\bbearer\b|\bENV\b|command.?line|\b[A-Za-z_][A-Za-z0-9_]*\s*=\s*[^=\s]|[a-z][a-z0-9+.-]*://|[A-Za-z]:[\\/]|(?:\d{1,3}\.){3}\d{1,3}|(?:[a-f0-9]{1,4}:){2,}[a-f0-9:]+|\b[a-f0-9]{16,}\b'
    if any(not isinstance(line,str) or len(line)>256 or re.search(r'[^\x20-\x7e\t]',line) or re.search(forbidden,line,re.I) or '/' in line or '\\' in line for line in excerpt['lines']) or sum(len(x.encode()) for x in excerpt['lines'])+max(0,len(excerpt['lines'])-1)>2048:raise ValueError('Redacted excerpt safety')
    keys={'pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill'}
    for part in ('before','after'):
        data=row[part]
        if not isinstance(data,dict) or set(data)!=keys or any(x is not None and (type(x) is not int or not 0<=x<=2**53-1) for x in data.values()):raise ValueError('Counters')
    if 'failure' in row:
        from hosted_browser import validate_failure_receipt
        validate_failure_receipt({'browserFailed':True,'phase':'chrome-launch','launchFailureCategory':'other','launchDiagnostics':row['failure']})
    return row

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--image');parser.add_argument('--output',type=Path);parser.add_argument('--cleanup-only',action='store_true');parser.add_argument('--watchdog',action='store_true');parser.add_argument('--cancel-probe',action='store_true');args=parser.parse_args()
    if args.watchdog:
        time.sleep(95 if args.cancel_probe else 30)
        cleanup(ledger_path(True),True,time.monotonic()+5) if args.cancel_probe else cleanup(ledger_path())
    elif args.cleanup_only:cleanup(ledger_path(True),True,time.monotonic()+10) if args.cancel_probe else cleanup(ledger_path())
    else:run(args.image,args.output,args.cancel_probe)
