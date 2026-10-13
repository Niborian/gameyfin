"""Unblocked actual-app successful scans + original-UI + natural recovery.

The outer CI runner supplies exact-owned resource sampling and prepared browser
objects (authentication outside each measured scan phase). No Docker/process
creation here; no rendezvous, artificial delay, forced GC or scheduler tuning.
"""
import math,re,time,threading

REQUIRED={'gameyfin_scans_active','gameyfin_scans_draining','gameyfin_scans_completed_total',
    'gameyfin_scans_failed_total','jvm_memory_used_bytes','jvm_gc_pause_seconds_count','jvm_gc_pause_seconds_sum'}

def parse_metrics(data):
    if len(data)>1024*1024:raise ValueError('Telemetry byte bound')
    values={}
    for line in data.decode('ascii').splitlines():
        if not line or line.startswith('#'):continue
        if line.split('{',1)[0].split(' ',1)[0] not in REQUIRED:continue
        match=re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^\r\n]{0,1024}\})? ([0-9.eE+-]+)',line)
        if not match:raise ValueError('Telemetry format')
        number=float(match[3])
        if not math.isfinite(number) or number<0:raise ValueError('Telemetry counter invalid')
        values.setdefault(match[1],[]).append((match[2] or '',number))
    def singleton(name,labels=None):
        rows=[v for tags,v in values.get(name,[]) if labels is None or labels in tags]
        if len(rows)!=1:raise ValueError('Required scan telemetry missing or ambiguous')
        return rows[0]
    active=singleton('gameyfin_scans_active');draining=singleton('gameyfin_scans_draining')
    if active>4 or draining>4:raise ValueError('Fixture scan gauge range')
    heap=[v for tags,v in values.get('jvm_memory_used_bytes',[]) if 'area="heap"' in tags]
    counts=values.get('jvm_gc_pause_seconds_count',[]);pauses=values.get('jvm_gc_pause_seconds_sum',[])
    if not heap or not counts or not pauses:raise ValueError('Required JVM memory/GC telemetry missing')
    return {'active':active,'draining':draining,
        'completed':singleton('gameyfin_scans_completed_total','type="full"'),
        'failed':singleton('gameyfin_scans_failed_total','type="full"'),
        'heapBytes':sum(heap),'gcPauseCount':sum(v for _,v in counts),'gcPauseSeconds':sum(v for _,v in pauses)}

def metrics(client):
    status,_,body=client.raw('/actuator/prometheus',management=True)
    if status!=200:raise ValueError('Private telemetry status')
    return parse_metrics(body)

def activity(client):
    status,_,body=client.raw('/actuator/prometheus?includedNames=gameyfin_scans_active',management=True)
    lines=[line for line in body.decode('ascii').splitlines() if line and not line.startswith('#')]
    if status!=200 or len(body)>8192 or len(lines)!=1:raise ValueError('Bounded filtered active gauge required')
    match=re.fullmatch(r'gameyfin_scans_active ([0-4](?:\.0+)?)',lines[0])
    if not match:raise ValueError('Filtered active gauge shape')
    return {'active':float(match[1])}

def delta(before,after,key):
    value=after[key]-before[key]
    if not math.isfinite(value) or value<0:raise ValueError('Counter reset or invalid delta')
    return value

def browser_sample(browser):
    try:
        result=browser.sample()
        if any(result.get(k) is not True for k in ('originalUiContentReady','coverDecoded','coverSha256Matches')):
            raise ValueError('Original UI/cover functional evidence absent')
        return {'status':'success','evidence':result}
    except TimeoutError:
        return {'status':'timeout','censoredAtLeastSeconds':15,'acceptance':'not_completed'}

class ResourceSampler:
    """One owned bounded non-daemon sampler; no JVM/HTTP work or forced GC."""
    def __init__(self,snapshot,clock):
        self.snapshot=snapshot;self.clock=clock;self.stop=threading.Event();self.samples=[];self.failure=None
        self.thread=threading.Thread(target=self.run,name='owned-app-cgroup-sampler',daemon=False)
    def run(self):
        end=self.clock()+240
        while not self.stop.is_set() and self.clock()<end and len(self.samples)<240:
            try:self.samples.append({'monotonicSeconds':self.clock(),**self.snapshot()})
            except Exception as error:self.failure=type(error).__name__;return
            if self.stop.wait(1):return
    def __enter__(self):
        self.samples.append({'monotonicSeconds':self.clock(),**self.snapshot()})
        self.thread.start();return self
    def __exit__(self,*_):
        self.stop.set();self.thread.join(35)
        if self.thread.is_alive():raise RuntimeError('Owned cgroup sampler did not stop within subprocess deadline')

