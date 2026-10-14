"""Compile against exact supplied existing API/PF4J jars; no downloads."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile
import os

PLUGIN_ID='org.gameyfin.experiments.fixture.metadata'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javac',required=True); parser.add_argument('--java',required=True)
    parser.add_argument('--plugin-api',type=Path,required=True); parser.add_argument('--pf4j',type=Path,required=True)
    parser.add_argument('--kotlin-stdlib',type=Path,required=True); parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('new output required')
    dependencies=[args.plugin_api,args.pf4j,args.kotlin_stdlib]
    if any(not path.is_file() for path in dependencies): raise ValueError('explicit existing dependency jars required')
    classpath=os.pathsep.join(str(path.resolve()) for path in dependencies)
    base=Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix='gameyfin-positive-provider-build-') as temporary:
        classes=Path(temporary)
        subprocess.run([args.javac,'-proc:none','-cp',classpath,'-d',str(classes),str(base/'FixtureMetadataPlugin.java'),str(base/'FixtureProviderTest.java')],check=True,timeout=60)
        subprocess.run([args.java,'-cp',str(classes)+os.pathsep+classpath,'org.gameyfin.experiments.fixture.FixtureProviderTest'],check=True,timeout=30)
        manifest=('Manifest-Version: 1.0\nPlugin-Version: 0.0.1\nPlugin-Class: org.gameyfin.experiments.fixture.FixtureMetadataPlugin\n'
            'Plugin-Id: '+PLUGIN_ID+'\nPlugin-Name: Synthetic Positive Metadata Fixture\nPlugin-Description: Staging-only deterministic provider; no network.\nPlugin-Author: Niborian\nPlugin-License: MIT\n\n')
        with zipfile.ZipFile(args.output,'x',compression=zipfile.ZIP_DEFLATED) as jar:
            jar.writestr('META-INF/MANIFEST.MF',manifest)
            jar.writestr('META-INF/extensions.idx','org.gameyfin.experiments.fixture.FixtureMetadataPlugin$Provider\n')
            for source in classes.rglob('*.class'):
                if source.name.startswith('FixtureProviderTest'): continue
                jar.write(source,source.relative_to(classes).as_posix())
    print('Built staging-only plugin SHA256 '+hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__=='__main__': main()
