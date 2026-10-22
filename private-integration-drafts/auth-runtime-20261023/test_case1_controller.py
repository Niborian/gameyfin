"""Limited protocol/lease/unchanged-guard tests; never starts Docker or a browser."""
import ast
import copy
import inspect
import io
import json
import os
import tempfile
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts' / 'browser-benchmark'))
import hosted_browser as original
import hosted_browser_case1 as draft
import owned_public_policy as lease_module
from owned_app import OwnedApp
from public_image_ui import IMAGE


def receipt():
    yes = ['uiVersionVerified', 'anonymousProfileWasNull', 'originalPublicLibraryAndGamePassed',
           'coversDecodedAndHashVerified', 'actualSignInAndLoginReadinessPassed', 'oldDocumentCatalogHttpAndWebSocketsSettledAtCheckpoint']
    no = ['anonymousAdminSubscriptionAbsenceProven', 'sameDocumentReactCancellationProven',
          'staleSubscriptionCallbackFencingProven', 'fullIssue154AcceptanceProven']
    proof = {**dict.fromkeys(yes, True), **dict.fromkeys(no, False),
             'authDocumentCatalogRpcCount': 0, 'anonymousAdminListHttpRpcCount': 0,
             'authCatalogObservationWindow': draft.CASE1_WINDOW,
             'naturalPendingCatalogAtSignIn': 0, 'catalogRequestsCancelled': 0}
    keys = ['requests', 'active', 'queued', 'peakActive', 'peakQueued', 'sockets', 'reservedSockets',
            'peakSockets', 'outboundSockets', 'peakOutboundSockets', 'idleSocketsReclaimed', 'socketRefusals',
            'deniedCapacity', 'deniedRequestBudget', 'deniedPolicy', 'deniedDeadline', 'deniedClosed']
    return {'publicAuthCase1': proof, 'proxyDiagnostics': dict.fromkeys(keys, 0)}


