"""Portable opt-in waiting-fixture builder; explicit cached dependencies, no downloads."""
import argparse,hashlib,os,subprocess,tempfile,zipfile
from pathlib import Path

def lifecycle_failure_category(stderr):
    # Only these fixture-owned literals may leave the private child capture.
    text=stderr.decode('utf-8','replace')[:16384] if isinstance(stderr,bytes) else ''
    for literal,category in (
        ('Invalid fixture release marker','invalid-release-marker'),
        ('Fixture waiting-provider deadline exceeded','provider-deadline'),
        ('AssertionError: Not held','hold-assertion'),
        ('AssertionError: Release failed','release-assertion'),
        ('TimeoutException','test-future-deadline')):
        if literal in text:return category
    return 'other'

def check_lifecycle(argv):
    try:subprocess.run(argv,check=True,timeout=10,capture_output=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError('Waiting fixture lifecycle failed: '+lifecycle_failure_category(error.stderr)) from None
    except subprocess.TimeoutExpired:
        raise RuntimeError('Waiting fixture lifecycle failed: process-deadline') from None

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('javac','java','plugin-api','pf4j','kotlin-stdlib','output'):p.add_argument('--'+name,required=True)
    a=p.parse_args();output=Path(a.output)
    if output.exists():raise ValueError('New-only fixture output')
    deps=[Path(a.plugin_api),Path(a.pf4j),Path(a.kotlin_stdlib)]
    if any(not d.is_file() or d.is_symlink() for d in deps):raise ValueError('Explicit existing dependency JARs')
    cp=os.pathsep.join(str(d.resolve()) for d in deps);base=Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix='gameyfin-waiting-provider-build-') as tmp:
        classes=Path(tmp)
        sources=[base/'FixtureMetadataPlugin.java',*[base/'overlap'/name for name in
            ('FixtureRendezvous.java','WaitingMetadataPlugin.java','FixtureRendezvousTest.java')]]
        subprocess.run([a.javac,'-proc:none','-cp',cp,'-d',str(classes),*map(str,sources)],check=True,timeout=60,capture_output=True)
        check_lifecycle([a.java,'-Xmx32m','-cp',str(classes)+os.pathsep+cp,
            'org.gameyfin.experiments.fixture.overlap.FixtureRendezvousTest'])
        manifest=('Manifest-Version: 1.0\nPlugin-Version: 0.0.2\n'
            'Plugin-Class: org.gameyfin.experiments.fixture.overlap.WaitingMetadataPlugin\n'
            'Plugin-Id: org.gameyfin.experiments.fixture.metadata\n'
            'Plugin-Name: Synthetic Waiting Metadata Fixture\n'
            'Plugin-Description: Staging-only provider waiting, never throughput evidence.\n'
            'Plugin-Author: Niborian\nPlugin-License: MIT\n\n')
        with zipfile.ZipFile(output,'x') as jar:
            def entry(name,data):
                info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16
                jar.writestr(info,data)
            entry('META-INF/MANIFEST.MF',manifest)
            entry('META-INF/extensions.idx','org.gameyfin.experiments.fixture.overlap.WaitingMetadataPlugin$Provider\n')
            for source in sorted(classes.rglob('*.class')):
                if source.name.startswith('FixtureRendezvousTest'):continue
                entry(source.relative_to(classes).as_posix(),source.read_bytes())
    print('Built opt-in waiting fixture SHA256 '+hashlib.sha256(output.read_bytes()).hexdigest())
if __name__=='__main__':main()
