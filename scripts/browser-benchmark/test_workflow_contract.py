"""Static execution-boundary regression; not a hosted workflow execution proof."""
import re
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]

class WorkflowTests(unittest.TestCase):
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
        for required in ['792a8aaacbc410e6f716ff23c1e38d77f14ba17e','f0c2870ef17d02b4d71bc9fe0170bb89a098a25f','2026-10-11T18:51:55Z','fixed_build_time.init.gradle','source-pair.json','--no-daemon --max-workers=2']:
            self.assertIn(required,text)
        script=(ROOT/'scripts/browser-benchmark/fixed_build_time.init.gradle').read_text()
        self.assertIn('task.getProperties().getTime().set(fixed)',script)
        self.assertNotIn('zipTree',script)

if __name__=='__main__':unittest.main()