def run_case(client,fixture,cover_path,prepare_browser,resource_snapshot,verify_sources,*,clock=time.monotonic,sleep=time.sleep,persist_partial=lambda report:None):
    report={'scope':'Unblocked actual-app synthetic successful metadata with original UI; no real provider HTTP or production acceptance',
        'phaseResults':[],'complete':False,'browserNavigationBudget':15,'browserOperationIncludesNormalUiLogout':True,'forcedGc':False,'schedulerChanged':False,
        'measurementScope':'Scan wall/CPU start after before-browser authentication/navigation and before counters; during browser HTTP adds app CPU. Browser container CPU excluded from app cgroup.',
        'overlapScope':'Fresh same-request gauge bracket observes activity around the bounded navigation+normal-UI-logout operation at two samples, NOT continuous scan execution. Navigation latency/resource receipt ends before logout. Missing/late gauge is unknown.'}
    persist_partial(report)
    def snapshot():
        return {**resource_snapshot(),**metrics(client)}
    start=snapshot()
    if start['active'] or start['draining']:raise ValueError('Initial fixture not quiescent')
    # Natural matched startup settling; no application requests beyond telemetry.
    cold_start=clock();cold=[]
    while True:
        s=snapshot();s['elapsedSeconds']=clock()-cold_start;cold.append(s)
        if s['active'] or s['draining']:raise ValueError('Cold idle not quiescent')
        if s['elapsedSeconds']>=60:break
        sleep(min(15,60-s['elapsedSeconds']))
    report['coldIdle']=cold
    persist_partial(report)
    all_window_before=resource_snapshot()
    for phase in ('cold','repeat'):
        with prepare_browser(phase+'-before') as before_browser:
            before_ui=browser_sample(before_browser)
            report['pendingPhase']={'phase':phase,'beforeBrowser':before_ui,'beforeBrowserCleanupPassed':False}
            persist_partial(report)
            before_browser.close()
            report['pendingPhase']['beforeBrowserCleanupPassed']=True
            persist_partial(report)
        with prepare_browser(phase+'-during') as during_browser:
            before=snapshot();phase_started=clock()
            with ResourceSampler(resource_snapshot,clock) as sampler:
                client.scan()
                # Up to10 bounded checks for ACTUAL activity; dispatch is not evidence.
                active_wait_until=clock()+10;active_attempts=0
                while True:
                    left_requested=clock();active_attempts+=1
                    try:left=activity(client);left_completed=clock()
                    except (OSError,TimeoutError):left=None;left_completed=clock()
                    if left and left['active']>0 or clock()>=active_wait_until or active_attempts>=10:break
                    probe=metrics(client)
                    if probe['completed']-before['completed']==4:break
                    sleep(.25)
                browser_start=clock();during_ui=browser_sample(during_browser);browser_end=clock()
                right_requested=clock()
                try:right=activity(client);right_completed=clock()
                except (OSError,TimeoutError):right=None;right_completed=clock()
                report['pendingPhase']['duringBrowser']=during_ui
                report['pendingPhase']['duringBrowserCleanupPassed']=False
                report['pendingPhase']['resourceAfterBrowser']=resource_snapshot()
                persist_partial(report)
                # Logout/removal occur promptly, never waiting through scan
                # completion on the browser's unchanged 15s command deadline.
                during_browser.close()
                report['pendingPhase']['duringBrowserCleanupPassed']=True
                persist_partial(report)
                overlap='unknown'
                if left and left['active']>0 and browser_start-left_requested<=5:
                    overlap='started-active'
                    if right and right['active']>0 and right_completed-left_requested<=5:
                        overlap='bracketed-active'
                polls=[];deadline=phase_started+180
                while True:
                    current=snapshot();current['elapsedSeconds']=clock()-phase_started;polls.append(current)
                    if current['failed']!=before['failed']:raise ValueError('Full scan failure')
                    if current['completed']-before['completed']==4 and current['active']==0 and current['draining']==0:break
                    if clock()>=deadline:raise TimeoutError('Successful scan/quiescence deadline')
                    sleep(1)
                quiescent=resource_snapshot();quiescent_at=clock();quiescent_metrics=current
            validation=client.validate(fixture,cover_path)
            verify_sources()
            after=snapshot();ended=clock()
            report['phaseResults'].append({'phase':phase,'browserCleanupPassed':False,'scanQuiescentSeconds':quiescent_at-phase_started,
                'scanContainerCpuSeconds':delta(before,quiescent,'cpuSeconds'),
                'wholePhaseIncludingValidationSeconds':ended-phase_started,
                'wholePhaseContainerCpuSeconds':delta(before,after,'cpuSeconds'),
                'containerThrottledSeconds':delta(before,quiescent,'throttledSeconds'),
                'gcPauseCount':delta(before,quiescent_metrics,'gcPauseCount'),'gcPauseSeconds':delta(before,quiescent_metrics,'gcPauseSeconds'),
                'resourceSamples':sampler.samples,'resourceSamplerFailure':sampler.failure,
                'resourceSamplingScope':'Owned app cgroup/RSS at1s during browser and scan, including prompt normal-UI logout. Navigation browser metrics stop before logout. Heap/GC are HTTP endpoint samples, not guaranteed instantaneous peaks.',
                'actualActivityChecks':active_attempts,
                'beforeBrowser':before_ui,'duringBrowser':during_ui,'scanClassification':overlap,
                'gaugeTimes':{'beforeRequested':left_requested,'beforeCompleted':left_completed,
                    'browserStarted':browser_start,'browserFinished':browser_end,
                    'afterRequested':right_requested,'afterCompleted':right_completed},
                'gaugeValues':{'before':left['active'] if left else None,'after':right['active'] if right else None},
                'samples':polls,'before':before,'after':after,'functionalValidation':validation})
            del report['pendingPhase']
            persist_partial(report)
        report['phaseResults'][-1]['browserCleanupPassed']=True
        persist_partial(report)
    recovery_start=clock();samples=[]
    while True:
        sample=snapshot();sample['elapsedSeconds']=clock()-recovery_start;samples.append(sample)
        if sample['active'] or sample['draining']:raise ValueError('Recovery scan workers not quiescent')
        if sample['elapsedSeconds']>=300:break
        sleep(min(15,300-sample['elapsedSeconds']))
    report['naturalRecovery']={'samples':samples,'observedSeconds':samples[-1]['elapsedSeconds'],
        'cpuSeconds':delta(samples[0],samples[-1],'cpuSeconds'),
        'gcPauseCount':delta(samples[0],samples[-1],'gcPauseCount'),
        'gcPauseSeconds':delta(samples[0],samples[-1],'gcPauseSeconds')}
    persist_partial(report)
    all_window_after=resource_snapshot()
    report['allWindowCpuSeconds']=delta(all_window_before,all_window_after,'cpuSeconds')
    # Authentication and final browser job occur AFTER recovery end counters.
    with prepare_browser('post300') as post_browser:report['post300Browser']=browser_sample(post_browser)
    report['post300BrowserExcludedFromRecoveryCpu']=True
    persist_partial(report)
    report['functionalValidation']=client.validate(fixture,cover_path);verify_sources()
    report['cleanAcceptance']=all(p['browserCleanupPassed'] is True and p['resourceSamplerFailure'] is None and bool(p['resourceSamples']) and p['beforeBrowser']['status']=='success' and p['duringBrowser']['status']=='success'
        and p['scanClassification']!='unknown' for p in report['phaseResults']) and report['post300Browser']['status']=='success'
    report['acceptanceLimit']='Outer runner must additionally gate application ERROR/OOM/restart/health and exact cleanup. Unknown overlap or timeout is NOT accepted responsiveness.'
    report['complete']=True
    persist_partial(report)
    return report

def balanced_pairs(run_arm):
    result=[]
    for pair,order in enumerate((('control','candidate'),('candidate','control'),('control','candidate')),1):
        for position,arm in enumerate(order,1):
            result.append({'pair':pair,'position':position,'arm':arm,'report':run_arm(arm,pair,position)})
    return {'scope':'Three order-balanced exploratory pairs; inspect distributions, no automatic statistical win claim',
        'caseOrder':[r['arm'] for r in result],'results':result}
