import json,tempfile,unittest
from pathlib import Path
from container_diagnostics import diagnostic,capture

class ContainerDiagnosticTest(unittest.TestCase):
    def test_exact_scalar_caps_canonical_spellings_and_no_private_fields(self):
        row={'Id':'a'*64,'Image':'sha256:'+'b'*64,'Config':{'User':'10042:10042','Env':['PASSWORD=private-secret'],
            'Labels':{'gameyfin.browser.owner':'c'*16}},'HostConfig':{'NanoCpus':1000000000,'Memory':805306368,
                'CapAdd':['CAP_SYS_ADMIN'],'CapDrop':['ALL'],'NetworkMode':'gameyfin-browser-net-'+'c'*16,
                'SecurityOpt':['no-new-privileges:true','apparmor=private-secret-profile']},
            'Mounts':[{'Type':'tmpfs','Source':'private-secret-path','Destination':'/tmp'}]}
        value=diagnostic(row,'c'*16,'browser','sha256:'+'b'*64)
        self.assertEqual(value['capAdd']['values'],['CAP_SYS_ADMIN'])
        self.assertEqual(value['securityOptions']['categories'],['no-new-privileges','unexpected'])
        self.assertEqual(value['namespaces']['NetworkMode'],'exact-owned-network')
        self.assertNotIn('private-secret',json.dumps(value))
        with tempfile.TemporaryDirectory() as parent:
            path=Path(parent)/'configured.json';capture(path,row,'c'*16,'browser','sha256:'+'b'*64)
            self.assertEqual(json.loads(path.read_text()),value)
    def test_unknown_strings_and_nested_fields_never_repeated(self):
        row={'HostConfig':{'CapAdd':['secret',{'x':'secret'}],'NetworkMode':['secret'],'Memory':-1}}
        value=diagnostic(row,'c'*16,'helper','sha256:'+'b'*64)
        self.assertEqual(value['capAdd']['values'],['unexpected','unexpected'])
        self.assertIsNone(value['resources']['Memory'])
        self.assertNotIn('secret',json.dumps(value))
if __name__=='__main__':unittest.main()
