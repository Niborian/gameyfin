"""Waiting-fixture adapter for an already-owned, authenticated ORIGINAL app.

Does not launch Docker, login, enable plugins, change configuration or export
credentials. The reviewed outer runner must bind the private client/browser to
the same exact fixture and enforce child deadlines/cleanup. No production use.
"""
from waiting_overlap import run_waiting_overlap
PLUGIN_ID='org.gameyfin.experiments.fixture.metadata'

def run_case(root,request,browser_action,wait_for_scan_and_validate,receipt):
    required={'syntheticFixtureOnly':True,'normalAdminAuthenticated':True,
        'browserAlreadyAuthenticated':True,'originalGameyfinUi':True,
        'boundedBrowserOperationSeconds':15,'publishedPorts':False,
        'cpuLimit':.5,'memoryMiB':1024,'heapMiB':320,
        'waitingProviderOptIn':True,'scanLibraryId':27003,'gameId':33000}
    if any(receipt.get(k)!=v for k,v in required.items()):
        raise ValueError('Exact reviewed synthetic waiting-fixture prerequisites required')
    plugins=request('/connect/PluginEndpoint/getAll',{})
    matches=[p for p in plugins if p.get('id')==PLUGIN_ID]
    if len(matches)!=1 or matches[0].get('trustLevel')!='THIRD_PARTY' or matches[0].get('state')!='STARTED':
        raise ValueError('Normal fixture plugin trust/enabled lifecycle required')
    result=run_waiting_overlap(root,
        lambda:request('/connect/LibraryEndpoint/triggerScan',{'scanType':'FULL','libraryIds':[27003]}),
        browser_action)
    # The browser callback returns only sanitized ORIGINAL UI readiness evidence.
    browser=result['browserResult']
    if not isinstance(browser,dict) or any(browser.get(k) is not True for k in
        ('originalUiContentReady','coverDecoded','coverSha256Matches')):
        raise ValueError('Original game UI and exact decoded cover evidence required')
    validation=wait_for_scan_and_validate()
    expected={'scanCompleted':True,'failedLibraries':0,'retainedGameCount':132,
        'target33000Enriched':True,'all132PinnedDefaultsPresent':True,
        'sourceFileCount':924,'sourceHashesUnchanged':True,
        'coverAssociationStable':True,'unexpectedErrorCount':0,'oomKilled':False}
    if any(validation.get(k)!=v for k,v in expected.items()):
        raise ValueError('Waiting-fixture scan/correctness acceptance failed')
    downloads=validation.get('exactDownloadChecks')
    if not isinstance(downloads,list) or len(downloads)!=2 or any(x is not True for x in downloads):
        raise ValueError('Two actual exact-selection download checks required')
    result['originalUiAndFunctionalGatePassed']=True
    result['interpretation']='One controlled waiting-provider concurrency case; NOT unblocked CPU-heavy scan, throughput/resource improvement, production or cutover acceptance.'
    return result
