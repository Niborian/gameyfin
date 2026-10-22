"""Owned hosted-CI browser lifecycle. No server/SSH/local-Docker support.

The fixture runner supplies fresh inspected internal network/app identities and
already-built immutable browser image. Credentials are written only to attach
stdin. Caller is responsible for a <=15s held-provider callback budget.
"""
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import sys
import time
from owned_container_watchdog import cleanup
from cap_policy import caps_match
from owned_container_watchdog import validate as validate_browser_contract
from container_diagnostics import capture

BROWSER_BUDGET = {'cpuLimit': 1, 'memoryMiB': 768, 'scope': 'separate browser container; excluded from application resource savings'}
CREATION_PLAN = {'user':'10042:10042','capDrop':['ALL'],'capAdd':['SYS_ADMIN','SYS_CHROOT'],
    'cpuNano':1000000000,'memoryBytes':768*1024*1024,'pidsLimit':256,
    'readonlyRoot':True,'publishedPorts':False,'bindMounts':[],
    'tmpfs':{'/tmp':'rw,nosuid,nodev,size=128m,mode=1777'}}
NUMBERS = {'domContentLoadedMs','loadMs','routeContentReadyMs','coverDecodedMs','coverNaturalWidth','coverNaturalHeight','coverResponseStatus','encodedDataLength','resourceDurationMs'}
BOOLEANS = {'coverSha256Matches','fromDiskCache','fromServiceWorker'}
REQUIRED = {'domContentLoadedMs','loadMs','routeContentReadyMs','coverDecodedMs','coverNaturalWidth','coverNaturalHeight','coverResponseStatus','coverSha256Matches','fromDiskCache','fromServiceWorker'}


def validate_navigation_progress(value):
    if not isinstance(value,dict) or set(value)!={'navigationProgress'}:raise ValueError('Browser progress schema')
    row=value['navigationProgress']
    if not isinstance(row,dict) or not {'routeIndex','stage','readiness'}<=set(row) or not set(row)<={'routeIndex','stage','readiness','observation','proxyDiagnostics'}:raise ValueError('Browser progress schema')
    if 'proxyDiagnostics' in row:
        proxy=row['proxyDiagnostics'];keys={'requests','active','queued','peakActive','peakQueued','sockets','reservedSockets','peakSockets','outboundSockets','peakOutboundSockets','idleSocketsReclaimed','socketRefusals','deniedCapacity','deniedRequestBudget','deniedPolicy','deniedDeadline','deniedClosed'}
        if not isinstance(proxy,dict) or set(proxy)!=keys or any(type(proxy[k]) is not int or not 0<=proxy[k]<=(8 if k in ('active','queued','peakActive','peakQueued','reservedSockets','outboundSockets','peakOutboundSockets') else 32 if k in ('sockets','peakSockets') else 256) for k in keys) or proxy['sockets']+proxy['reservedSockets']>32 or proxy['outboundSockets']+proxy['reservedSockets']>8:raise ValueError('Browser proxy diagnostic schema')
    if type(row['routeIndex']) is not int or not 1<=row['routeIndex']<=3 or row['stage'] not in ('navigate','content','cover-body','complete'):raise ValueError('Browser progress value')
    readiness=row['readiness']
    keys={'pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch'}
    if readiness is not None and (not isinstance(readiness,dict) or set(readiness)!=keys or any(type(v) is not bool for v in readiness.values())):raise ValueError('Browser readiness schema')
    if 'observation' in row:
        observation=row['observation'];flags={'domRootPresent','navPresent','signInPresent','staticErrorViewPresent','uncaughtExceptionSeen','truncated'};rpcs={'library','game','profile'}
        if not isinstance(observation,dict) or set(observation)!=flags|rpcs or any(type(observation[k]) is not bool for k in flags):raise ValueError('Browser navigation observation schema')
        for kind in rpcs:
            rpc=observation[kind]
            if not isinstance(rpc,dict) or set(rpc) not in ({'status','completion'},{'status','completion','httpStatus','proxyDeny'}) or rpc['status'] not in ('not-observed','1xx','2xx','3xx','4xx','5xx','invalid') or rpc['completion'] not in ('not-observed','pending','finished','failed'):raise ValueError('Browser navigation RPC enum')
            if 'httpStatus' in rpc and ((rpc['httpStatus'] is not None and (type(rpc['httpStatus']) is not int or not 100<=rpc['httpStatus']<=599)) or rpc['proxyDeny'] not in ('not-observed','none','capacity','request-budget','policy','deadline','closed','invalid')):raise ValueError('Browser navigation RPC status boundary')
    return value

