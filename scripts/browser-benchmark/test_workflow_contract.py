"""Static execution-boundary regression; not a hosted workflow execution proof."""
import re
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]

class WorkflowTests(unittest.TestCase):
    def test_suite_envelope_does_not_relax_individual_work_or_resources(self):
        workflow=(ROOT/'.github/workflows/browser-scan-benchmark.yml').read_text()
        self.assertIn('timeout-minutes: 180',workflow)
        self.assertIn('timeout --signal=TERM --kill-after=15s 9000s python3',workflow)
        app=(ROOT/'scripts/browser-benchmark/owned_app.py').read_text()
        case=(ROOT/'scripts/browser-benchmark/throughput_case.py').read_text()
        browser=(ROOT/'scripts/browser-benchmark/owned_container_watchdog.py').read_text()
        self.assertIn('if args.watchdog:time.sleep(1200)',app)
        self.assertIn('deadline=time.monotonic()+180',app)
        self.assertIn('deadline=phase_started+180',case)
        self.assertIn("for phase in ('cold','repeat')",case)
        self.assertIn("(('control','candidate'),('candidate','control'),('control','candidate'))",case)
        self.assertIn("if sample['elapsedSeconds']>=300:break",case)
        self.assertIn('<= 90',browser)

    def test_reviewed_hosted_readonly_manual_or_label_gate(self):
        text=(ROOT/'.github/workflows/browser-scan-benchmark.yml').read_text()
        for required in ['types: [labeled]','runs-on: ubuntu-24.04','contents: read','persist-credentials: false',"github.event.label.name == format('bench-ok-{0}', github.event.pull_request.head.sha)",'inputs.reviewed == true','inputs.reviewed_head == github.sha','head.repo.full_name == github.repository','if: always()','--cleanup-only']:
            self.assertIn(required,text)
        for forbidden in ['packages: write','secrets.','self-hosted','docker push','docker login','docker system prune','32181','10.0.0.200','latest']:
            self.assertNotIn(forbidden,text)
        for action in re.findall(r'uses: ([^\s]+)',text):
            self.assertRegex(action,r'^[\w/-]+@[a-f0-9]{40}$')

    def test_exact_pair_and_recorded_build_input(self):
        text=(ROOT/'.github/workflows/browser-scan-benchmark.yml').read_text()
        for required in ['5a6f51442157fd4bcd1041997fb5560f665f7278','81f85eb4c5c9cfe5e0c4b4cb0b9420ea9e04f798','2026-10-11T18:51:55Z','fixed_build_time.init.gradle','source-pair.json','--no-daemon --max-workers=2']:
            self.assertIn(required,text)
        script=(ROOT/'scripts/browser-benchmark/fixed_build_time.init.gradle').read_text()
        self.assertIn('task.getProperties().getTime().set(fixed)',script)
        self.assertNotIn('zipTree',script)
        self.assertIn('git show -s --format=%cI f0c2870ef17d02b4d71bc9fe0170bb89a098a25f',text)
        self.assertNotIn('git show -s --format=%cI "$CANDIDATE_SOURCE"',text)
        for arm in ('CONTROL','CANDIDATE'):
            self.assertIn('git rev-parse "$'+arm+'_SOURCE^{commit}"',text)

if __name__=='__main__':unittest.main()
