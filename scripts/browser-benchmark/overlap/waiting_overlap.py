"""Fixture-only local/CI host rendezvous orchestration; no Docker or server launch.

Browser action must already be authenticated/ready and enforce its own <=15 s
deadline. All controller timestamps use the SAME host Python monotonic domain.
Java nanoTime receipts are compared ONLY with each other, never with Python.
The causal marker protocol proves waiting-worker overlap without clock offsets.
"""
from pathlib import Path
import os,re,time,stat

def exact_root(root):
    root=Path(root).absolute()
    if not re.fullmatch(r'gameyfin-overlap-fixture-[a-f0-9]{16}',root.name):
        raise ValueError('Exact generated fixture leaf required')
    if not root.is_dir() or root.is_symlink() or root.resolve()!=root:
        raise ValueError('Existing non-symlink fixture root required')
    return root

def receipt(root,name):
    if name not in ('entered','released','exited'):raise ValueError('Receipt name')
    fd=os.open(root/name,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0))
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size>128:raise ValueError('Bounded regular receipt required')
        data=os.read(fd,129)
        if len(data)>128:raise ValueError('Receipt bound')
    finally:os.close(fd)
    match=re.fullmatch(rb'waiting-provider-v1\n(-?[0-9]{1,20})\n',data)
    if not match:raise ValueError('Receipt shape')
    return int(match[1])

def run_waiting_overlap(root,trigger_scan,browser_action,*,clock=time.monotonic_ns,sleep=time.sleep):
    root=exact_root(root)
    if any((root/name).exists() for name in ('entered','released','release','exited')):
        raise ValueError('Fresh rendezvous required')
    dispatch=clock();trigger_scan();deadline=dispatch+20_000_000_000
    while not (root/'entered').exists():
        if clock()>=deadline:raise TimeoutError('Provider did not enter')
        sleep(.005)
    observed=clock();entered=receipt(root,'entered')
    if (root/'exited').exists():raise RuntimeError('Provider exited before browser')
    browser_start=clock();result=None
    try:
        # Runnable integration seam: caller supplies actual original-UI operation.
        result=browser_action()
        browser_end=clock()
        if browser_end-browser_start>15_000_000_000:raise TimeoutError('Browser action exceeded bound')
        if (root/'exited').exists():raise RuntimeError('Provider expired during browser action')
    finally:
        release_start=clock()
        pending=root/'release.pending'
        fd=os.open(pending,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_BINARY',0),0o644)
        try:
            os.fchmod(fd,0o644)
            os.write(fd,b'release\n')
        finally:os.close(fd)
        try:os.link(pending,root/'release',follow_symlinks=False)
        finally:pending.unlink()
    while not (root/'exited').exists():
        if clock()>=deadline:raise TimeoutError('Provider did not exit after release')
        sleep(.005)
    released=receipt(root,'released');exited=receipt(root,'exited');finished=clock()
    if not entered<=released<=exited:raise RuntimeError('Provider receipt order invalid')
    if not dispatch<=observed<=browser_start<=browser_end<=release_start<=finished:
        raise RuntimeError('Controller monotonic order invalid')
    return {'scope':'controlled waiting-provider overlap; NOT CPU-heavy performance',
        'workerLifetimeOverlapVerified':True,'browserResult':result,
        'controllerMonotonicNs':{'dispatch':dispatch,'enteredObserved':observed,
            'browserStart':browser_start,'browserEnd':browser_end,'releaseStart':release_start,'finished':finished},
        'providerNanoTime':{'entered':entered,'released':released,'exited':exited},
        'clockScope':'Controller/browser callback timestamps same-host Python monotonic. Java values compare within Java only; causal marker ordering avoids cross-runtime clock-origin assumptions.'}