def validate_auth_ready(value):
    keys={'authenticated','sandboxVerified','normalLoginAutoRedirectObserved','fixtureProfileVerified'}
    if not isinstance(value,dict) or set(value)!=keys or any(value[k] is not True for k in ('authenticated','sandboxVerified','fixtureProfileVerified')) or type(value['normalLoginAutoRedirectObserved']) is not bool:raise ValueError('Normal UI auth/sandbox/profile gate')
    return value

def validate_sample(receipt):
    if not isinstance(receipt,dict) or set(receipt)!={'originalUiContentReady','coverDecoded','coverSha256Matches','navigations','browserResources'} or any(receipt[k] is not True for k in ('originalUiContentReady','coverDecoded','coverSha256Matches')):
        raise ValueError('Normal UI sample gate')
    resources=receipt['browserResources']
    if not isinstance(resources,dict) or set(resources)!={'before','after'}: raise ValueError('Browser resource schema')
    for snapshot in resources.values():
        if not isinstance(snapshot,dict) or set(snapshot)!={'cpuUsageUsec','memoryCurrentBytes','memoryPeakBytes'} or any(type(v) is not int or not 0<=v<=2**53-1 for v in snapshot.values()):
            raise ValueError('Browser resource boundary')
    if resources['after']['cpuUsageUsec']<resources['before']['cpuUsageUsec']: raise ValueError('Browser CPU counter regression')
    rows=receipt['navigations']
    if not isinstance(rows,list) or len(rows)!=3:
        raise ValueError('Navigation cardinality')
    for row in rows:
        if not isinstance(row,dict) or not REQUIRED.issubset(row) or set(row)-NUMBERS-BOOLEANS:
            raise ValueError('Navigation schema boundary')
        for key,value in row.items():
            if key in NUMBERS and (type(value) not in (int,float) or not 0<=value<=1e9):
                raise ValueError('Numeric metric boundary')
            if key in BOOLEANS and type(value) is not bool:
                raise ValueError('Boolean metric boundary')
        if row['coverSha256Matches'] is not True or row['coverNaturalWidth']!=320 or row['coverNaturalHeight']!=480 or row['coverResponseStatus'] not in (200,304):
            raise ValueError('Cover evidence boundary')
    return receipt


def reap_owned_process(process):
    if process is None: return
    try:
        process.communicate(timeout=2)
    except subprocess.TimeoutExpired:
        process.terminate()
        try: process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill();process.communicate(timeout=2)


def command(*argv):
    return subprocess.run(['docker',*argv],check=True,capture_output=True,text=True,timeout=10).stdout


def validate_owner_root(root,owner):
    root=Path(root)
    if not re.fullmatch('[a-f0-9]{16}',owner):
        raise ValueError('Fixture identity boundary')
    if not root.is_absolute() or root.name!='gameyfin-browser-fixture-'+owner or not root.is_dir() or any(p.is_symlink() for p in [root,*root.parents]) or root.resolve()!=root:
        raise ValueError('Fresh synthetic fixture root boundary')
    marker=root/'OWNER';before=marker.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_size>64 or before.st_mode&0o022:
        raise ValueError('Owner marker regular-file/mode boundary')
    descriptor=os.open(marker,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0))
    try:
        opened=os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (opened.st_dev,opened.st_ino,opened.st_size)!=(before.st_dev,before.st_ino,before.st_size):
            raise ValueError('Owner marker changed before open')
        marker_bytes=os.read(descriptor,65)
        after=os.fstat(descriptor)
        if (opened.st_size,opened.st_mtime_ns)!=(after.st_size,after.st_mtime_ns) or marker.lstat().st_ino!=opened.st_ino:
            raise ValueError('Owner marker changed during read')
    finally:os.close(descriptor)
    if marker_bytes!=('benchmark-27-'+owner).encode():
        raise ValueError('Synthetic fixture marker mismatch')
    return root


