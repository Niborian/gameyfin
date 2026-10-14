import subprocess,unittest
from unittest.mock import patch
from build_waiting_fixture import check_lifecycle,lifecycle_failure_category

class WaitingBuilderTests(unittest.TestCase):
    def test_only_fixed_failure_categories_leave_capture(self):
        private=b'/private/path token=never-print\nInvalid fixture release marker\n'
        self.assertEqual(lifecycle_failure_category(private),'invalid-release-marker')
        self.assertEqual(lifecycle_failure_category(b'unknown password and paths'),'other')
        self.assertEqual(lifecycle_failure_category(b'x'*16384+b'Invalid fixture release marker'),'other')
        with patch('build_waiting_fixture.subprocess.run',side_effect=subprocess.CalledProcessError(1,['private'],stderr=private)):
            with self.assertRaisesRegex(RuntimeError,'^Waiting fixture lifecycle failed: invalid-release-marker$'):check_lifecycle(['private'])
    def test_deadline_and_success_are_bounded(self):
        with patch('build_waiting_fixture.subprocess.run') as run:
            check_lifecycle(['java']);run.assert_called_once_with(['java'],check=True,timeout=10,capture_output=True)
        with patch('build_waiting_fixture.subprocess.run',side_effect=subprocess.TimeoutExpired('private',10)):
            with self.assertRaisesRegex(RuntimeError,'process-deadline'):check_lifecycle(['private'])

if __name__=='__main__':unittest.main()
