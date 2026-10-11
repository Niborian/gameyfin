import unittest
from bind_built_images import bind
from verify_source_pair import CONTROL,CANDIDATE

class BindingTests(unittest.TestCase):
    def test_exact_sources_and_parity_only(self):
        receipt={'controlSource':CONTROL,'candidateSource':CANDIDATE,'entryByteParity':True,'normalizationPerformed':False,'differingResourceEntries':[],'controlJarSha256':'a'*64,'candidateJarSha256':'b'*64}
        rows=[{'Id':'sha256:'+n*64,'Config':{'Labels':{'org.opencontainers.image.revision':source}}} for n,source in [('c',CONTROL),('d',CANDIDATE)]]
        self.assertEqual(bind(receipt,*rows)['candidateImageId'],rows[1]['Id'])
        for key,value in [('entryByteParity',False),('normalizationPerformed',True),('controlSource',CANDIDATE)]:
            with self.assertRaises(ValueError):bind({**receipt,key:value},*rows)
        with self.assertRaises(ValueError):bind(receipt,rows[1],rows[0])
if __name__=='__main__':unittest.main()