def validate_app_contract(row,root,owner,image,container_id,network,*,waiting=False):
    root=validate_owner_root(root,owner)
    if not re.fullmatch('[a-f0-9]{64}',container_id) or not re.fullmatch('sha256:[a-f0-9]{64}',image):
        raise ValueError('Fixture identity boundary')
    if network!='gameyfin-browser-net-'+owner or row.get('Id')!=container_id or row.get('Name')!='/gameyfin-benchmark-app-'+owner or row.get('Image')!=image or row.get('Config',{}).get('Labels',{}).get('gameyfin.fixture.owner')!='benchmark-27-'+owner:
        raise ValueError('Actual application identity mismatch')
    if row.get('State',{}).get('Running') is not True or row.get('State',{}).get('OOMKilled') or row.get('RestartCount')!=0:
        raise ValueError('Application lifecycle boundary')
    host=row.get('HostConfig',{})
    if host.get('NetworkMode')!=network or host.get('Privileged') or host.get('PidMode','')!='' or host.get('IpcMode','') not in ('','private') or host.get('UTSMode','')!='' or host.get('CgroupnsMode')!='private' or host.get('PortBindings') or host.get('PublishAllPorts'):
        raise ValueError('Application network/namespace boundary')
    if host.get('NanoCpus')!=500000000 or host.get('Memory')!=1024*1024*1024 or host.get('MemorySwap')!=1024*1024*1024 or host.get('PidsLimit')!=256 or not caps_match(host.get('CapDrop'),['ALL']) or not caps_match(host.get('CapAdd'),[]):
        raise ValueError('Application resource/capability boundary')
    if set(row.get('NetworkSettings',{}).get('Networks',{}))!={network} or row.get('Config',{}).get('User')!='1337:1337':
        raise ValueError('Application exclusive network/nonroot boundary')
    expected={f'/opt/gameyfin/{leaf}':(root/leaf,True) for leaf in ('db','data','logs','plugins','plugindata')}
    expected.update({'/fixture':(root/'fixture/sources',False),'/fixture-images':(root/'fixture/images',False)})
    if waiting:expected['/waiting-fixture']=(root/'waiting',True)
    mounts=row.get('Mounts',[])
    if len(mounts)!=len(expected):raise ValueError('Application mount cardinality')
    observed=set()
    for mount in mounts:
        destination=mount.get('Destination')
        if destination not in expected or destination in observed or mount.get('Type')!='bind':raise ValueError('Application mount boundary')
        source,rw=expected[destination];observed.add(destination)
        if mount.get('Source')!=str(source) or mount.get('RW') is not rw or not source.is_dir() or any(p.is_symlink() for p in [source,*source.parents]):
            raise ValueError('Application exact owned mount mismatch')
    return row


def record_browser(root,owner,container_id,image):
    root=validate_owner_root(root,owner)
    if not re.fullmatch('[a-f0-9]{64}',container_id) or not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Browser ledger identity')
    pending=root/'browser-ledger.pending'
    with pending.open('x',encoding='ascii') as output:
        output.write(json.dumps({'owner':owner,'id':container_id,'image':image}))
    pending.chmod(0o600);pending.replace(root/'browser-ledger.json')


def record_creation_intent(root,owner,image):
    root=validate_owner_root(root,owner)
    if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Browser intent image identity')
    cleanup_known_browser(root,owner)
    identity=root.stat(follow_symlinks=False)
    value={'owner':owner,'image':image,'name':'gameyfin-browser-'+owner,
        'network':'gameyfin-browser-net-'+owner,'device':identity.st_dev,'inode':identity.st_ino,'plan':CREATION_PLAN}
    pending=root/'browser-intent.pending'
    with pending.open('x',encoding='ascii') as output:output.write(json.dumps(value))
    pending.chmod(0o600);pending.replace(root/'browser-intent.json')


