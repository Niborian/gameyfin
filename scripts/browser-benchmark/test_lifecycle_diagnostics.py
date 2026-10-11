import json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock
from owned_app import lifecycle_failure,OwnedApp
from fixture_case import route_category

class LifecycleDiagnostics(unittest.TestCase):
    def test_http_fixed_fields_only(self):
        error=RuntimeError('secret-password /private/path')
        error.fixture_http_status=403;error.fixture_route_category='enable-plugin'
        row=lifecycle_failure('admin-plugin-bootstrap',error,12.3456)
        self.assertEqual(row['httpStatus'],403)
        self.assertEqual(row['phase'],'admin-plugin-bootstrap')
        self.assertNotIn('secret',json.dumps(row));self.assertNotIn('/private',json.dumps(row))
    def test_unknown_metadata_rejected(self):
        error=RuntimeError('hidden');error.fixture_http_status=True
        error.fixture_route_category='/login?password=hidden'
        row=lifecycle_failure('hidden',error,0)
        self.assertIsNone(row['httpStatus']);self.assertEqual(row['phase'],'unknown')
        self.assertEqual(row['routeCategory'],'other')
        self.assertEqual(route_category('/arbitrary?secret=hidden'),'other')
    def test_failed_diagnostic_still_closes(self):
        app=OwnedApp.__new__(OwnedApp)
        app.capture_lifecycle_failure=Mock(side_effect=OSError('disk'))
        app.close=Mock()
        app.cleanup_initialization_failure(RuntimeError('original'))
        app.close.assert_called_once()
    def test_symlink_destination_not_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);target=base/'target';target.write_text('unchanged')
            (base/'app-lifecycle.json').symlink_to(target)
            app=OwnedApp.__new__(OwnedApp);app.diagnostic_output=base/'app.json'
            app.lifecycle_phase='seeded-login';app.lifecycle_started=0
            with self.assertRaises(ValueError):app.capture_lifecycle_failure(RuntimeError('private'))
            self.assertEqual(target.read_text(),'unchanged')
