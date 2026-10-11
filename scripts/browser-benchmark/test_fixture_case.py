import tempfile,unittest
from pathlib import Path
from fixture_case import FixtureClient,SameOriginRedirect,generate_fixture,verify_sources

class FixtureCaseTest(unittest.TestCase):
    def test_exact_generated_source_and_cover_contract(self):
        with tempfile.TemporaryDirectory(prefix='benchmark-local-') as parent:
            output=Path(parent)/'fixture';fixture=generate_fixture(output)
            self.assertEqual(fixture['manifest']['sourceFileCount'],924)
            self.assertEqual(len(fixture['manifest']['games']),132)
            self.assertEqual(fixture['manifestSha256'],'817907dc520075c045d334e537504a086015f1de59bc21ee1585d9c181b276b4')
            self.assertTrue(verify_sources(output,fixture['manifest'])['sourceFilesUnchanged'])
            self.assertEqual((output/'images/cover.png').stat().st_size,371528)
            sql=(output/'seed.sql').read_text()
            self.assertEqual(sql.count('INSERT INTO GAME_ORIGINAL_IDS'),132)
            (output/'sources/lib1/game-000/base-a.bin').write_bytes(b'changed')
            with self.assertRaises(ValueError):verify_sources(output,fixture['manifest'])
    def test_private_ipv4_only_no_redirect_escape(self):
        for target in ('127.0.0.1','169.254.1.1','8.8.8.8','host.example','::1'):
            with self.assertRaises(ValueError):FixtureClient(target)
        client=FixtureClient('172.18.0.2')
        self.assertEqual(client.base,'http://172.18.0.2:8080')
        for url in ('https://172.18.0.2:8080/','http://172.18.0.2:8081/','http://example.invalid/'):
            with self.assertRaises(ValueError):SameOriginRedirect(client.base).redirect_request(None,None,302,None,None,url)
    def test_path_bound_and_password_not_accepting_production_input(self):
        client=FixtureClient('172.18.0.2')
        for path in ('//external.invalid/','x','/x\\y'):
            with self.assertRaises(ValueError):client.raw(path)
        with self.assertRaises(ValueError):client.login('short')
if __name__=='__main__':unittest.main()
