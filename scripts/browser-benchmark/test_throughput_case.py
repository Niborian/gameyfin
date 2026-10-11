import unittest
from throughput_case import parse_metrics,balanced_pairs,run_case,browser_sample

def text(completed=0,active=0):
    return (f'gameyfin_scans_active {active}\ngameyfin_scans_draining 0\n'
        f'gameyfin_scans_completed_total{{type="full"}} {completed}\n'
        'gameyfin_scans_failed_total{type="full"} 0\n'
        'jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 123\n'
        'jvm_gc_pause_seconds_count{action="minor"} 0\n'
        'jvm_gc_pause_seconds_sum{action="minor"} 0\n').encode()

class Clock:
    t=0
    def now(self):return self.t
    def sleep(self,seconds):self.t+=seconds

class Client:
    completed=0;pending=False
    def raw(self,path,management=False):
        if 'includedNames' in path:return 200,{},f'gameyfin_scans_active {int(self.pending)}\n'.encode()
        if self.pending:self.completed+=4;self.pending=False
        return 200,{},text(self.completed)
    def scan(self):self.pending=True
    def validate(self,fixture,path):return {'testDoubleOnly':True}

class Browser:
    def __init__(self,clock):self.clock=clock
    def __enter__(self):return self
    def __exit__(self,*_):pass
    def sample(self):
        self.clock.sleep(1)
        return {'originalUiContentReady':True,'coverDecoded':True,'coverSha256Matches':True,'testDoubleOnly':True}

class ThroughputCaseTest(unittest.TestCase):
    def test_required_metrics_not_optional_or_nan(self):
        parsed=parse_metrics(text())
        self.assertEqual(parsed['heapBytes'],123)
        with self.assertRaises(ValueError):parse_metrics(text().replace(b'gameyfin_scans_active 0',b'gameyfin_scans_active NaN'))
        with self.assertRaises(ValueError):parse_metrics(text().replace(b'gameyfin_scans_draining 0\n',b''))
        self.assertEqual(parse_metrics(text()+b'irrelevant_metric -1\n')['active'],0)
    def test_order_balanced_six_sequential_arms(self):
        calls=[]
        report=balanced_pairs(lambda arm,pair,position:calls.append((arm,pair,position)))
        self.assertEqual(report['caseOrder'],['control','candidate','candidate','control','control','candidate'])
        self.assertEqual(len(calls),6)
    def test_original_ui_deadline_is_not_success(self):
        class Hung:
            def sample(self):raise TimeoutError()
        self.assertEqual(browser_sample(Hung())['acceptance'],'not_completed')
    def test_normal_scans_and_full_natural_recovery(self):
        clock=Clock();client=Client();browser_phases=[];source_checks=[]
        def browser(phase):browser_phases.append(phase);return Browser(clock)
        def resource():return {'cpuSeconds':clock.t/4,'throttledSeconds':clock.t/2,'memoryBytes':1000000,
            'anonymousBytes':900000,'fileCacheBytes':100000,'rssBytes':950000}
        report=run_case(client,{},'/images/cover/1',browser,resource,lambda:source_checks.append(True),clock=clock.now,sleep=clock.sleep)
        self.assertEqual(client.completed,8)
        self.assertEqual(report['naturalRecovery']['observedSeconds'],300)
        self.assertEqual(report['naturalRecovery']['cpuSeconds'],75)
        self.assertEqual(report['phaseResults'][0]['scanClassification'],'bracketed-active')
        self.assertEqual(browser_phases,['cold-before','cold-during','repeat-before','repeat-during','post300'])
        self.assertEqual(len(source_checks),3)
        self.assertTrue(report['post300BrowserExcludedFromRecoveryCpu'])
if __name__=='__main__':unittest.main()