def read_state(path,bound):
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size>bound or info.st_mode&0o022:raise ValueError('Browser state file boundary')
    fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0))
    try:
        opened=os.fstat(fd)
        if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError('Browser state changed')
        return json.loads(os.read(fd,bound+1))
    finally:os.close(fd)


def cleanup_known_browser(root,owner):
    root=validate_owner_root(root,owner);ledger=root/'browser-ledger.json';intent_path=root/'browser-intent.json'
    intent=None
    if intent_path.exists() or intent_path.is_symlink():
        intent=read_state(intent_path,1024);identity=root.stat(follow_symlinks=False)
        if not isinstance(intent,dict) or set(intent)!={'owner','image','name','network','device','inode','plan'} or intent['plan']!=CREATION_PLAN or intent['owner']!=owner or intent['name']!='gameyfin-browser-'+owner or intent['network']!='gameyfin-browser-net-'+owner or (intent['device'],intent['inode'])!=(identity.st_dev,identity.st_ino) or not re.fullmatch('sha256:[a-f0-9]{64}',intent['image']):
            raise ValueError('Exact browser creation intent boundary')
    if not ledger.exists():
        if ledger.is_symlink():raise ValueError('Browser ledger symlink')
        if intent:
            # Only this exact durable name can recover the create→returned-ID
            # cancellation window. No guessed names, label-only or prefix match.
            rows=command('ps','-a','--no-trunc','--format','{{.ID}} {{.Names}}').splitlines()
            matches=[]
            for line in rows:
                parts=line.split()
                if len(parts)==2 and parts[1]==intent['name']:
                    if not re.fullmatch('[a-f0-9]{64}',parts[0]):raise ValueError('Recovered exact ID malformed')
                    matches.append(parts[0])
            if len(matches)>1:raise ValueError('Recovered intent name ambiguous')
            if matches:
                cleanup(matches[0],owner,intent['image'],command)
                if matches[0] in command('ps','-aq','--no-trunc').split():raise ValueError('Recovered browser still exists')
            intent_path.unlink()
        return
    row=read_state(ledger,512)
    if set(row)!={'owner','id','image'} or row['owner']!=owner or not re.fullmatch('[a-f0-9]{64}',row['id']) or not re.fullmatch('sha256:[a-f0-9]{64}',row['image']):raise ValueError('Browser ledger identity boundary')
    if intent and row['image']!=intent['image']:raise ValueError('Browser ledger differs from creation intent')
    ids=command('ps','-aq','--no-trunc').split()
    if row['id'] in ids:cleanup(row['id'],owner,row['image'],command)
    if row['id'] in command('ps','-aq','--no-trunc').split():raise ValueError('Browser still exists after cleanup')
    ledger.unlink()
    if intent:intent_path.unlink()


def creation_args(owner,image,network,app_ip,cover_id,cover_sha):
    if not re.fullmatch('[a-f0-9]{16}',owner) or not re.fullmatch('sha256:[a-f0-9]{64}',image):
        raise ValueError('Owned identity boundary')
    if not re.fullmatch('gameyfin-browser-net-[a-f0-9]{16}',network) or not re.fullmatch(r'172\.(1[6-9]|2[0-9]|3[01])\.\d{1,3}\.\d{1,3}',app_ip) or any(int(x)>255 for x in app_ip.split('.')):
        raise ValueError('Private fixture boundary')
    if not isinstance(cover_id,int) or cover_id<1 or not re.fullmatch('[a-f0-9]{64}',cover_sha):
        raise ValueError('Cover boundary')
    return ['create','--name','gameyfin-browser-'+owner,'--label','gameyfin.browser.owner='+owner,
        '--network',network,'--cgroupns','private','--read-only','--init','--user','10042:10042',
        '--cap-drop','ALL','--cap-add','SYS_ADMIN','--cap-add','SYS_CHROOT', # Chrome namespace/chroot sandbox only; ephemeral hosted CI.
        '--security-opt','no-new-privileges','--cpus','1','--memory','768m','--memory-swap','768m',
        '--pids-limit','256','--shm-size','128m','--tmpfs','/tmp:rw,nosuid,nodev,size=128m,mode=1777',
        '--env','GAMEYFIN_HOSTED_BROWSER=reviewed-fixture-only','--interactive',
        image,app_ip,str(cover_id),cover_sha]


