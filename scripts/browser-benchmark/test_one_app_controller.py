"""Actual controller failure/cleanup persistence, mocks only; no runtime execution."""
import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import run_one_app_probe as runner
from types import SimpleNamespace

class ControllerFailureTests(unittest.TestCase):
    def fixture(self,root,output,*,ownership_failure=False,cleanup_failure=False):
        patches=[patch.object(runner,'hosted_only'),patch.object(runner,'input_root',return_value=root),
            patch.object(runner,'index_path',return_value=root.parent/'absent-index.json'),patch.object(runner,'job_identity',return_value='owned-job'),
            patch.object(runner,'create'),patch.object(runner.signal,'signal'),patch.object(runner.signal,'SIGALRM',0,create=True),
            patch.object(runner.signal,'alarm',create=True),patch.object(runner,'download',side_effect=ValueError('private-artifact-detail')),
            patch.object(runner,'cleanup_all',side_effect=RuntimeError('private-cleanup-detail') if cleanup_failure else None)]
        if ownership_failure:patches.append(patch.object(runner,'write',side_effect=OSError('private-owner-detail')))
        return patches
    def run_failure(self,ownership_failure,cleanup_failure):
        from contextlib import ExitStack
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory)/'owned-inputs';output=Path(directory)/'reports/result.json'
            with ExitStack()as stack:
                mocks=[stack.enter_context(p)for p in self.fixture(root,output,ownership_failure=ownership_failure,cleanup_failure=cleanup_failure)]
                with self.assertRaises((ValueError,OSError,RuntimeError)):runner.execute('sha256:'+'1'*64,output)
            receipt=json.loads(output.read_text())
            self.assertFalse(receipt['complete']);self.assertNotIn('private-',json.dumps(receipt))
            self.assertEqual(receipt['failure']['stage'],'input-ownership'if ownership_failure else'artifact')
            self.assertEqual(receipt['cleanupOutcome'],'unknown-retained'if cleanup_failure else'complete')
            self.assertEqual(receipt['cleanupConfirmed'],not cleanup_failure)
            mocks[9].assert_called_once()
    def test_owner_write_failure_still_attempts_scoped_cleanup_and_persists_failure(self):self.run_failure(True,False)
    def test_artifact_failure_persists_distinct_confirmed_cleanup(self):self.run_failure(False,False)
    def test_cleanup_uncertainty_is_not_success_even_with_original_failure(self):self.run_failure(False,True)
    def test_inventory_substage_is_persisted_before_failure_and_cleanup(self):
        from contextlib import ExitStack
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory)/'owned-inputs';output=Path(directory)/'reports/result.json'
            def fail_inventory(root,*,on_observation):
                on_observation({'stage':'fixture-dependency-selection'})
                raise ValueError('private manifest content')
            def cleanup():
                self.assertEqual(json.loads(output.read_text())['inventoryObservation'],{'stage':'fixture-dependency-selection'})
            with ExitStack()as stack:
                for p in self.fixture(root,output):stack.enter_context(p)
                stack.enter_context(patch.object(runner,'download',return_value={}))
                stack.enter_context(patch.object(runner,'inventory',side_effect=fail_inventory))
                stack.enter_context(patch.object(runner,'cleanup_all',side_effect=cleanup))
                with self.assertRaises(ValueError):runner.execute('sha256:'+'1'*64,output)
            receipt=json.loads(output.read_text())
            self.assertEqual(receipt['failure'],{'stage':'image-inventory','category':'ValueError'})
            self.assertTrue(receipt['cleanupConfirmed']);self.assertNotIn('private',json.dumps(receipt))
    def test_preexisting_output_is_rejected_before_any_input_mutation(self):
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory)/'must-not-exist';output=Path(directory)/'existing.json';output.write_text('unchanged')
            with patch.object(runner,'hosted_only'),patch.object(runner,'input_root',return_value=root),patch.object(runner,'create')as create:
                with self.assertRaises(ValueError):runner.execute('sha256:'+'1'*64,output)
                create.assert_not_called()
            self.assertFalse(root.exists());self.assertEqual(output.read_text(),'unchanged')
    def test_actual_controller_persists_failed_concurrency_evidence_before_cleanup(self):
        from contextlib import ExitStack
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory)/'owned-inputs';output=Path(directory)/'reports/result.json';cleaned=[]
            class App:
                def __init__(self,*a,**k):
                    self.root=root;self.owner='owned';self.network='network';self.ip='172.22.0.2';self.id='owned';self.password='ephemeral'
                    self.cover_path='/images/cover/1';self.fixture={'manifest':{},'image':{'sha256':'a'*64}}
                    self.client=SimpleNamespace(scan=lambda:None)
                def resources(self):return {}
                def __enter__(self):return self
                def __exit__(self,*a):self.cleanup_completed=True
            class Browser:
                authenticationEvidence={'normalLoginAutoRedirectObserved':True,'fixtureProfileVerified':True}
                def __init__(self,*a,**k):pass
                def authenticate(self,*a):pass
                def sample(self):raise ValueError('private browser detail')
                def __enter__(self):return self
                def __exit__(self,*a):pass
            def probe(address,kind):return {'kind':kind,'started':1.,'completed':2.,'httpStatus':503,'active':None,'category':'http-rejected'}
            def cleanup():
                saved=json.loads(output.read_text());self.assertEqual(saved['concurrent']['failureCategory'],'browser-failed')
                self.assertEqual(len(saved['concurrent']['probes']),2);cleaned.append(True)
            with ExitStack()as stack:
                for p in self.fixture(root,output):stack.enter_context(p)
                for p in [patch.object(runner,'download',return_value={}),patch.object(runner,'inventory',return_value=({'jarSha256':'b'*64},Path('direct.jar'),())),
                    patch.object(runner,'build_provider',return_value=(Path('fixture.jar'),{'fixtureJarSha256':'c'*64})),patch.object(runner,'OwnedApp',App),
                    patch.object(runner,'HostedBrowser',Browser),patch.object(runner,'verify_sources'),patch.object(runner,'plugin_proof',return_value={}),
                    patch.object(runner,'metrics',return_value={'active':0,'draining':0,'completed':0,'failed':0}),patch.object(runner,'scrape',side_effect=probe),
                    patch.object(runner,'cleanup_all',side_effect=cleanup)]:stack.enter_context(p)
                with self.assertRaises(ValueError):runner.execute('sha256:'+'1'*64,output)
            self.assertEqual(cleaned,[True]);receipt=json.loads(output.read_text())
            self.assertFalse(receipt['complete']);self.assertTrue(receipt['cleanupConfirmed']);self.assertNotIn('private',json.dumps(receipt))

if __name__=='__main__':unittest.main()
