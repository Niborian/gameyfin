import copy,json,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
import chrome_smoke
from hosted_browser import CREATION_PLAN
from owned_container_watchdog import validate
from test_owned_container_watchdog import fixture,ID,OWNER,IMAGE

def probe_row():
    row=fixture();row['Name']='/gameyfin-browser-cancel-probe-'+OWNER;row['Config']['Labels']['gameyfin.browser.purpose']='cancel-probe-only';row['HostConfig'].update(NetworkMode='none',RestartPolicy={'Name':'no','MaximumRetryCount':0},ShmSize=128*1024**2,Tmpfs={'/tmp':'rw,nosuid,nodev,size=128m,mode=1777'},SecurityOpt=['no-new-privileges']);row['NetworkSettings']['Networks']={'none':{}};row['Config'].update(Entrypoint=['node'],Cmd=chrome_smoke.mode_plan(True));return row

def plan():return {'owner':OWNER,'image':IMAGE,'id':ID,'contract':CREATION_PLAN,'entrypoint':['node'],'cmd':chrome_smoke.mode_plan(True),'purpose':'chrome-cancel-probe-only'}
def receipt():
    e={'resourceType':'Image','method':'GET','target':'exact-cover','reason':'eligible','permitted':True,'pauseEligible':True,'metadataPresent':True,'duplicate':False,'truncated':False,'typedBinding':True,'frameMatches':True,'loaderMatches':True,'generationMatches':True,'currentHop':True,'reused':False,'ambiguous':False,'canceledBeforeFailure':True}
    cases=[{'kind':'current-document','complete':True,'category':'interception-invalid-id','terminalCancellationAccepted':True,'matchingCancellationObserved':True,'continueCommands':1,'eligibility':e},{'kind':'old-loader','complete':True,'category':'interception-invalid-id','terminalCancellationAccepted':False,'matchingCancellationObserved':True,'continueCommands':1,'eligibility':{**e,'reason':'loader-mismatch','loaderMatches':False,'generationMatches':False}}]
    return {'scope':'Owned Chrome-only cancellation ordering; not application or performance acceptance','complete':True,'sandboxVerified':True,'cases':cases,'failure':'none','cleanup':{'browserClosed':True,'profileRemoved':True,'forcedTermination':False,'complete':True},'listenersClosed':True}