def validate_eligibility_diagnostic(e):
    enums={'resourceType':('Document','Image','Script','Stylesheet','Font','Fetch','XHR','Other','unknown'),'method':('GET','POST','HEAD','other','unknown'),'target':('exact-cover','same-origin-other','foreign','invalid'),'reason':('eligible','policy-denied','duplicate','not-image','not-get','not-exact-cover','network-missing','metadata-missing','frame-mismatch','loader-mismatch','generation-mismatch','hop-replaced','ambiguous','typed-binding-missing','cancel-not-before','pause-ineligible','truncated')};flags={'permitted','pauseEligible','metadataPresent','duplicate','truncated','typedBinding'};nullable={'frameMatches','loaderMatches','generationMatches','currentHop','reused','ambiguous','canceledBeforeFailure'}
    if not isinstance(e,dict) or set(e)!=set(enums)|flags|nullable or any(e[k] not in values for k,values in enums.items()) or any(type(e[k]) is not bool for k in flags) or any(e[k] is not None and type(e[k]) is not bool for k in nullable):raise ValueError('Auth eligibility diagnostic boundary')
    return e

def validate_failure_receipt(value):
    base={'browserFailed','phase'}
    categories={'crashpad-database','readonly-filesystem','sandbox-unavailable','namespace-permission','missing-shared-library','missing-executable','launch-timeout','other'}
    if (not isinstance(value,dict) or value.get('browserFailed') is not True or not base.issubset(value) or set(value)-base-{'launchFailureCategory','launchDiagnostics','authStage','failureCounters','authDiagnostics'} or
        value.get('phase') not in ('arguments','input','bridge','profile','chrome-launch','sandbox','authentication','navigation','logout','cleanup')):
        raise ValueError('Browser failure receipt schema')
    if 'authStage' in value and (value['phase']!='authentication' or value['authStage'] not in ('interception','login-navigation','login-form','submit','redirect','profile-proof','complete')):raise ValueError('Auth stage boundary')
    if 'authDiagnostics' in value:
        data=value['authDiagnostics']
        keys={'loginPostObserved','loginPostStatus','submittedUsernameLengthMatches','submittedPasswordLengthMatches','readiness','cdpFailure','cdpMethod','loginResultHeader','userInfoStatus','profile','userInfoIdentity','cookieDiagnostics'}
        if value['phase']!='authentication' or not isinstance(data,dict) or set(data) not in (keys,keys|{'interceptionDiagnostics'}) or type(data['loginPostObserved']) is not bool:raise ValueError('Auth diagnostics schema')
        if 'interceptionDiagnostics' in data:
            diagnostic=data['interceptionDiagnostics'];counters={'pausedEvents','duplicatePausedIds','canceledNetworkEvents','verifiedCanceledImageContinuations'}
            if not isinstance(diagnostic,dict) or set(diagnostic)!=counters|{'failedOperation','failedRequestCanceled','truncated'}|({'eligibility'} if 'eligibility' in diagnostic else set()) or diagnostic['failedOperation'] not in ('none','continue','deny') or (diagnostic['failedRequestCanceled'] is not None and type(diagnostic['failedRequestCanceled']) is not bool) or type(diagnostic['truncated']) is not bool or any(type(diagnostic[k]) is not int or not 0<=diagnostic[k]<=256 for k in counters) or diagnostic['verifiedCanceledImageContinuations']>diagnostic['pausedEvents']:raise ValueError('Auth interception diagnostic boundary')
            if 'eligibility' in diagnostic:
                validate_eligibility_diagnostic(diagnostic['eligibility'])
        if data['loginPostStatus'] is not None and (type(data['loginPostStatus']) is not int or not 100<=data['loginPostStatus']<=599):raise ValueError('Auth diagnostic numeric boundary')
        if data['userInfoStatus'] is not None and (type(data['userInfoStatus']) is not int or not 100<=data['userInfoStatus']<=599):raise ValueError('Auth user-info status boundary')
        if data['loginResultHeader'] not in ('success','failure','missing','other'):raise ValueError('Auth result enum')
        if data['userInfoIdentity'] not in ('not-observed','null','fixture-user','other','invalid','failed'):raise ValueError('Auth user-info identity enum')
        cookies=data['cookieDiagnostics'];cookie_keys={'loginSetCookieSeen','loginSessionCookieSetSeen','loginSessionCookieBlocked','profileCookieHeaderSeen','profileSessionCookieAssociated','profileSessionCookieBlocked','loginSubmittedWithPendingRpc','otherSessionCookieSetAfterLogin'}
        if not isinstance(cookies,dict) or set(cookies)!=cookie_keys or any(v is not None and type(v) is not bool for v in cookies.values()):raise ValueError('Auth cookie diagnostic boundary')
        if any(data[k] is not None and type(data[k]) is not bool for k in ('submittedUsernameLengthMatches','submittedPasswordLengthMatches')):raise ValueError('Auth diagnostic match boundary')
        if data['cdpFailure'] not in ('none','execution-context-destroyed','execution-context-missing','target-closed','protocol-timeout','interception-invalid-id','other'):raise ValueError('Auth CDP failure enum')
        if data['cdpMethod'] not in ('none','page-navigation','runtime-evaluate','input-insert','request-interception','setup','other'):raise ValueError('Auth CDP method enum')
        if data['cdpFailure']=='interception-invalid-id' and data['cdpMethod']!='request-interception':raise ValueError('Auth CDP failure method mismatch')
        profile=data['profile'];stages=('not-started','navigate','profile-dom','open-menu','identity-menu','close-menu','complete')
        if not isinstance(profile,dict) or set(profile)!={'stage','readiness'} or profile['stage'] not in stages:raise ValueError('Profile diagnostic schema')
        gates=profile['readiness'];profile_flags={'originMatches','profilePath','loginPath','myProfileHeading','usernamePresent','emailPresent','newPasswordPresent','passwordRepeatPresent','baseRootMatches','identityRow','adminItem','signOutItem','navPresent','navbarAvatarPresent','navbarSignInPresent','navTriggerPresent','navTriggerVisible','menuAriaPresent','menuAriaVisible','menuAriaOutsideNav','profileTriggerUnique'}
        if gates is not None and (not isinstance(gates,dict) or set(gates)!=profile_flags|{'triggerCount'} or any(type(gates[k]) is not bool for k in profile_flags) or gates['triggerCount'] not in ('none','one','multiple')):raise ValueError('Profile readiness schema')
        readiness=data['readiness'];flags={'originMatches','loginPath','usernamePresent','passwordPresent','submitPresent','submitDisabled','invalidCredentialsDisplayed'}
        if readiness is not None and (not isinstance(readiness,dict) or set(readiness)!=flags|{'documentReady'} or any(type(readiness[k]) is not bool for k in flags) or readiness['documentReady'] not in ('loading','interactive','complete')):raise ValueError('Auth readiness boundary')
    if 'failureCounters' in value:
        data=value['failureCounters'];keys={'pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill'}
        if not isinstance(data,dict) or set(data)!=keys or any(x is not None and (type(x) is not int or not 0<=x<=2**53-1) for x in data.values()):raise ValueError('Failure cgroup numeric boundary')
    if 'launchFailureCategory' in value:
        category=value['launchFailureCategory']
        if value['phase']!='chrome-launch' or not isinstance(category,str) or category not in categories:
            raise ValueError('Browser failure receipt schema')
    if 'launchDiagnostics' in value:
        data=value['launchDiagnostics']
        booleans={'messageTruncated','launchCallable','processExitHeader','launchNotFunction','setuidNotRoot','crashpadPermission','devtoolsEndpoint','protocolTimeout','configuredPathMissing','cacheConfiguration'}
        nullable={'executableRegular','executableAccessible'}
        if (value['phase']!='chrome-launch' or not isinstance(data,dict) or set(data)!=booleans|nullable|{'errorName','errno','exitCode','preflightPhase','preflightErrno'} or
            any(type(data[k]) is not bool for k in booleans) or any(data[k] is not None and type(data[k]) is not bool for k in nullable) or
            data['errorName'] not in ('Error','TimeoutError','TypeError','SyntaxError','other') or data['errno'] not in ('ENOENT','EACCES','EPERM','other') or
            data['preflightPhase'] not in ('resolve','invalid-result','stat','access','complete') or data['preflightErrno'] not in ('ENOENT','EACCES','EPERM','other') or
            (data['exitCode'] is not None and (type(data['exitCode']) is not int or not 0<=data['exitCode']<=255))):
            raise ValueError('Bounded launch diagnostics schema')
    return value

