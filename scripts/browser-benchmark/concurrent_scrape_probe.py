"""One-app technical diagnostic only; no Docker, credentials or performance claim."""
import ipaddress,math,re,socket,threading,time,urllib.error,urllib.request

PATHS={'filtered':'/actuator/prometheus?includedNames=gameyfin_scans_active','unfiltered':'/actuator/prometheus'}

def safe_probe(row,kind):
    if not isinstance(row,dict)or row.get('kind')!=kind:raise ValueError('Probe identity')
    def number(key,limit=float('inf')):
        value=row.get(key)
        return value if type(value)in(int,float)and math.isfinite(value)and 0<=value<=limit else None
    status=row.get('httpStatus');category=row.get('category')
    return {'kind':kind,'started':number('started'),'completed':number('completed'),
        'httpStatus':status if type(status)is int and 100<=status<=599 else None,'active':number('active',4),
        'category':category if category in('observed','unavailable','timeout','http-rejected','transport-unavailable','invalid-or-unavailable')else'invalid-or-unavailable'}

def scrape(address,kind,*,clock=time.monotonic):
    parsed=ipaddress.ip_address(address)
    if parsed.version!=4 or not parsed.is_private or parsed.is_loopback or parsed.is_link_local or kind not in PATHS:raise ValueError('Exact private management target')
    started=clock();row={'kind':kind,'started':started,'completed':None,'httpStatus':None,'active':None,'category':'unavailable'}
    # A separate opener for EACH read; no browser/admin cookies or shared mutable jar.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*a,**k):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    try:
        with opener.open('http://'+str(parsed)+':8081'+PATHS[kind],timeout=10) as response:
            row['httpStatus']=response.status;bound=8192 if kind=='filtered' else 1024*1024
            deadline=started+10;chunks=[];total=0
            owned_socket=response.fp.raw._sock
            if not isinstance(owned_socket,socket.socket):raise ValueError('Owned urllib socket required')
            while True:
                remaining=deadline-clock()
                if remaining<=0:raise TimeoutError('Absolute scrape deadline')
                owned_socket.settimeout(remaining)
                chunk=response.read1(min(65536,bound+1-total))
                if not chunk:break
                total+=len(chunk);chunks.append(chunk)
                if total>bound:raise ValueError('Scrape byte bound')
            if clock()>deadline:raise TimeoutError('Absolute scrape deadline')
            body=b''.join(chunks)
            if response.status!=200 or len(body)>bound:raise ValueError('Bounded scrape required')
            rows=re.findall(rb'^gameyfin_scans_active ([0-4](?:\.0+)?)$',body,re.M)
            if len(rows)!=1:raise ValueError('Exact active gauge required')
            row.update(active=float(rows[0]),category='observed')
    except urllib.error.HTTPError as error:
        row.update(httpStatus=error.code if 100<=error.code<=599 else None,category='http-rejected')
    except (socket.timeout,TimeoutError):row['category']='timeout'
    except urllib.error.URLError as error:row['category']='timeout' if isinstance(error.reason,(socket.timeout,TimeoutError))else'transport-unavailable'
    except Exception:row['category']='invalid-or-unavailable'
    row['completed']=clock();return row

def concurrent_probe(scan,browser,probe,*,clock=time.monotonic,join_seconds=11,on_observation=lambda value:None):
    """Exactly two probes after dispatch; never wait on a scrape before browser."""
    if join_seconds!=11:raise ValueError('Fixed owned join bound')
    release=threading.Event();results={};failures=[];proceed=False;attempted=[]
    def observe(kind):
        release.wait()
        if not proceed:return
        try:results[kind]=probe(kind)
        except Exception:failures.append(kind)
    threads=[threading.Thread(target=observe,args=(kind,),name='owned-scrape-'+kind,daemon=False)for kind in PATHS]
    before=clock();scan_returned=None;browser_started=None;browser_ended=None;value=None;phase='worker-start';failed=None
    def observe_partial(category):
        # Project fixed safe fields only; never exception text or injected response bodies.
        rows=[]
        for kind in PATHS:
            if kind not in results:continue
            row=results[kind]
            if not isinstance(row,dict):continue
            try:rows.append(safe_probe(row,kind))
            except ValueError:pass
        snapshot={'scope':'One ordinary scan technical diagnostic; incomplete is not acceptance','complete':False,
            'failureCategory':category,'dispatchStarted':before,'dispatchReturned':scan_returned,
            'browserStarted':browser_started,'browserCompleted':browser_ended,'probes':rows,
            'continuousOverlapProven':False,'performanceAccepted':False}
        on_observation(snapshot)
    try:
        for thread in threads:
            attempted.append(thread);thread.start()
        phase='dispatch';scan();scan_returned=clock();proceed=True;release.set()
        phase='browser'
        browser_started=clock();value=browser();browser_ended=clock()
    except BaseException:
        failed=phase+'-failed';raise
    finally:
        release.set()
        deadline=clock()+join_seconds
        uncertain=False
        try:
            for thread in attempted:
                if getattr(thread,'ident',None)is not None or thread.is_alive():thread.join(max(0,deadline-clock()))
            uncertain=any(t.is_alive()for t in attempted)
        except Exception:uncertain=True
        observe_partial('join-uncertain'if uncertain else failed or'joined')
        if uncertain:raise RuntimeError('Owned management probe did not terminate')
    if failures or set(results)!=set(PATHS):
        observe_partial('probe-worker-failed');raise ValueError('Complete bounded probe results required')
    for kind,row in results.items():
        row=safe_probe(row,kind);results[kind]=row
        row['intervalWhollyInsideBrowser']=row['category']=='observed' and row['active']is not None and row['active']>0 and row['started']is not None and row['completed']is not None and browser_started<=row['started']<=row['completed']<=browser_ended
    report={'scope':'One ordinary scan sequencing diagnostic; not paired performance, continuous overlap or cutover acceptance','complete':True,
        'dispatchStarted':before,'dispatchReturned':scan_returned,'browserStarted':browser_started,'browserCompleted':browser_ended,
        'probes':[results[k]for k in PATHS],'browser':value,'continuousOverlapProven':False,'performanceAccepted':False}
    on_observation(report);return report
