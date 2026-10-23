"""Narrow actual helper diagnostics; no Docker, HTTP or application execution."""
import json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from owned_app import OwnedApp,classify_errors

class FinalGateDiagnostics(unittest.TestCase):
    def app(self,health='UP'):
        return SimpleNamespace(id='owned',inspect=lambda:{'State':{'OOMKilled':False},'RestartCount':0},
            client=SimpleNamespace(raw=lambda *a,**k:(200,{},json.dumps({'status':health}).encode())))
    def test_rejected_gate_is_observed_before_exception_without_log_content(self):
        seen=[]
        with patch('owned_app.command',return_value='ERROR sensitive-fixture-value\n'):
            with self.assertRaises(ValueError):OwnedApp.final_gate(self.app(),seen.append)
        self.assertEqual(seen,[{'unexpectedErrorCount':1,'oomLogCount':0,'oomKilled':False,'restartCount':0,'health':'UP','healthHttpStatus':200,'failureCategory':'criteria-rejected'}])
        self.assertNotIn('sensitive',json.dumps(seen))
    def test_unavailable_stage_and_unknown_health_remain_failure(self):
        seen=[];app=self.app();app.client.raw=lambda *a,**k:(_ for _ in ()).throw(RuntimeError('private-body'))
        with patch('owned_app.command',return_value=''):
            with self.assertRaises(ValueError):OwnedApp.final_gate(app,seen.append)
            self.assertEqual(seen[-1]['failureCategory'],'health-unavailable')
            with self.assertRaises(ValueError):OwnedApp.final_gate(self.app('private-status'),seen.append)
        self.assertEqual(seen[-1]['health'],'other');self.assertNotIn('private',json.dumps(seen))
    def test_non200_and_malformed_health_keep_status_not_raw_body(self):
        for status,body in [(503,b'{"status":"DOWN"}'),(200,b'private-malformed')]:
            app=self.app();app.client.raw=lambda *a,**k:(status,{},body);seen=[]
            with patch('owned_app.command',return_value=''):
                with self.assertRaises(ValueError):OwnedApp.final_gate(app,seen.append)
            self.assertEqual(seen[0]['healthHttpStatus'],status);self.assertNotIn('private',json.dumps(seen))
    def test_passing_gate_still_requires_every_existing_criterion(self):
        with patch('owned_app.command',return_value=''):
            seen=[];gate=OwnedApp.final_gate(self.app(),seen.append)
        self.assertEqual(gate['failureCategory'],'passed');self.assertEqual(seen,[gate])
    def test_optional_failure_health_reader_does_not_use_cookie_transport(self):
        app=self.app();app.client.raw=lambda *a,**k:(_ for _ in()).throw(AssertionError('shared transport forbidden'))
        with patch('owned_app.command',return_value=''):
            self.assertEqual(OwnedApp.final_gate(app,health_reader=lambda:(200,{},b'{"status":"UP"}'))['health'],'UP')
    def test_diagnostic_timeout_and_allowlisted_error_categories_never_retain_secrets(self):
        seen=[];app=self.app();calls=[]
        app.inspect=lambda **kw:(calls.append(kw)or {'State':{'OOMKilled':False},'RestartCount':0})
        logs='2026-10-23T00:00:00 ERROR o.g.a.c.e.EndpointExceptionHandler : secret-token\njava.lang.IllegalStateException: secret-path\nCaused by: private.UnknownException: secret-user\nERROR private-logger secret-value\n'
        with patch('owned_app.command',return_value=logs)as command:
            with self.assertRaises(ValueError):OwnedApp.final_gate(app,seen.append,command_timeout=10)
        self.assertEqual(calls,[{'command_timeout':10}]);command.assert_called_once_with('logs','owned',timeout=10)
        self.assertEqual(seen[0]['errorClassification'],{'loggerCounts':{'ENDPOINT':1,'OTHER':1},'exceptionTypeCounts':{'ILLEGAL_STATE':1,'OTHER':1}})
        self.assertNotIn('secret',json.dumps(seen));self.assertNotIn('private',json.dumps(seen))
    def test_normal_gate_keeps_original_command_default_and_no_classification(self):
        with patch('owned_app.command',return_value='')as command:
            gate=OwnedApp.final_gate(self.app())
        command.assert_called_once_with('logs','owned');self.assertNotIn('errorClassification',gate)
    def test_resource_diagnostic_inspection_uses_ten_seconds_but_normal_stays_default(self):
        app=SimpleNamespace(id='owned',root=Path('unused'),owner='owned',image='image',diagnostic_output=None)
        with patch('owned_app.command',return_value='[{}]')as command,patch('owned_app.validate_owned'):
            OwnedApp.inspect(app,command_timeout=10)
            command.assert_called_once_with('inspect','owned',timeout=10)
        with patch('owned_app.command',return_value='[{}]')as command,patch('owned_app.validate_owned'):
            OwnedApp.inspect(app)
            command.assert_called_once_with('inspect','owned')
        calls=[];app.inspect=lambda **kw:(calls.append(kw)or {'State':{'Pid':0,'Running':False}})
        with self.assertRaises(ValueError):OwnedApp.resources(app,command_timeout=10)
        self.assertEqual(calls,[{'command_timeout':10}])
    def test_close_records_only_confirmed_success_or_failure(self):
        for fail in (False,True):
            with tempfile.TemporaryDirectory() as folder:
                root=Path(folder);(root/'ledger.json').write_text('{}')
                app=SimpleNamespace(root=root,owner='owned',password='ephemeral',watchdog=None)
                with patch('owned_app.cleanup',side_effect=RuntimeError('private') if fail else None):
                    if fail:
                        with self.assertRaises(RuntimeError):OwnedApp.close(app)
                    else:OwnedApp.close(app)
                self.assertEqual(app.cleanup_completed,not fail);self.assertIsNone(app.password)

if __name__=='__main__':unittest.main()
