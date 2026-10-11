"""Guard JUnit reporting's read-only fork contract without extra dependencies."""
from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/package.yml"


class JunitReportPolicyTests(unittest.TestCase):
    def test_report_uses_supported_annotation_only_inputs(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        report = text.split("      - name: Publish test report\n", 1)[1].split("\n  package:", 1)[0]
        self.assertIn("        if: always()", report)
        self.assertIn("        uses: mikepenz/action-junit-report@v6", report)
        inputs = dict(re.findall(r"^          ([a-z_]+): (.+)$", report, re.MULTILINE))
        self.assertEqual(inputs, {
            "report_paths": '"**/build/test-results/test/TEST-*.xml"',
            "annotate_only": "true", "job_summary": "true",
        })

    def test_verify_keeps_read_only_token_and_gradle_failure_gate(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        verify = text.split("  verify:\n", 1)[1].split("\n  package:", 1)[0]
        permissions = verify.split("    permissions:\n", 1)[1].split("    outputs:\n", 1)[0]
        self.assertEqual(permissions.strip(), "contents: read")
        tests = verify.split("      - name: Run automated tests\n", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("run: ./gradlew :app:test --no-daemon --console=plain", tests)
        self.assertNotIn("continue-on-error", tests)
        self.assertNotIn("pull_request_target:", text)


if __name__ == "__main__":
    unittest.main()
