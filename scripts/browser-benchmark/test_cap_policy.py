import unittest
from cap_policy import caps_match

class CapabilityTests(unittest.TestCase):
    def test_raw_and_docker28_canonical_spellings_same_exact_permission(self):
        for actual in [['SYS_ADMIN'],['CAP_SYS_ADMIN']]:self.assertTrue(caps_match(actual,['SYS_ADMIN']))
        self.assertTrue(caps_match(['CAP_CHOWN','CAP_DAC_READ_SEARCH'],['CHOWN','DAC_READ_SEARCH']))
        self.assertTrue(caps_match(['ALL'],['ALL']))
        self.assertTrue(caps_match(None,[]))

    def test_extra_unknown_duplicate_and_malformed_never_accepted(self):
        for actual in [['SYS_ADMIN','NET_ADMIN'],['ALL'],['SYS_ADMIN','CAP_SYS_ADMIN'],['CAP_CAP_SYS_ADMIN'],['sys_admin'],['untrusted arbitrary text'],{},1]:
            self.assertFalse(caps_match(actual,['SYS_ADMIN']))
        self.assertFalse(caps_match(['CAP_ALL'],['ALL']))

if __name__=='__main__':unittest.main()
