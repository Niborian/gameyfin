"""Narrow local contract/scheduling fixtures; no HTTP/Docker/runtime claim."""
import tempfile,threading,time,unittest
from pathlib import Path
from unittest.mock import patch
from retained_scan_image import fixture_dependencies,validate_retained_inputs,IMAGE
from concurrent_scrape_probe import concurrent_probe
from run_one_app_probe import plugin_proof
from fixture_case import FixtureClient,PLUGIN_ID
import hashlib

class OneAppProbeTests(unittest.TestCase):
    def test_exact_manifest_dependencies_without_guessed_or_ambiguous_paths(self):
        text='Manifest-Version: 1.0\nClass-Path: lib/plugin-api-2.4.3.jar lib/pf4j-3.14.1.jar lib/kotlin-stdlib-2.2.0.jar lib/h2-2.4.240.jar\n'
        self.assertEqual(set(fixture_dependencies(text)),{'plugin-api','pf4j','kotlin-stdlib'})
        self.assertEqual(fixture_dependencies(text.replace('\n','\r\n')),fixture_dependencies(text))
        for bad in [text.replace('lib/pf4j','../lib/pf4j'),text.replace('lib/h2','lib/pf4j-3.0.jar lib/h2'),text.replace('Class-Path:','Other:')]:
            with self.assertRaises(ValueError):fixture_dependencies(bad)
    def test_original_bundle_missing_or_mutated_fails_before_image_command(self):
        with tempfile.TemporaryDirectory() as directory:
            direct=Path(directory)/'direct.jar';direct.write_bytes(b'original');meta=Path(directory)/'fixture.jar';meta.write_bytes(b'fixture')
            inputs={'inventory':{'jarSha256':'a'*64,'buildVersion':'2.4.3','h2Jar':'/opt/gameyfin/lib/h2-2.4.240.jar','pluginSha256':{'direct.jar':'b'*64}},'plugins':(direct,)}
            with patch('retained_scan_image.command',side_effect=AssertionError('Must reject before Docker')):
                with self.assertRaises(ValueError):validate_retained_inputs(IMAGE,'a'*64,direct,meta,inputs)
    def test_browser_does_not_wait_on_probe_and_late_observations_do_not_prove_overlap(self):
        entered=threading.Event();release=threading.Event();calls=[]
        def scan():calls.append('scan')
        def browser():calls.append('browser');entered.set();release.set();return {'success':True}
        def probe(kind):
            self.assertTrue(entered.wait(1));release.wait(1);now=time.monotonic()
            return {'kind':kind,'started':now,'completed':now+1,'active':1,'category':'observed'}
        result=concurrent_probe(scan,browser,probe)
        self.assertEqual(calls,['scan','browser']);self.assertEqual(len(result['probes']),2)
        self.assertTrue(all(row['intervalWhollyInsideBrowser']is False for row in result['probes']))
        self.assertFalse(result['continuousOverlapProven']);self.assertFalse(result['performanceAccepted'])
    def test_actual_owned_join_refuses_live_worker_without_acceptance(self):
        class Thread:
            def __init__(self,**kwargs):pass
            def start(self):pass
            def join(self,timeout):pass
            def is_alive(self):return True
        with patch('concurrent_scrape_probe.threading.Thread',Thread):
            with self.assertRaises(RuntimeError):concurrent_probe(lambda:None,lambda:None,lambda kind:None)
    def test_partial_thread_start_failure_releases_and_joins_started_worker_without_probe(self):
        real_thread=threading.Thread;created=[];probes=[]
        class StartFailure(real_thread):
            def start(self):raise RuntimeError('owned start failure')
        def create(**kwargs):
            value=(real_thread if not created else StartFailure)(**kwargs);created.append(value);return value
        with patch('concurrent_scrape_probe.threading.Thread',create):
            with self.assertRaisesRegex(RuntimeError,'owned start failure'):
                concurrent_probe(lambda:None,lambda:None,lambda kind:probes.append(kind))
        self.assertFalse(any(t.is_alive()for t in created));self.assertEqual(probes,[])
    def test_scan_failure_does_not_launch_network_probes_or_browser(self):
        calls=[];observed=[]
        def scan():raise ValueError('fixed dispatch failure')
        with self.assertRaisesRegex(ValueError,'fixed dispatch failure'):
            concurrent_probe(scan,lambda:calls.append('browser'),lambda kind:calls.append(kind),on_observation=observed.append)
        self.assertEqual(calls,[])
        self.assertEqual(observed[-1]['failureCategory'],'dispatch-failed');self.assertEqual(observed[-1]['probes'],[])
    def test_browser_failure_retains_completed_probe_status_and_times_before_raising(self):
        observed=[]
        def browser():raise ValueError('private browser detail')
        def probe(kind):return {'kind':kind,'started':1.,'completed':2.,'active':0,'httpStatus':503,'category':'http-rejected','private':'must-not-export'}
        with self.assertRaisesRegex(ValueError,'private browser detail'):
            concurrent_probe(lambda:None,browser,probe,on_observation=observed.append)
        self.assertEqual(observed[-1]['failureCategory'],'browser-failed');self.assertEqual(len(observed[-1]['probes']),2)
        self.assertTrue(all(p['httpStatus']==503 for p in observed[-1]['probes']))
        self.assertNotIn('private',str(observed))
    def test_actual_mounted_bundle_proof_detects_postrun_changes(self):
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory);(root/'plugins').mkdir();direct=root/'plugins/direct.jar';direct.write_bytes(b'original')
            provider=root/'fixture.jar';provider.write_bytes(b'fixture');(root/'plugins/fixture.jar').write_bytes(b'fixture')
            evidence={'pluginSha256':{'direct.jar':hashlib.sha256(b'original').hexdigest()}}
            app=type('App',(),{'root':root})();sha=hashlib.sha256(b'fixture').hexdigest()
            self.assertTrue(plugin_proof(app,evidence,provider,sha)['allOriginalPluginJarsUnchanged'])
            direct.write_bytes(b'changed')
            with self.assertRaises(ValueError):plugin_proof(app,evidence,provider,sha)
    def test_actual_bootstrap_isolates_bundle_then_normal_thirdparty_enable(self):
        rows=[{'id':PLUGIN_ID,'trustLevel':'THIRD_PARTY','state':'STOPPED'},
            {'id':'org.gameyfin.plugins.download.direct','state':'STOPPED'},{'id':'original.metadata','state':'STARTED'}]
        client=FixtureClient.__new__(FixtureClient);client.page_csrf=lambda path:None;client.login=lambda password:None
        calls=[]
        def request(path,body):
            calls.append(path)
            if path.endswith('/getAll'):return rows
            if path.endswith('/enablePlugin')or path.endswith('/disablePlugin'):
                row=next(p for p in rows if p['id']==body['pluginId']);row['state']='STARTED'if path.endswith('/enablePlugin')else'STOPPED'
        client.request=request
        password=client.bootstrap(isolate_bundled=True)
        self.assertGreaterEqual(len(password),32);self.assertIn('/connect/PluginEndpoint/disablePlugin',calls)
        self.assertEqual({p['id']for p in rows if p['state']=='STARTED'},{PLUGIN_ID,'org.gameyfin.plugins.download.direct'})

if __name__=='__main__':unittest.main()
