"""Explicit current GitHub job ownership index; no prefix/glob cleanup."""
import json,os,re,shutil
from pathlib import Path

def job_identity():
    values=[os.environ.get(k,'') for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_JOB')]
    if not re.fullmatch('[0-9]{1,20}',values[0]) or not re.fullmatch('[0-9]{1,5}',values[1]) or not re.fullmatch('[A-Za-z0-9_-]{1,64}',values[2]):raise ValueError('Exact hosted job identity required')
    return '-'.join(values)

def index_path():return Path(os.environ['RUNNER_TEMP']).resolve()/('gameyfin-benchmark-27-owned-'+job_identity()+'.json')

def read(path):
    from owned_app import read_regular
    if Path(path)!=index_path():raise ValueError('Exact current job index path required')
    value=json.loads(read_regular(path,65536))
    if value.get('job')!=job_identity() or not isinstance(value.get('roots'),list) or len(value['roots'])>7:raise ValueError('Current job ownership index boundary')
    return value

def write(path,value):
    pending=Path(path).with_suffix('.pending')
    with pending.open('x') as stream:stream.write(json.dumps(value))
    pending.chmod(0o600);pending.replace(path)

def create():
    path=index_path()
    with path.open('x') as stream:stream.write(json.dumps({'job':job_identity(),'roots':[]}))
    path.chmod(0o600);return path

def planned(path,root,owner):
    value=read(path)
    if len(value['roots'])>=7 or any(r['owner']==owner for r in value['roots']):raise ValueError('Fresh case owner required')
    root=Path(root)
    if root.parent!=index_path().parent or root.name!='gameyfin-browser-fixture-'+owner or not re.fullmatch('[a-f0-9]{16}',owner) or root.exists():raise ValueError('Fresh current-job fixture leaf required')
    value['roots'].append({'root':str(root),'owner':owner,'state':'planned'});write(path,value)

def created(path,root,owner):
    value=read(path);rows=[r for r in value['roots'] if r['owner']==owner]
    if len(rows)!=1 or rows[0]['state']!='planned':raise ValueError('Unique planned owner required')
    info=Path(root).stat(follow_symlinks=False)
    rows[0].update(state='created',device=info.st_dev,inode=info.st_ino);write(path,value)

def cleanup_current():
    from owned_app import cleanup,command
    path=index_path()
    if not path.exists():return
    value=read(path)
    for row in value['roots']:
        root=Path(row['root']);owner=row['owner']
        if root.parent!=path.parent or root.name!='gameyfin-browser-fixture-'+owner or not re.fullmatch('[a-f0-9]{16}',owner):raise ValueError('Indexed exact fixture path required')
        if not root.exists():continue
        observed=root.stat(follow_symlinks=False)
        if root.is_symlink() or root.resolve()!=root or row.get('state')!='created' or (observed.st_dev,observed.st_ino)!=(row.get('device'),row.get('inode')):raise ValueError('Unknown or replaced root retained')
        if (root/'ledger.json').exists():cleanup(root,owner,remove_root=True)
        else:
            ids=command('ps','-aq','--no-trunc').split();containers=json.loads(command('inspect',*ids)) if ids else []
            if any(Path(m.get('Source','/')).resolve()==root or Path(m.get('Source','/')).resolve().is_relative_to(root) for d in containers for m in d.get('Mounts',[])):raise ValueError('Initial fixture unexpectedly referenced; retain')
            observed=root.stat(follow_symlinks=False)
            if (observed.st_dev,observed.st_ino)!=(row['device'],row['inode']):raise ValueError('Initial root changed before delete')
            shutil.rmtree(root)
