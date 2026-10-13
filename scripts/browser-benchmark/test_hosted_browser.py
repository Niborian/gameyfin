import unittest,tempfile,json,subprocess
from pathlib import Path
from unittest.mock import Mock,patch
import hosted_browser
from hosted_browser import creation_args,validate_sample,cleanup_known_browser,record_creation_intent,HostedBrowser
def sample_receipt():
    row={'domContentLoadedMs':1,'loadMs':2,'routeContentReadyMs':3,'coverDecodedMs':4,'coverNaturalWidth':320,'coverNaturalHeight':480,'coverResponseStatus':200,'coverSha256Matches':True,'fromDiskCache':False,'fromServiceWorker':False}
    snapshot={'cpuUsageUsec':1,'memoryCurrentBytes':1,'memoryPeakBytes':1}
    return {'originalUiContentReady':True,'coverDecoded':True,'coverSha256Matches':True,'navigations':[row]*3,'browserResources':{'before':snapshot,'after':snapshot}}

class CreationTests(unittest.TestCase):
    def test_failure_receipt_category_is_strict_nonsecret_enum(self):
        row={'browserFailed':True,'phase':'chrome-launch','launchFailureCategory':'sandbox-unavailable'}
        self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
        for mutation in ({'launchFailureCategory':'secret password'},{'launchFailureCategory':[]},{'phase':'navigation'},{'rawMessage':'secret'},{'browserFailed':1}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,**mutation})
    def test_auth_failure_enum_and_numeric_counters(self):
        counters={x:0 for x in ('pids.events.max','memory.events.max','memory.events.oom','memory.events.oom_kill')}
        row={'browserFailed':True,'phase':'authentication','authStage':'submit','failureCounters':counters}
        self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
        for mutation in ({'authStage':'private password'},{'phase':'navigation'},{'failureCounters':{**counters,'private':'password'}},{'failureCounters':{**counters,'pids.events.max':True}}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,**mutation})
    def test_launch_diagnostics_strict_no_extra_or_string_evidence(self):
        data={key:False for key in ('messageTruncated','launchCallable','processExitHeader','launchNotFunction','setuidNotRoot','crashpadPermission','devtoolsEndpoint','protocolTimeout','configuredPathMissing','cacheConfiguration')}
        data.update(errorName='Error',errno='other',exitCode=1,executableRegular=True,executableAccessible=True,preflightPhase='complete',preflightErrno='other')
        row={'browserFailed':True,'phase':'chrome-launch','launchFailureCategory':'other','launchDiagnostics':data}
        self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
        for mutation in ({'rawMessage':'secret'},{'exitCode':True},{'exitCode':256},{'errorName':'secret'},{'executableRegular':'/private/path'}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'launchDiagnostics':{**data,**mutation}})
    def test_sandbox_and_resource_boundary(self):
        args=creation_args('a'*16,'sha256:'+'b'*64,'gameyfin-browser-net-'+'a'*16,'172.22.0.2',1,'c'*64)
        self.assertEqual(args.count('--cap-add'),2)
        self.assertEqual(hosted_browser.CREATION_PLAN['capAdd'],['SYS_ADMIN','SYS_CHROOT'])
        self.assertEqual(hosted_browser.CREATION_PLAN['pidsLimit'],256)
        for value in ['--read-only','--init','--interactive','no-new-privileges','10042:10042','SYS_ADMIN','SYS_CHROOT','768m','256']:
            self.assertIn(value,args)
        for value in ['--privileged','--publish','--network=host','--no-sandbox','/var/run/docker.sock']:
            self.assertNotIn(value,args)

    def test_external_or_mutable_targets_rejected(self):
        values=['a'*16,'sha256:'+'b'*64,'gameyfin-browser-net-'+'a'*16,'172.22.0.2',1,'c'*64]
        for index,invalid in [(0,'other'),(1,'latest'),(2,'bridge'),(3,'10.0.0.200'),(3,'172.22.999.2'),(4,0),(5,'incorrect')]:
            case=values.copy();case[index]=invalid
            with self.assertRaises(ValueError):creation_args(*case)

    def test_strict_sample_schema(self):
        row={'domContentLoadedMs':1,'loadMs':2,'routeContentReadyMs':3,'coverDecodedMs':4,'coverNaturalWidth':320,'coverNaturalHeight':480,'coverResponseStatus':200,'coverSha256Matches':True,'fromDiskCache':False,'fromServiceWorker':False}
        snapshot={'cpuUsageUsec':1,'memoryCurrentBytes':1,'memoryPeakBytes':1}
        receipt={'originalUiContentReady':True,'coverDecoded':True,'coverSha256Matches':True,'navigations':[row]*3,'browserResources':{'before':snapshot,'after':snapshot}}
        self.assertEqual(validate_sample(receipt),receipt)
        for key,value in [('url','secret'),('loadMs',float('nan')),('loadMs',-1),('loadMs',True),('coverSha256Matches',1)]:
            bad=row.copy();bad[key]=value
            with self.assertRaises(ValueError):validate_sample({**receipt,'navigations':[bad]*3})

    def test_cleanup_failure_keeps_watchdog_but_reaps_owned_attach(self):
        value=HostedBrowser.__new__(HostedBrowser)
        value.id='a'*64;value.owner='b'*16;value.image='sha256:'+'c'*64
        value.process=Mock();value.watchdog=Mock()
        with patch.object(hosted_browser,'cleanup',side_effect=ValueError('scope')):
            with self.assertRaises(ValueError):value.close()
        value.process.communicate.assert_called_once_with(timeout=2)
        value.watchdog.terminate.assert_not_called()

    def test_hung_attach_does_not_prevent_watchdog_reap_after_removal(self):
        value=HostedBrowser.__new__(HostedBrowser)
        value.id='a'*64;value.owner='b'*16;value.image='sha256:'+'c'*64
        value.process=Mock();value.watchdog=Mock()
        value.process.communicate.side_effect=[subprocess.TimeoutExpired('attach',2),subprocess.TimeoutExpired('attach',2),(b'',b'')]
        with patch.object(hosted_browser,'cleanup'):value.close()
        value.process.terminate.assert_called_once();value.process.kill.assert_called_once()
        value.watchdog.terminate.assert_called_once();value.watchdog.communicate.assert_called_once_with(timeout=2)
    def test_logout_failure_keeps_navigation_diagnostic_and_still_removes(self):
        value=HostedBrowser.__new__(HostedBrowser);value.id='a'*64;value.owner='b'*16;value.image='sha256:'+'c'*64
        value.process=Mock();value.watchdog=Mock();value.authenticated=True;value._read_receipt=Mock(side_effect=[sample_receipt(),TimeoutError()])
        with tempfile.TemporaryDirectory() as directory:
            value.diagnostic_output=Path(directory)/'diagnostic.json';value.diagnostic_output.write_text('{}')
            with patch.object(hosted_browser,'cleanup') as cleanup:
                with self.assertRaises(TimeoutError):
                    with value:value.sample()
            retained=json.loads(value.diagnostic_output.read_text())
            self.assertFalse(retained['normalUiLogoutPassed']);self.assertEqual(retained['navigationEvidenceBeforeLogout'],sample_receipt())
        cleanup.assert_called_once();self.assertEqual([c.args[0] for c in value.process.stdin.write.call_args_list],[b'navigate\n',b'logout\n'])
        value.watchdog.terminate.assert_called_once();self.assertIsNone(value.id)
    def test_sample_requires_logout_under_shared_operation_deadline(self):
        value=HostedBrowser.__new__(HostedBrowser);value.id='a'*64;value.owner='b'*16;value.image='sha256:'+'c'*64
        value.process=Mock();value.watchdog=Mock();value.authenticated=True;value.diagnostic_output=None
        value._read_receipt=Mock(side_effect=[sample_receipt(),{'loggedOut':True}])
        with patch.object(hosted_browser.time,'monotonic',side_effect=[0,1,2]):self.assertEqual(value.sample(),sample_receipt())
        self.assertFalse(value.authenticated);self.assertEqual([c.args[0] for c in value._read_receipt.call_args_list],[14,13])
    def test_logout_boolean_integer_alias_is_not_success(self):
        value=HostedBrowser.__new__(HostedBrowser);value.process=Mock();value.authenticated=True;value.diagnostic_output=None
        value._read_receipt=Mock(side_effect=[sample_receipt(),{'loggedOut':1}])
        with self.assertRaises(ValueError):value.sample()
        self.assertTrue(value.authenticated)

    def test_final_cleanup_uses_exact_durable_id_not_name_or_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            owner='b'*16;root=Path(directory)/('gameyfin-browser-fixture-'+owner);root.mkdir();(root/'OWNER').write_text('benchmark-27-'+owner)
            identity='a'*64;image='sha256:'+'c'*64
            (root/'browser-ledger.json').write_text(json.dumps({'owner':owner,'id':identity,'image':image}))
            with patch.object(hosted_browser,'command',side_effect=[identity+'\n','']),patch.object(hosted_browser,'cleanup') as clean:
                cleanup_known_browser(root,owner)
                clean.assert_called_once_with(identity,owner,image,hosted_browser.command)
            self.assertFalse((root/'browser-ledger.json').exists())

    def test_creation_intent_recovers_cancelled_create_without_returned_id(self):
        with tempfile.TemporaryDirectory() as directory:
            owner='b'*16;root=Path(directory)/('gameyfin-browser-fixture-'+owner);root.mkdir();(root/'OWNER').write_text('benchmark-27-'+owner)
            identity='a'*64;image='sha256:'+'c'*64
            record_creation_intent(root,owner,image)
            self.assertFalse((root/'browser-ledger.json').exists())
            inventory=identity+' gameyfin-browser-'+owner+'\n'+'d'*64+' other-browser\n'
            with patch.object(hosted_browser,'command',side_effect=[inventory,'']),patch.object(hosted_browser,'cleanup') as clean:
                cleanup_known_browser(root,owner)
                clean.assert_called_once_with(identity,owner,image,hosted_browser.command)
            self.assertFalse((root/'browser-intent.json').exists())

if __name__=='__main__':unittest.main()
