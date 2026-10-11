import tempfile,unittest,zipfile
from pathlib import Path
from verify_source_pair import verify

class ParityTests(unittest.TestCase):
    def test_backend_only_delta_and_resource_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            paths=[Path(directory)/name for name in ('a.jar','b.jar')]
            def write(path,code,ui=b'original',metadata=b'build'):
                with zipfile.ZipFile(path,'w') as jar:
                    jar.writestr('BOOT-INF/classes/org/gameyfin/GameService.class',code)
                    jar.writestr('BOOT-INF/classes/META-INF/VAADIN/webapp/index.html',ui)
                    jar.writestr('BOOT-INF/classes/META-INF/VAADIN/webapp/assets/index.js',ui)
                    jar.writestr('BOOT-INF/classes/META-INF/VAADIN/config/flow-build-info.json','{"productionMode":true}')
                    jar.writestr('BOOT-INF/classes/META-INF/build-info.properties',metadata)
                    jar.writestr('BOOT-INF/lib/dependency.jar',b'same')
            write(paths[0],b'control');write(paths[1],b'candidate')
            self.assertTrue(verify(*paths)['entryByteParity'])
            write(paths[1],b'candidate',metadata=b'different')
            report=verify(*paths);self.assertFalse(report['entryByteParity'])
            self.assertEqual(report['differingResourceEntries'],['BOOT-INF/classes/META-INF/build-info.properties'])

if __name__=='__main__':unittest.main()
