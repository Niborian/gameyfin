import unittest
from unittest.mock import patch
import original_app_waiting_case as adapter

class OriginalAppCaseTest(unittest.TestCase):
    def receipt(self):
        return {'syntheticFixtureOnly':True,'normalAdminAuthenticated':True,
            'browserAlreadyAuthenticated':True,'originalGameyfinUi':True,
            'boundedBrowserOperationSeconds':15,'publishedPorts':False,'cpuLimit':.5,
            'memoryMiB':1024,'heapMiB':320,'waitingProviderOptIn':True,'scanLibraryId':27003,'gameId':33000}
    def validation(self):
        return {'scanCompleted':True,'failedLibraries':0,'retainedGameCount':132,
            'target33000Enriched':True,'all132PinnedDefaultsPresent':True,'sourceFileCount':924,
            'sourceHashesUnchanged':True,'coverAssociationStable':True,'unexpectedErrorCount':0,
            'oomKilled':False,'exactDownloadChecks':[True,True]}
    def invoke(self,validation=None,trust='THIRD_PARTY',browser=None):
        calls=[]
        def request(path,body):
            calls.append((path,body))
            return [{'id':adapter.PLUGIN_ID,'trustLevel':trust,'state':'STARTED'}] if path.endswith('/getAll') else None
        def causal(root,trigger,action):
            trigger();return {'browserResult':action(),'workerLifetimeOverlapVerified':True}
        with patch.object(adapter,'run_waiting_overlap',causal):
            result=adapter.run_case(None,request,lambda:browser or
                {'originalUiContentReady':True,'coverDecoded':True,'coverSha256Matches':True},
                lambda:validation or self.validation(),self.receipt())
        return result,calls
    def test_exact_normal_scan_and_gate(self):
        result,calls=self.invoke()
        self.assertTrue(result['originalUiAndFunctionalGatePassed'])
        self.assertEqual(calls[-1],('/connect/LibraryEndpoint/triggerScan',{'scanType':'FULL','libraryIds':[27003]}))
    def test_trust_not_bypassed(self):
        with self.assertRaises(ValueError):self.invoke(trust='TRUSTED')
    def test_missing_downloads_no_vacuous_pass(self):
        validation=self.validation();validation['exactDownloadChecks']=[]
        with self.assertRaises(ValueError):self.invoke(validation=validation)
    def test_source_change_or_placeholder_ui_rejected(self):
        validation=self.validation();validation['sourceHashesUnchanged']=False
        with self.assertRaises(ValueError):self.invoke(validation=validation)
        with self.assertRaises(ValueError):self.invoke(browser={'originalUiContentReady':False})
if __name__=='__main__':unittest.main()