class ProtocolTest(unittest.TestCase):
    @unittest.skipUnless(os.name=='posix','Actual private-file permission guard requires POSIX; hosted CI executes this fixture')
    def test_real_reader_persists_validated_failure_and_rejects_unknown_fields(self):
        data={'stage':'anonymous-profile','dom':None,'anonymousRequestObserved':False,'anonymousProfileWasNull':False,'observationOverflow':False,'loginDocumentObserved':False,'pendingCatalogHttp':0,'oldWebSockets':0,'anonymousAdminHttp':0,'authCatalogHttp':0}
        data.update(profileClassification='not-observed',anonymousHttpStatus=None)
        good={'browserFailed':True,'phase':'navigation','publicAuthCase1Failure':data}
        self.assertEqual(good,draft.validate_failure_receipt(good))
        for bad in [dict(good,private='forbidden'),dict(good,publicAuthCase1Failure=dict(data,rawUrl='forbidden'))]:
            with self.assertRaises(ValueError):draft.validate_failure_receipt(bad)
        class ReadySelector:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def register(self,*args):pass
            def select(self,*args):return [True]
        # Actual reader and persistence method, real pipe bytes and private temporary file.
        for valid in [True,False]:
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'browser.json';path.write_text('{}');path.chmod(0o600)
                browser=draft.HostedBrowser.__new__(draft.HostedBrowser)
                browser.diagnostic_output=path
                read_fd,write_fd=os.pipe()
                try:
                    payload=good if valid else dict(good,publicAuthCase1Failure=dict(data,rawUrl='forbidden'))
                    os.write(write_fd,(json.dumps(payload)+'\n').encode())
                    with os.fdopen(read_fd,'rb',buffering=0) as stream:
                        browser.process=SimpleNamespace(stdout=stream)
                        with patch.object(draft.selectors,'DefaultSelector',ReadySelector),self.assertRaises(ValueError):browser._read_receipt(1)
                    persisted=json.loads(path.read_text())
                    self.assertEqual({'browserDriverFailure':good} if valid else {},persisted)
                finally:os.close(write_fd)

    def test_exact_receipt_and_caps(self):
        good = receipt()
        self.assertEqual(good, draft.validate_case1_receipt(good))
        for group, key, value in [('publicAuthCase1', 'fullIssue154AcceptanceProven', True),
                                  ('publicAuthCase1', 'anonymousAdminSubscriptionAbsenceProven', True),
                                  ('publicAuthCase1', 'naturalPendingCatalogAtSignIn', 129),
                                  ('publicAuthCase1', 'authDocumentCatalogRpcCount', False),
                                  ('proxyDiagnostics', 'requests', 257), ('proxyDiagnostics', 'active', 9),
                                  ('proxyDiagnostics', 'sockets', 33)]:
            bad = copy.deepcopy(good)
            bad[group][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                draft.validate_case1_receipt(bad)
        with self.assertRaises(ValueError):
            draft.validate_case1_receipt({**good, 'private': 'forbidden'})

    def test_actual_transport_method_sends_only_fixed_case1_command_once(self):
        browser = draft.HostedBrowser.__new__(draft.HostedBrowser)
        browser.public_case1 = True
        browser.process = SimpleNamespace(stdin=io.BytesIO())
        browser._read_receipt = lambda timeout: receipt() if timeout == 60 else None
        self.assertEqual(receipt(), browser.public_auth_case1())
        self.assertEqual(b'public-auth-case1\n', browser.process.stdin.getvalue())
        with self.assertRaises(ValueError):
            browser.public_auth_case1()

    def test_original_ownership_caps_receipt_reader_and_cleanup_are_unchanged(self):
        for name in ['creation_args', 'validate_app_contract', 'cleanup_known_browser', 'validate_navigation_progress']:
            self.assertEqual(inspect.getsource(getattr(original, name)), inspect.getsource(getattr(draft, name)), name)
        for name in ['close', 'record_navigation_progress', 'record_driver_failure']:
            self.assertEqual(inspect.getsource(getattr(original.HostedBrowser, name)), inspect.getsource(getattr(draft.HostedBrowser, name)), name)
        self.assertEqual(inspect.getsource(original.HostedBrowser._read_receipt),inspect.getsource(draft.HostedBrowser._read_receipt).replace('value=validate_failure_receipt(value)','validate_failure_receipt(value)'))
        source = inspect.getsource(draft.HostedBrowser.__init__)
        self.assertIn("'--public-auth-case1'", source)
        self.assertIn('validate_browser_contract(created[0],self.id,owner,image)', source)
        self.assertIn("'owned_container_watchdog.py'", source)


class LeaseTest(unittest.TestCase):
    def app(self, value=False, fail_enable=False, fail_restore=False):
        app = OwnedApp.__new__(OwnedApp)
        app.ui_only, app.image = True, IMAGE
        app.inspect = lambda: {'State': {'Running': True}}
        state = {'value': value, 'calls': []}
        def request(path, body):
            state['calls'].append(path)
            if path == '/connect/ConfigEndpoint/getAll':
                return [{'key': lease_module.KEY, 'value': state['value']}]
            self.assertEqual('/connect/ConfigEndpoint/update', path)
            target = body['update']['updates']
            self.assertEqual({lease_module.KEY}, set(target))
            state['value'] = target[lease_module.KEY]
            if target[lease_module.KEY] is True and fail_enable or target[lease_module.KEY] is False and fail_restore:
                raise ValueError('Synthetic uncertain policy response')
        app.client = SimpleNamespace(request=request)
        return app, state

    def test_wrong_image_or_changed_public_source_is_rejected_before_policy_access(self):
        app, state = self.app()
        with patch.object(lease_module, 'SOURCE', 'b' * 40), self.assertRaises(ValueError):
            lease_module.OwnedPublicPolicy(app)
        app.image = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError):
            lease_module.OwnedPublicPolicy(app)
        self.assertEqual([], state['calls'])

    @patch.object(lease_module, 'hosted_only')
    def test_restores_original_effective_value_after_browser_failure(self, _):
        app, state = self.app()
        lease = lease_module.OwnedPublicPolicy(app)
        with self.assertRaisesRegex(RuntimeError, 'synthetic browser'):
            with lease:
                self.assertIs(state['value'], True)
                raise RuntimeError('synthetic browser')
        self.assertIs(state['value'], False)
        self.assertTrue(lease.effective_policy_restored)
        self.assertEqual('/connect/ConfigEndpoint/getAll', state['calls'][-1])

    @patch.object(lease_module, 'hosted_only')
    def test_uncertain_enable_attempt_still_restores_and_reads_back(self, _):
        app, state = self.app(fail_enable=True)
        lease = lease_module.OwnedPublicPolicy(app)
        with self.assertRaises(ValueError):
            with lease:
                self.fail('uncertain enable cannot start browser')
        self.assertIs(state['value'], False)
        self.assertTrue(lease.effective_policy_restored)

    @patch.object(lease_module, 'hosted_only')
    def test_failed_restoration_is_never_reported_success(self, _):
        app, state = self.app(fail_restore=True)
        lease = lease_module.OwnedPublicPolicy(app)
        with self.assertRaises(ValueError):
            with lease:
                pass
        self.assertFalse(lease.effective_policy_restored)


if __name__ == '__main__':
    unittest.main()
