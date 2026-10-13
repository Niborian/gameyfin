"""Exact-owned hosted smoke; no application/source/auth mounts or local Docker."""
import argparse,json,os,re,secrets,subprocess,sys,time
from pathlib import Path
from hosted_browser import command,creation_args,read_state,CREATION_PLAN
from owned_container_watchdog import validate,validate_cleanup_scope

def ledger_path():
    if os.name!='posix' or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':raise ValueError('Hosted only')
    values=[os.environ.get(k,'') for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_JOB')]
    if any(not re.fullmatch('[A-Za-z0-9_-]{1,64}',x) for x in values):raise ValueError('Job identity')
    root=Path(os.environ['RUNNER_TEMP']).resolve()
    if any(x.is_symlink() for x in [root,*root.parents]):raise ValueError('Regular private temp')
    return root/('gameyfin-chrome-smoke-'+'-'.join(values)+'.json')

def cleanup(path):
    if path!=ledger_path():raise ValueError('Exact current-job ledger')
    if not path.exists():return
    plan=read_state(path,4096)
    if set(plan)!={'owner','image','id','contract','entrypoint','cmd'} or (plan['id'] is not None and (not isinstance(plan['id'],str) or not re.fullmatch('[a-f0-9]{64}',plan['id']))):raise ValueError('Strict intent schema')
    owner=plan['owner'];image=plan['image'];network='gameyfin-browser-net-'+owner;name='gameyfin-browser-'+owner
    if plan.get('contract')!=CREATION_PLAN or plan.get('entrypoint')!=['node'] or plan.get('cmd')!=['/opt/gameyfin-browser/chrome_smoke.mjs']:raise ValueError('Reviewed creation intent')
    if not re.fullmatch('[a-f0-9]{16}',owner) or not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Intent identity')
    identities=command('ps','-aq','--no-trunc').split()
    for identity in identities:
        row=json.loads(command('inspect',identity))[0]
        if row.get('Name')!='/'+name and identity!=plan.get('id'):continue
        if plan.get('id') is not None and identity!=plan['id']:raise ValueError('Immutable smoke identity')
        validate_cleanup_scope(row,identity,owner,image)
        if row['Config'].get('Entrypoint')!=plan['entrypoint'] or row['Config'].get('Cmd')!=plan['cmd']:raise ValueError('Smoke entry identity')
        command('rm','--force',identity)
        if identity in command('ps','-aq','--no-trunc').split():raise ValueError('Exact removed-container confirmation')
    networks=command('network','ls','--filter','name=^'+network+'$','--format','{{.ID}}').split()
    for identity in networks:
        row=json.loads(command('network','inspect',identity))[0]
        if row.get('Name')!=network or row.get('Driver')!='bridge' or not row.get('Internal') or row.get('Labels',{}).get('gameyfin.browser.owner')!=owner or row.get('Containers'):raise ValueError('Exact isolated smoke network')
        command('network','rm',row['Id'])
    if plan.get('id') is not None and plan['id'] in command('ps','-aq','--no-trunc').split():raise ValueError('Recorded ID still present')
    path.unlink()

def run(image,output):
    path=ledger_path();owner=secrets.token_hex(8);network='gameyfin-browser-net-'+owner
    if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Immutable image')
    plan={'owner':owner,'image':image,'id':None,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':['/opt/gameyfin-browser/chrome_smoke.mjs']}
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'w') as stream:json.dump(plan,stream)
    watchdog=None
    try:
        watchdog=subprocess.Popen([sys.executable,__file__,'--watchdog'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        command('network','create','--internal','--label','gameyfin.browser.owner='+owner,network)
        args=creation_args(owner,image,network,'172.22.0.2',1,'a'*64)
        args=args[:-4] # remove image and fixture-only three arguments
        args+=['--entrypoint','node',image,'/opt/gameyfin-browser/chrome_smoke.mjs']
        identity=command(*args).strip()
        if not re.fullmatch('[a-f0-9]{64}',identity):raise ValueError('Full created identity')
        plan['id']=identity;pending=path.with_suffix('.pending')
        fd=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
        with os.fdopen(fd,'w') as stream:json.dump(plan,stream)
        pending.replace(path)
        row=json.loads(command('inspect',identity))[0];validate(row,identity,owner,image)
        h=row['HostConfig']
        if h.get('ShmSize')!=128*1024**2 or h.get('Tmpfs')!={'/tmp':'rw,nosuid,nodev,size=128m,mode=1777'} or h.get('SecurityOpt') not in (['no-new-privileges'],['no-new-privileges=true'],['no-new-privileges:true']):raise ValueError('Matched smoke readonly/tmpfs/security boundary')
        if row['Config'].get('Entrypoint')!=plan['entrypoint'] or row['Config'].get('Cmd')!=plan['cmd']:raise ValueError('Known smoke command')
        net=json.loads(command('network','inspect',network))[0]
        if net.get('Name')!=network or net.get('Driver')!='bridge' or net.get('Labels',{}).get('gameyfin.browser.owner')!=owner or not net.get('Internal') or set(net.get('Containers',{})) not in (set(),{identity}):raise ValueError('No unexpected smoke peers')
        result=subprocess.run(['docker','start','--attach',identity],capture_output=True,text=True,timeout=25)
        if len(result.stdout.encode())>8192:raise ValueError('Smoke receipt bound')
        receipt=json.loads(result.stdout);validate_receipt(receipt)
        output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(receipt,indent=2))
        if result.returncode or not receipt['started'] or not receipt['sandboxVerified']:raise RuntimeError('Chrome smoke rejected')
    finally:
        cleanup(path)
        if watchdog:
            watchdog.terminate()
            try:watchdog.wait(timeout=2)
            except subprocess.TimeoutExpired:watchdog.kill();watchdog.wait(timeout=2)

def validate_receipt(row):
    required={'scope','started','sandboxVerified','exitCode','signal','signalScope','deadlineTriggered','exitObservedBeforeCleanup','failurePhase','fatalFlags','before','after'}
    if not isinstance(row,dict) or set(row) not in (required,required|{'failure'}):raise ValueError('Smoke schema')
    if row['scope']!='Chrome-only startup diagnostic; not app performance' or row['signalScope']!='Includes diagnostic shutdown; not automatic crash attribution' or any(type(row[k]) is not bool for k in ('started','sandboxVerified','deadlineTriggered','exitObservedBeforeCleanup')):raise ValueError('Smoke flags')
    if row['failurePhase'] not in (None,'launch','endpoint','connect','sandbox'):raise ValueError('Failure phase')
    if row['signal'] not in ('none','other','SIGABRT','SIGTRAP','SIGSYS','SIGSEGV','SIGKILL','SIGTERM'):raise ValueError('Signal enum')
    if row['exitCode'] is not None and (type(row['exitCode']) is not int or not 0<=row['exitCode']<=255):raise ValueError('Exit code')
    flags={'pthreadCreate','resourceUnavailable','checkFailed','seccomp','ptrace','credentialsSource','zygoteSource'}
    if not isinstance(row['fatalFlags'],dict) or set(row['fatalFlags'])!=flags or any(type(x) is not bool for x in row['fatalFlags'].values()):raise ValueError('Fatal hints')
    keys={'pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill'}
    for part in ('before','after'):
        data=row[part]
        if not isinstance(data,dict) or set(data)!=keys or any(x is not None and (type(x) is not int or not 0<=x<=2**53-1) for x in data.values()):raise ValueError('Counters')
    if 'failure' in row:
        from hosted_browser import validate_failure_receipt
        validate_failure_receipt({'browserFailed':True,'phase':'chrome-launch','launchFailureCategory':'other','launchDiagnostics':row['failure']})
    return row

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--image');parser.add_argument('--output',type=Path);parser.add_argument('--cleanup-only',action='store_true');parser.add_argument('--watchdog',action='store_true');args=parser.parse_args()
    if args.watchdog:time.sleep(30);cleanup(ledger_path())
    elif args.cleanup_only:cleanup(ledger_path())
    else:run(args.image,args.output)
