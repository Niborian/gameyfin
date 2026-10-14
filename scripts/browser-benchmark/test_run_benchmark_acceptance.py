import unittest
from run_benchmark import responsiveness_accepted

class GlobalAcceptanceTests(unittest.TestCase):
    def test_held_login_ux_failure_blocks_otherwise_clean_six_cases(self):
        reports=[{'report':{'cleanAcceptance':True}} for _ in range(6)]
        auth={'normalLoginAutoRedirectObserved':True,'fixtureProfileVerified':True}
        self.assertTrue(responsiveness_accepted(reports,auth))
        for evidence in (None,{},dict(auth,normalLoginAutoRedirectObserved=False),dict(auth,fixtureProfileVerified=False),dict(auth,normalLoginAutoRedirectObserved=1),dict(auth,raw='private')):
            self.assertFalse(responsiveness_accepted(reports,evidence))
        self.assertFalse(responsiveness_accepted(reports[:-1],auth))
        self.assertFalse(responsiveness_accepted([{'report':{'cleanAcceptance':False}},*reports[1:]],auth))

if __name__=='__main__':unittest.main()
