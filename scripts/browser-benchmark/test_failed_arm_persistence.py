"""Actual execute/persist failure paths, dependency mocks only; no runtime proof."""
import json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import run_benchmark as runner

class FailedArmPersistence(unittest.TestCase):
    def check(self,waiting,cleanup_ok):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);plugin=root/'plugin.jar';plugin.write_bytes(b'owned-fixture')
            control='sha256:'+'1'*64;candidate='sha256:'+'2'*64
            receipt={'controlSource':runner.CONTROL,'candidateSource':runner.CANDIDATE,'entryByteParity':True,
                'normalizationPerformed':False,'differingResourceEntries':[],'controlImageId':control,'candidateImageId':candidate,
                'controlJarSha256':'3'*64,'candidateJarSha256':'4'*64}
            source=root/'receipt.json';source.write_text(json.dumps(receipt));output=root/'report.json'
            args=SimpleNamespace(receipt=str(source),output=str(output),control_image=control,candidate_image=candidate,
                browser_image='sha256:'+'5'*64,metadata_plugin=str(plugin),waiting_plugin=str(plugin),direct_plugin=str(plugin))
            observed={'unexpectedErrorCount':1,'oomLogCount':0,'oomKilled':False,'restartCount':0,
                'health':'UP','healthHttpStatus':200,'failureCategory':'criteria-rejected'}
            class App:
                def __init__(self,image,*a,**k):
                    self.image=image;self.owner='owned';self.network='owned-network';self.ip='172.22.0.2';self.id='owned'
                    self.root=root;self.waiting_root=root;self.cover_path='/images/cover/1';self.client=object();self.password='ephemeral-fixture'
                    self.fixture={'manifestSha256':'a'*64,'manifest':{},'image':{'sha256':'b'*64}}
                def resources(self):return {}
                def final_gate(self,on_observation):
                    on_observation(dict(observed));raise ValueError('fixed failure')
                def __enter__(self):return self
                def __exit__(self,*a):
                    self.cleanup_completed=cleanup_ok
                    if not cleanup_ok:raise RuntimeError('fixed cleanup failure')
            class Browser:
                authenticationEvidence={'normalLoginAutoRedirectObserved':True,'fixtureProfileVerified':True}
                def __init__(self,*a,**k):pass
                def authenticate(self,*a):pass
                def close(self):pass
                def sample(self):pass
                def __enter__(self):return self
                def __exit__(self,*a):self.close()
            def failed_waiting(app,before,on_observation):return app.final_gate(on_observation)
            def held_case(root,request,sample,finish,prerequisites):return finish()
            # waiting preparation references request even though the mock held case does not use it.
            AppClient=SimpleNamespace(request=lambda *a:None)
            original_init=App.__init__
            def init(self,*a,**k):original_init(self,*a,**k);self.client=AppClient
            App.__init__=init
            balanced=(lambda arm:{'caseOrder':[]}) if waiting else runner.balanced_pairs
            with patch.object(runner,'hosted_only'),patch.object(runner,'image_source'),patch('ownership_index.create'),\
                 patch.object(runner,'OwnedApp',App),patch.object(runner,'HostedBrowser',Browser),\
                 patch.object(runner,'run_case',return_value={'complete':True,'cleanAcceptance':False}),\
                 patch.object(runner,'balanced_pairs',balanced),patch.object(runner,'metrics',return_value={'active':0,'draining':0}),\
                 patch.object(runner,'waiting_validation',failed_waiting),\
                 patch.dict('sys.modules',{'original_app_waiting_case':SimpleNamespace(run_case=held_case)}):
                with self.assertRaises(RuntimeError if not cleanup_ok else ValueError):runner.execute(args)
            saved=json.loads(output.read_text())
            target=saved if waiting else saved['results'][0]
            self.assertEqual(target['waitingFinalGate' if waiting else 'finalGate'],observed)
            self.assertEqual(target['waitingExactOwnedCleanupPassed' if waiting else 'exactOwnedCleanupPassed'],cleanup_ok)
            self.assertEqual(target['waitingCleanupOutcome' if waiting else 'cleanupOutcome'],'complete' if cleanup_ok else 'incomplete')
            self.assertFalse(saved['allCasesCompleted'])
    def test_actual_failed_ordinary_arm_persists_gate_and_confirmed_cleanup(self):
        for cleanup_ok in (True,False):self.check(False,cleanup_ok)
    def test_actual_failed_waiting_arm_persists_gate_and_confirmed_cleanup(self):
        for cleanup_ok in (True,False):self.check(True,cleanup_ok)

if __name__=='__main__':unittest.main()
