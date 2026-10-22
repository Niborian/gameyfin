"""Inactive controller-only public-policy lease for an existing owned UI fixture."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts' / 'browser-benchmark'))
from owned_app import OwnedApp, hosted_only
from public_image_ui import IMAGE, SOURCE

KEY = 'security.allow-public-access'
EXPECTED_SOURCE = '21cce95a6ea0b473ca096f6ae26a372f651baa1e'
EXPECTED_IMAGE = 'sha256:9ec1195171a00faf9715b3ef9128e5f0cd0be8042d8918907f28edeab00a7d08'


class OwnedPublicPolicy:
    """Restore effective policy; never accept operator endpoints or credentials."""
    def __init__(self, app):
        if SOURCE != EXPECTED_SOURCE or IMAGE != EXPECTED_IMAGE or not isinstance(app, OwnedApp) or app.ui_only is not True or app.image != IMAGE:
            raise ValueError('Exact owned retained-image UI fixture required')
        self.app, self.original, self.changed = app, None, False
        self.effective_policy_restored = False

    def _check(self):
        hosted_only()
        row = self.app.inspect()  # Existing exact ID/owner/image/mount/network checks.
        if row['State'].get('Running') is not True:
            raise ValueError('Owned application must remain running')

    def _read(self):
        self._check()
        rows = self.app.client.request('/connect/ConfigEndpoint/getAll', {})
        if not isinstance(rows, list) or len(rows) > 512:
            raise ValueError('Bounded fixture policy response required')
        matches = [row for row in rows if isinstance(row, dict) and row.get('key') == KEY]
        if len(matches) != 1 or type(matches[0].get('value')) is not bool:
            raise ValueError('Exact effective public policy required')
        # Return only one non-secret boolean; do not retain/export full configuration.
        return matches[0]['value']

    def _write(self, value):
        if type(value) is not bool:
            raise ValueError('Boolean public policy only')
        self._check()
        self.app.client.request('/connect/ConfigEndpoint/update', {'update': {'updates': {KEY: value}}})

    def __enter__(self):
        self.original = self._read()
        self.changed = True  # Record restoration duty BEFORE attempting the mutation.
        try:
            self._write(True)
            if self._read() is not True:
                raise ValueError('Public policy enablement not verified')
        except BaseException:
            self.restore()
            raise
        return self

    def restore(self):
        if self.changed:
            self._write(self.original)
            if self._read() != self.original:
                raise ValueError('Effective public policy restoration unverified')
            self.changed = False
            self.effective_policy_restored = True

    def __exit__(self, *_):
        self.restore()  # Failure is fatal; never claim restoration on an uncertain reply.

    def receipt(self):
        return {'effectivePublicPolicyRestored': self.effective_policy_restored,
                'fixtureOnly': True, 'retainedArtifactSourceMatches': SOURCE == '21cce95a6ea0b473ca096f6ae26a372f651baa1e'}
