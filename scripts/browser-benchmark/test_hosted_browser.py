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
    def test_node_interception_failure_roundtrip_and_strict_boundaries(self):
        # Exercise the real producer, not a Python copy of its receipt schema.
        program="""
import {FixtureUiDriver} from './frontend_ui_driver.mjs';
import {recordFirstAuthCdpFailure,failureReceipt} from './browser_entry.mjs';
const rows=['Fetch.continueRequest','Fetch.failRequest'].map(method=>{
  const driver=new FixtureUiDriver({request:async()=>{}},{origin:'http://127.0.0.1:53123',coverId:7,coverSha256:'0'.repeat(64)});
  recordFirstAuthCdpFailure(driver.authDiagnostics,method,new Error('Invalid InterceptionId. synthetic-secret-sentinel'));
  return failureReceipt('authentication',null,null,'profile-proof',null,driver.authDiagnostics);
});
process.stdout.write(JSON.stringify(rows));
"""
        result=subprocess.run(['node','--input-type=module','-e',program],cwd=Path(__file__).resolve().parent,capture_output=True,timeout=10,check=True)
        self.assertLess(len(result.stdout),16384)
        self.assertNotIn(b'synthetic-secret-sentinel',result.stdout)
        for row in json.loads(result.stdout):
            self.assertEqual(row['authDiagnostics']['cdpFailure'],'interception-invalid-id')
            self.assertEqual(row['authDiagnostics']['cdpMethod'],'request-interception')
            self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
            diagnostic=row['authDiagnostics']['interceptionDiagnostics']
            for mutation in ({'failedOperation':'raw-secret'},{'failedRequestCanceled':1},{'failedRequestCanceled':'raw-secret'},{'pausedEvents':257},{'duplicatePausedIds':-1},{'canceledNetworkEvents':True},{'verifiedCanceledImageContinuations':257},{'verifiedCanceledImageContinuations':1},{'truncated':1},{'rawId':'secret'}):
                with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**row['authDiagnostics'],'interceptionDiagnostics':{**diagnostic,**mutation}}})
            for mutation in ({'cdpFailure':'unknown-enum'},{'cdpFailure':'synthetic-secret-sentinel'},{'cdpMethod':'runtime-evaluate'}):
                with self.assertRaises(ValueError):
                    hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**row['authDiagnostics'],**mutation}})
            for mutation in ({'phase':'navigation'},{'authStage':'synthetic-secret-sentinel'}):
                with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,**mutation})
    def test_verified_canceled_image_counter_roundtrips_real_transport_without_success_claim(self):
        program="""
import {FixtureUiDriver} from './frontend_ui_driver.mjs';
import {createCdpTransport,recordFirstAuthCdpFailure} from './frontend_cdp_failures.mjs';
import {failureReceipt} from './browser_entry.mjs';
const origin='http://127.0.0.1:53123';let driver,reject;
const request=createCdpTransport({send:()=>new Promise((_,r)=>{reject=r;})},{captureInterception:()=>driver.captureInterceptionFailure()});
driver=new FixtureUiDriver({request},{origin,coverId:7,coverSha256:'0'.repeat(64)});
driver.event('Network.requestWillBeSent',{requestId:'PRIVATE-DOC',frameId:'PRIVATE-FRAME',loaderId:'PRIVATE-LOADER',type:'Document',request:{url:origin+'/',method:'GET'}});
const image={url:origin+'/images/cover/7',method:'GET'};
driver.event('Network.requestWillBeSent',{requestId:'PRIVATE-NET',frameId:'PRIVATE-FRAME',loaderId:'PRIVATE-LOADER',type:'Image',request:image});
driver.event('Fetch.requestPaused',{requestId:'PRIVATE-FETCH',networkId:'PRIVATE-NET',frameId:'PRIVATE-FRAME',resourceType:'Image',request:image});
driver.event('Network.loadingFailed',{requestId:'PRIVATE-NET',type:'Image',canceled:true});
reject(new Error('Invalid InterceptionId. PRIVATE_COOKIE=secret'));await new Promise(resolve=>setImmediate(resolve));
recordFirstAuthCdpFailure(driver.authDiagnostics,'Runtime.evaluate',new Error('Target closed PRIVATE'));
process.stdout.write(JSON.stringify(failureReceipt('authentication',null,null,'profile-proof',null,driver.authDiagnostics)));
"""
        result=subprocess.run(['node','--input-type=module','-e',program],cwd=Path(__file__).resolve().parent,capture_output=True,timeout=10,check=True)
        self.assertLess(len(result.stdout),4096);self.assertNotIn(b'PRIVATE',result.stdout);self.assertNotIn(b'secret',result.stdout)
        row=json.loads(result.stdout);self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
        self.assertTrue(row['browserFailed']);self.assertEqual(row['authDiagnostics']['cdpFailure'],'target-closed')
        self.assertEqual(row['authDiagnostics']['interceptionDiagnostics']['verifiedCanceledImageContinuations'],1)
    def test_auth_ready_requires_exact_fixture_identity_proof_and_boolean_redirect(self):
        row={'authenticated':True,'sandboxVerified':True,'fixtureProfileVerified':True,'normalLoginAutoRedirectObserved':False}
        self.assertEqual(hosted_browser.validate_auth_ready(row),row)
        for mutation in ({'fixtureProfileVerified':False},{'normalLoginAutoRedirectObserved':1},{'rawIdentity':'private'},{'authenticated':1}):
            with self.assertRaises(ValueError):hosted_browser.validate_auth_ready({**row,**mutation})
    def test_auth_diagnostics_exact_nonsecret_schema(self):
        readiness={k:False for k in ('originMatches','loginPath','usernamePresent','passwordPresent','submitPresent','submitDisabled','invalidCredentialsDisplayed')};readiness['documentReady']='complete'
        profile_flags=('originMatches','profilePath','loginPath','myProfileHeading','usernamePresent','emailPresent','newPasswordPresent','passwordRepeatPresent','baseRootMatches','identityRow','adminItem','signOutItem','navPresent','navbarAvatarPresent','navbarSignInPresent','navTriggerPresent','navTriggerVisible','menuAriaPresent','menuAriaVisible','menuAriaOutsideNav','profileTriggerUnique')
        profile={'stage':'profile-dom','readiness':{**dict.fromkeys(profile_flags,False),'triggerCount':'one'}}
        cookie_keys=('loginSetCookieSeen','loginSessionCookieSetSeen','loginSessionCookieBlocked','profileCookieHeaderSeen','profileSessionCookieAssociated','profileSessionCookieBlocked','loginSubmittedWithPendingRpc','otherSessionCookieSetAfterLogin')
        cookies=dict.fromkeys(cookie_keys)
        data={'loginPostObserved':True,'loginPostStatus':302,'submittedUsernameLengthMatches':True,'submittedPasswordLengthMatches':True,'readiness':readiness,'cdpFailure':'none','cdpMethod':'none','loginResultHeader':'missing','userInfoStatus':None,'profile':profile,'userInfoIdentity':'not-observed','cookieDiagnostics':cookies}
        row={'browserFailed':True,'phase':'authentication','authDiagnostics':data}
        self.assertEqual(hosted_browser.validate_failure_receipt(row),row)
        for mutation in ({'url':'private'},{'loginPostObserved':1},{'loginPostStatus':True},{'loginPostStatus':600},{'submittedUsernameLengthMatches':1},{'submittedPasswordLengthMatches':'private'},{'readiness':{**readiness,'rawText':'secret'}},{'readiness':{**readiness,'documentReady':'private'}},{'readiness':{**readiness,'loginPath':1}}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,**mutation}})
        with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'phase':'navigation'})
        for private_length in ('submittedUsernameLength','submittedPasswordLength'):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,private_length:32}})
        for invalid in ('raw private error',None,True,[]):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'cdpFailure':invalid}})
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'cdpMethod':invalid}})
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'loginResultHeader':invalid}})
        for invalid in (True,99,600,'200',200.5):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'userInfoStatus':invalid}})
        for status in (100,200,599,None):
            hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'userInfoStatus':status}})
        for identity in ('not-observed','null','fixture-user','other','invalid','failed'):
            hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'userInfoIdentity':identity}})
        for invalid in ('fixture-admin',None,1,{},['fixture-user']):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'userInfoIdentity':invalid}})
        for invalid in (None,{**cookies,'rawCookie':'private'},{**cookies,'loginSetCookieSeen':1},{**cookies,'profileCookieHeaderSeen':'private'}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'cookieDiagnostics':invalid}})
        for flag in (True,False,None):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'cookieDiagnostics':dict.fromkeys(cookie_keys,flag)}})
        for invalid in (None,{'stage':'private','readiness':None},{**profile,'raw':'private'},{**profile,'readiness':{**profile['readiness'],'identityRow':1}},{**profile,'readiness':{**profile['readiness'],'triggerCount':2}},{**profile,'readiness':{**profile['readiness'],'rawText':'private'}}):
            with self.assertRaises(ValueError):hosted_browser.validate_failure_receipt({**row,'authDiagnostics':{**data,'profile':invalid}})
        self.assertEqual(hosted_browser.validate_failure_receipt({**row,'authDiagnostics':dict.fromkeys(data)|{'loginPostObserved':False,'cdpFailure':'none','cdpMethod':'none','loginResultHeader':'missing','profile':{'stage':'not-started','readiness':None},'userInfoIdentity':'not-observed','cookieDiagnostics':cookies}})['authDiagnostics']['readiness'],None)
    def test_progress_schema_rejects_private_fields_and_integer_flags(self):
        row={'routeIndex':1,'stage':'content','readiness':{k:False for k in ('pathMatches','contentReady','coverTitlePresent','coverPresent','coverComplete','dimensionsMatch')}}
        self.assertEqual(hosted_browser.validate_navigation_progress({'navigationProgress':row}),{'navigationProgress':row})
        for bad in ({**row,'url':'secret'},{**row,'routeIndex':True},{**row,'stage':'secret'},{**row,'readiness':{**row['readiness'],'pathMatches':1}}):
            with self.assertRaises(ValueError):hosted_browser.validate_navigation_progress({'navigationProgress':bad})
    def test_node_navigation_observation_roundtrip_is_strict_and_nonsecret(self):
        program="""
import {navigationProgress,safeProxyDiagnostics} from './frontend_ui_driver.mjs';
import {LoopbackProxy} from './frontend_loopback_proxy.mjs';
const observation={domRootPresent:true,navPresent:true,signInPresent:false,staticErrorViewPresent:false,uncaughtExceptionSeen:true,truncated:false,...Object.fromEntries(['library','game','profile'].map(k=>[k,{status:'4xx',completion:'finished',httpStatus:403,proxyDeny:'capacity'}]))};
const proxy=new LoopbackProxy('http://127.0.0.1:53123');
process.stdout.write(JSON.stringify({navigationProgress:{...navigationProgress(2,'content',null,observation),proxyDiagnostics:safeProxyDiagnostics(proxy.diagnostics())}}));proxy.close();
"""
        result=subprocess.run(['node','--input-type=module','-e',program],cwd=Path(__file__).resolve().parent,capture_output=True,timeout=10,check=True)
        self.assertLess(len(result.stdout),4096);row=json.loads(result.stdout)
        self.assertEqual(hosted_browser.validate_navigation_progress(row),row)
        progress=row['navigationProgress'];observation=progress['observation']
        for mutation in ({'domRootPresent':1},{'uncaughtExceptionSeen':'secret'},{'raw':'secret'},{'game':{'status':403,'completion':'finished'}},{'game':{'status':'2xx','completion':'secret'}}):
            with self.assertRaises(ValueError):hosted_browser.validate_navigation_progress({'navigationProgress':{**progress,'observation':{**observation,**mutation}}})
        for mutation in ({'httpStatus':True},{'httpStatus':'secret'},{'proxyDeny':'secret'},{'raw':'secret'}):
            with self.assertRaises(ValueError):hosted_browser.validate_navigation_progress({'navigationProgress':{**progress,'observation':{**observation,'game':{**observation['game'],**mutation}}}})
        for mutation in ({'active':9},{'queued':9},{'requests':257},{'sockets':33},{'reservedSockets':9},{'sockets':32,'reservedSockets':1},{'outboundSockets':9},{'outboundSockets':8,'reservedSockets':1},{'peakOutboundSockets':9},{'outboundSockets':True},{'idleSocketsReclaimed':257},{'socketRefusals':True},{'deniedPolicy':True},{'raw':'secret'}):
            with self.assertRaises(ValueError):hosted_browser.validate_navigation_progress({'navigationProgress':{**progress,'proxyDiagnostics':{**progress['proxyDiagnostics'],**mutation}}})
    def test_progress_preserves_receipt_deadline_and_partial_evidence(self):
        value=HostedBrowser.__new__(HostedBrowser);value.process=Mock();value.diagnostic_output=None
        value.record_navigation_progress=Mock()
        row={'navigationProgress':{'routeIndex':1,'stage':'navigate','readiness':None}}
        payload=(json.dumps(row)+'\n').encode();clock=[0]
        def read(*_):
            byte=bytes([payload[clock[0]]]);clock[0]+=1;return byte
        # Receipt progress consumes the original budget; no new deadline is made.
        def now():return 0 if clock[0]<len(payload) else 16
        selector=Mock();selector.select.return_value=[1]
        with patch.object(hosted_browser.selectors,'DefaultSelector') as factory,patch.object(hosted_browser.os,'read',side_effect=read),patch.object(hosted_browser.time,'monotonic',side_effect=now):
            factory.return_value.__enter__.return_value=selector
            with self.assertRaises(TimeoutError):value._read_receipt(15)
        value.record_navigation_progress.assert_called_once_with(row['navigationProgress'])
    def test_progress_flood_is_bounded(self):
        value=HostedBrowser.__new__(HostedBrowser);value.process=Mock();value.diagnostic_output=None;value.record_navigation_progress=Mock()
        payload=iter((json.dumps({'navigationProgress':{'routeIndex':1,'stage':'navigate','readiness':None}})+'\n').encode()*65)
        selector=Mock();selector.select.return_value=[1]
        with patch.object(hosted_browser.selectors,'DefaultSelector') as factory,patch.object(hosted_browser.os,'read',side_effect=lambda *_:bytes([next(payload)])),patch.object(hosted_browser.time,'monotonic',return_value=0):
            factory.return_value.__enter__.return_value=selector
            with self.assertRaisesRegex(ValueError,'count bound'):value._read_receipt(15)
        self.assertEqual(value.record_navigation_progress.call_count,64)
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
