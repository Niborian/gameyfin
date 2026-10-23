"""Pure exact binding tests only; no network, Docker, image load or app build."""
import json, unittest
from pathlib import Path
from unittest.mock import patch
import public_image_ui as subject

class SourceBindingTest(unittest.TestCase):
    def test_actual_pinned_manifest_and_equivalent_commit_trees(self):
        def api(path):
            sha=path.rsplit('/',1)[1]
            return {'sha':sha,'tree':{'sha':subject.REVIEWED_TREE}}
        with patch.object(subject,'api',api):
            self.assertEqual(subject.SOURCE,subject.source_binding()['reviewedHead'])

    def test_different_checkout_tree_fails_closed(self):
        def api(path):
            sha=path.rsplit('/',1)[1]
            return {'sha':sha,'tree':{'sha':'0'*40 if sha==subject.CHECKOUT else subject.REVIEWED_TREE}}
        with patch.object(subject,'api',api),self.assertRaisesRegex(ValueError,'tree mismatch'):
            subject.source_binding()

    def test_altered_manifest_does_not_reach_api(self):
        original=json.loads(Path(subject.__file__).with_name('public_image_source_binding.json').read_text())
        for change in [{'reviewedHead':'0'*40},{'imageJobId':1},{'extra':'forbidden'}]:
            with patch.object(subject,'read_regular',return_value=json.dumps({**original,**change})),patch.object(subject,'api') as api,self.assertRaisesRegex(ValueError,'Exact reviewed'):
                subject.source_binding()
            api.assert_not_called()

if __name__=='__main__':unittest.main()