class ProbeController(unittest.TestCase):
    def test_actual_network_validator_keeps_none_explicit_and_all_other_boundaries(self):
        self.assertEqual(validate(probe_row(),ID,OWNER,IMAGE,network_none=True),ID)
        with self.assertRaises(ValueError):validate(probe_row(),ID,OWNER,IMAGE)
        with self.assertRaises(ValueError):validate(fixture(),ID,OWNER,IMAGE,network_none=True)
        for mutate in [lambda r:r['HostConfig'].update(NetworkMode='host'),lambda r:r['HostConfig'].update(NetworkMode='bridge'),lambda r:r['NetworkSettings'].update(Networks={}),lambda r:r['HostConfig'].update(RestartPolicy={'Name':'always','MaximumRetryCount':0}),lambda r:r['HostConfig'].update(PortBindings={'1/tcp':[{}]}),lambda r:r['HostConfig'].update(Privileged=True),lambda r:r['HostConfig'].update(CapAdd=['ALL']),lambda r:r.update(Id='d'*64)]:
            row=probe_row();mutate(row)
            with self.assertRaises(ValueError):validate(row,ID,OWNER,IMAGE,network_none=True)
        with self.assertRaises(ValueError):validate(probe_row(),ID,OWNER,IMAGE,network_none=1)

    def test_cross_mode_ledger_purpose_or_command_never_reaches_docker_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);startup=root/'startup.json';probe=root/'probe.json';probe.touch();startup.touch()
            paths=lambda mode=False:probe if mode else startup
            for payload,mode,path in [(plan(),False,startup),({k:v for k,v in plan().items() if k!='purpose'},True,probe),({**plan(),'cmd':chrome_smoke.mode_plan(False)},True,probe),({**plan(),'purpose':'startup'},True,probe)]:
                with patch.object(chrome_smoke,'ledger_path',side_effect=paths),patch.object(chrome_smoke,'read_state',return_value=payload),patch.object(chrome_smoke,'command') as command:
                    with self.assertRaises(ValueError):chrome_smoke.cleanup(path,mode)
                    command.assert_not_called()
            with patch.object(chrome_smoke,'ledger_path',side_effect=paths),patch.object(chrome_smoke,'command') as command:
                with self.assertRaises(ValueError):chrome_smoke.cleanup(startup,True)
                command.assert_not_called()

    def test_exact_probe_cleanup_uses_actual_none_validator_and_no_network_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'probe.json';path.touch();calls=[]
            def command(*args):
                calls.append(args)
                if args[0]=='ps':return ID if len(calls)==1 else ''
                if args[0]=='inspect':return json.dumps([probe_row()])
                return ''
            with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke,'read_state',return_value=plan()),patch.object(chrome_smoke,'command',side_effect=command):chrome_smoke.cleanup(path,True)
            self.assertEqual(calls,[('ps','-aq','--no-trunc'),('inspect',ID),('rm','--force',ID),('ps','-aq','--no-trunc'),('ps','-aq','--no-trunc')]);self.assertFalse(path.exists())

    def exercise_run(self,attach_error=False,unknown_create=False,cleanup_error=False):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);path=root/'probe.json';output=root/'result.json';calls=[];state={'exists':False};watchdog=Mock();watchdog.wait.return_value=0
            def docker(_deadline,*args):
                calls.append(args)
                if args[0]=='create':
                    state['exists']=True
                    if unknown_create:raise subprocess.TimeoutExpired('safe-create',1)
                    return ID
                if args[0]=='inspect':return json.dumps([probe_row()])
                if args[0]=='ps':return ID if state['exists'] else ''
                if args[0]=='rm':
                    if cleanup_error:raise RuntimeError('Synthetic cleanup unavailable')
                    state['exists']=False;return ''
                raise AssertionError('Unexpected command')
            result=Mock(returncode=0,stdout=json.dumps(receipt()))
            with patch.object(chrome_smoke,'ledger_path',return_value=path),patch.object(chrome_smoke.secrets,'token_hex',return_value=OWNER),patch.object(chrome_smoke,'read_state',side_effect=lambda p,_:json.loads(p.read_text())),patch.object(chrome_smoke,'budget_command',side_effect=docker),patch.object(chrome_smoke.subprocess,'Popen',return_value=watchdog) as popen,patch.object(chrome_smoke.subprocess,'run',side_effect=subprocess.TimeoutExpired('safe-attach',1) if attach_error else None,return_value=result):
                if attach_error or unknown_create or cleanup_error:
                    with self.assertRaises((subprocess.TimeoutExpired,RuntimeError)):chrome_smoke.run(IMAGE,output,True)
                    self.assertFalse(output.exists())
                else:
                    chrome_smoke.run(IMAGE,output,True);final=json.loads(output.read_text());self.assertTrue(final['containerCleanupConfirmed']);chrome_smoke.validate_probe_receipt(final,controller=True)
                self.assertTrue(popen.call_args.kwargs['start_new_session']);self.assertIn('--cancel-probe',popen.call_args.args[0]);watchdog.terminate.assert_called_once();watchdog.wait.assert_called_once_with(timeout=2)
            create=[c for c in calls if c[0]=='create'];self.assertEqual(len(create),1);self.assertEqual(create[0][create[0].index('--network')+1],'none');self.assertEqual(create[0][-1],'/opt/gameyfin-browser/cdp_cancel_probe.mjs');self.assertFalse(any(c[0]=='network' for c in calls));self.assertIn(('rm','--force',ID),calls)
            self.assertEqual(path.exists(),cleanup_error);self.assertEqual(state['exists'],cleanup_error)
    def test_timeout_reaps_detached_watchdog_and_exact_container_without_success(self):self.exercise_run(attach_error=True)
    def test_unknown_create_discovers_only_owned_identity_without_duplicate_create(self):self.exercise_run(unknown_create=True)
    def test_cleanup_failure_retains_intent_provisional_but_never_final_acceptance(self):self.exercise_run(cleanup_error=True)
    def test_final_acceptance_written_only_after_actual_owned_cleanup(self):self.exercise_run()

    def test_schema_and_case_proofs_do_not_allow_false_global_or_protocol_success(self):
        row=receipt();self.assertEqual(chrome_smoke.validate_probe_receipt(row),row)
        for mutate in [lambda r:r.update(raw='secret'),lambda r:r.update(sandboxVerified=False),lambda r:r['cleanup'].update(forcedTermination=True,complete=False),lambda r:r['cases'][0].update(category='none'),lambda r:r['cases'][0]['eligibility'].update(reason='not-image'),lambda r:r['cases'][1]['eligibility'].update(loaderMatches=True),lambda r:r['cases'][1].update(continueCommands=True),lambda r:r['cases'][0]['eligibility'].update(rawId='secret')]:
            bad=copy.deepcopy(row);mutate(bad)
            with self.assertRaises(ValueError):chrome_smoke.validate_probe_receipt(bad)
        with self.assertRaises(ValueError):chrome_smoke.validate_probe_receipt({**row,'containerCleanupConfirmed':True})
        with self.assertRaises(ValueError):chrome_smoke.validate_probe_receipt({**row,'containerCleanupConfirmed':False},controller=True)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'result.json';chrome_smoke.write_probe_output(path,row)
            with self.assertRaises(FileExistsError):chrome_smoke.write_probe_output(path,row)
    def test_probe_workflow_is_manual_reviewed_head_only_and_cannot_build_or_publish_app(self):
        workflow=(Path(__file__).resolve().parents[2]/'.github/workflows/browser-cancel-probe.yml').read_text()
        for required in ('workflow_dispatch:','inputs.reviewed == true','inputs.reviewed_head == github.sha',"github.repository == 'Niborian/gameyfin'",'contents: read','persist-credentials: false','--cancel-probe --cleanup-only','if: always()','--kill-after=1s 99s','scripts/browser-benchmark/Dockerfile'):
            self.assertIn(required,workflow)
        for forbidden in ('pull_request:','pull_request_target:','gradlew','docker push','secrets.','write-all','packages: write','--no-sandbox'):
            self.assertNotIn(forbidden,workflow)

if __name__=='__main__':unittest.main()
