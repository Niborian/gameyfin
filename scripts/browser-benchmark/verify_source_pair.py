"""Exact full-build resource/dependency parity gate; never normalizes ZIP bytes.

JAR timestamps are ignored by comparing uncompressed ENTRY bytes, not by
rewriting builds. Build-info differences are explicitly reported and fail the
gate before any benchmark, requiring diagnosis/review rather than a silent fix.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import zipfile

CONTROL='d7c878c1d558d9167ec35b1b2ed0f5977d79c7f7'
CANDIDATE='04eb5c92832e5fb33704f4b0e62c2c9c081d3ab5'


def entries(path):
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate JAR entries')
        if len(names)>50000:raise ValueError('JAR entry bound')
        selected={}
        for entry in jar.infolist():
            name=entry.filename
            if entry.is_dir():continue
            if entry.file_size>256*1024*1024:raise ValueError('JAR member byte bound')
            # Every library and non-code resource must stay identical. Kotlin
            # bytecode/module metadata is the reviewed backend delta, not UI.
            if not (name.startswith('BOOT-INF/classes/') and name.endswith(('.class','.kotlin_module'))):
                selected[name]=hashlib.sha256(jar.read(entry)).hexdigest()
        if not any(('/VAADIN/webapp/' in '/'+name or '/META-INF/resources/' in '/'+name or '/static/' in '/'+name) and name.endswith('.js') for name in selected):
            raise ValueError('Original production UI resources missing')
        configs=[name for name in selected if name.endswith('/VAADIN/config/flow-build-info.json')]
        if len(configs)!=1 or json.loads(jar.read(configs[0])).get('productionMode') is not True:
            raise ValueError('Exact production frontend build configuration required')
        return selected


def verify(control,candidate):
    before,after=entries(control),entries(candidate)
    differing=[name for name in sorted(set(before)|set(after)) if before.get(name)!=after.get(name)]
    return {'controlSource':CONTROL,'candidateSource':CANDIDATE,
        'controlJarSha256':hashlib.sha256(Path(control).read_bytes()).hexdigest(),
        'candidateJarSha256':hashlib.sha256(Path(candidate).read_bytes()).hexdigest(),
        'comparedEntryCount':len(before),'differingResourceEntries':differing,
        'entryByteParity':not differing,'normalizationPerformed':False,
        'scope':'Full original UI/dependency/resource ENTRY bytes; not runtime acceptance'}


def prepare(jar,destination):
    import shutil
    destination=Path(destination);destination.mkdir()
    shutil.copyfile(jar,destination/'application.jar')
    with zipfile.ZipFile(jar) as source:
        for name,pattern in [('h2',r'h2-[0-9].*\.jar'),('plugin-api',r'plugin-api(?:-[0-9].*)?\.jar'),('pf4j',r'pf4j-[0-9].*\.jar'),('kotlin-stdlib',r'kotlin-stdlib-[0-9].*\.jar')]:
            members=[entry for entry in source.namelist() if entry.startswith('BOOT-INF/lib/') and re.fullmatch(pattern,entry.rsplit('/',1)[-1])]
            if len(members)!=1:raise ValueError('Exact single required dependency missing or ambiguous: '+name)
            (destination/(name+'.jar')).write_bytes(source.read(members[0]))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--control',required=True,type=Path);parser.add_argument('--candidate',required=True,type=Path);parser.add_argument('--output',required=True,type=Path);parser.add_argument('--prepare-root',type=Path)
    args=parser.parse_args();report=verify(args.control,args.candidate)
    args.output.write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')
    if not report['entryByteParity']:raise SystemExit('Build resource bytes differ; diagnose before benchmarking')
    if args.prepare_root:
        args.prepare_root.mkdir()
        for name,path in [('control',args.control),('candidate',args.candidate)]:prepare(path,args.prepare_root/name)

if __name__=='__main__':main()