class HostedBrowser:
    def __init__(self,owner,image,network,app_ip,cover_id,cover_sha,*,app_id,app_image,fixture_root,waiting=False,diagnostic_output=None):
        if os.name!='posix' or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':
            raise ValueError('Hosted Linux CI only')
        row=json.loads(command('network','inspect',network))[0]
        if row.get('Name')!=network or row.get('Internal') is not True or len(row.get('Containers',{}))!=1:
            raise ValueError('Exclusive internal fixture network required')
        target=next(iter(row['Containers'].values()))
        if set(row['Containers'])!={app_id} or target.get('IPv4Address','').split('/')[0]!=app_ip:
            raise ValueError('Actual private fixture IP mismatch')
        app_rows=json.loads(command('inspect',app_id))
        if len(app_rows)!=1:raise ValueError('Application inspection cardinality')
        validate_app_contract(app_rows[0],fixture_root,owner,app_image,app_id,network,waiting=waiting)
        image_rows=json.loads(command('image','inspect',image))
        if len(image_rows)!=1 or image_rows[0].get('Id')!=image:raise ValueError('Actual browser image mismatch')
        self.owner=owner;self.image=image;self.id=None;self.process=None;self.watchdog=None;self.diagnostic_output=diagnostic_output
        try:
            record_creation_intent(fixture_root,owner,image)
            self.id=command(*creation_args(owner,image,network,app_ip,cover_id,cover_sha)).strip()
            if not re.fullmatch('[a-f0-9]{64}',self.id): raise ValueError('Created ID boundary')
            record_browser(fixture_root,owner,self.id,image)
            created=json.loads(command('inspect',self.id))
            if len(created)!=1:raise ValueError('Browser inspection cardinality')
            if diagnostic_output:capture(diagnostic_output,created[0],owner,'browser',image)
            validate_browser_contract(created[0],self.id,owner,image)
            current=json.loads(command('network','inspect',network))
            if len(current)!=1 or current[0].get('Name')!=network or current[0].get('Internal') is not True:
                raise ValueError('Private network changed before browser start')
            members=current[0].get('Containers',{})
            if set(members) not in ({app_id},{app_id,self.id}) or members[app_id].get('IPv4Address','').split('/')[0]!=app_ip:
                raise ValueError('Exclusive fixture topology changed before start')
            # Independent fence exists BEFORE start, unaffected by controller death.
            self.watchdog=subprocess.Popen([sys.executable,str(Path(__file__).with_name('owned_container_watchdog.py')),
                '--container-id',self.id,'--owner',owner,'--image-id',image],stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            self.process=subprocess.Popen(['docker','start','--attach','--interactive',self.id],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0)
        except Exception:
            self.close();raise

    def _read_receipt(self, timeout):
        # Binary unbuffered stdout makes select truthful; no raw failures echoed.
        end=time.monotonic()+timeout
        raw=bytearray()
        progress_count=0
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout,selectors.EVENT_READ)
            while time.monotonic()<end:
                if not selector.select(max(0,end-time.monotonic())): break
                chunk=os.read(self.process.stdout.fileno(),1)
                if not chunk: raise ValueError('Browser receipt unavailable')
                if chunk==b'\n':
                    value=json.loads(raw)
                    if not isinstance(value,dict): raise ValueError('Browser receipt schema')
                    if 'navigationProgress' in value:
                        validate_navigation_progress(value)
                        progress_count+=1
                        if progress_count>64:raise ValueError('Browser progress count bound')
                        self.record_navigation_progress(value['navigationProgress'])
                        raw.clear()
                        continue
                    if value.get('browserFailed') is True:
                        validate_failure_receipt(value)
                        self.record_driver_failure(value)
                        raise ValueError('Private browser phase failed; sanitized receipt retained')
                    return value
                raw.extend(chunk)
                if len(raw)>8192: raise ValueError('Browser receipt bound')
        raise TimeoutError('Browser receipt deadline')

    def record_navigation_progress(self,value):
        if not getattr(self,'diagnostic_output',None):return
        path=Path(self.diagnostic_output);data=read_state(path,8192)
        data['lastNavigationProgress']=value
        self.write_diagnostic(path,data,'.progress.pending')

    def record_driver_failure(self,value):
        if not self.diagnostic_output:return
        path=Path(self.diagnostic_output);observed=read_state(path,8192)
        observed['browserDriverFailure']=value
        pending=path.with_suffix('.driver-failure.pending')
        with pending.open('x') as output:output.write(json.dumps(observed,indent=2))
        pending.chmod(0o600);pending.replace(path)

    def authenticate(self,username,password):
        if not isinstance(username,str) or not isinstance(password,str) or not 1<=len(username)<=128 or not 16<=len(password)<=256 or re.search(r'[\x00-\x1f\x7f]',username+password):
            raise ValueError('Private input boundary')
        self.process.stdin.write((username+'\n'+password+'\n').encode());self.process.stdin.flush()
        ready=self._read_receipt(30)
        validate_auth_ready(ready)
        self.authenticationEvidence={k:ready[k] for k in ('normalLoginAutoRedirectObserved','fixtureProfileVerified')}
        if getattr(self,'diagnostic_output',None):
            path=Path(self.diagnostic_output);data=read_state(path,8192);data['authenticationEvidence']=self.authenticationEvidence
            self.write_diagnostic(path,data,'.auth-ready.pending')
        self.authenticated=True

    def sample(self):
        deadline=time.monotonic()+15
        self.process.stdin.write(b'navigate\n');self.process.stdin.flush()
        receipt=validate_sample(self._read_receipt(max(0,deadline-time.monotonic())))
        self.record_sample_before_logout(receipt)
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('Browser operation budget exhausted before logout')
        self.process.stdin.write(b'logout\n');self.process.stdin.flush()
        logged_out=self._read_receipt(remaining)
        if logged_out!={'loggedOut':True} or logged_out.get('loggedOut') is not True:raise ValueError('Normal UI logout receipt')
        self.authenticated=False
        if getattr(self,'diagnostic_output',None):
            path=Path(self.diagnostic_output);data=read_state(path,8192);data['normalUiLogoutPassed']=True
            self.write_diagnostic(path,data,'.logout.pending')
        return receipt

    def write_diagnostic(self,path,data,suffix):
        encoded=json.dumps(data,indent=2)
        if len(encoded.encode())>8192:raise ValueError('Browser diagnostic byte bound')
        pending=path.with_suffix(suffix)
        fd=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
        with os.fdopen(fd,'w') as stream:stream.write(encoded)
        pending.replace(path)

    def record_sample_before_logout(self,receipt):
        if not getattr(self,'diagnostic_output',None):return
        path=Path(self.diagnostic_output);data=read_state(path,8192)
        data['navigationEvidenceBeforeLogout']=validate_sample(receipt);data['normalUiLogoutPassed']=False
        self.write_diagnostic(path,data,'.sample.pending')

    def close(self):
        # Keep independent fence alive if removal fails, but always reap our
        # exact attach child. No process-name, PID-list or global Docker cleanup.
        removed=self.id is None
        try:
            if self.id:
                cleanup(self.id,self.owner,self.image,command)
                self.id=None;removed=True
        finally:
            try: reap_owned_process(self.process)
            finally:
                if removed and self.watchdog:
                    self.watchdog.terminate()
                    reap_owned_process(self.watchdog)

    def __enter__(self): return self
    def __exit__(self,*_): self.close()
