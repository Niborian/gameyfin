import tempfile,time,threading,unittest,os
from pathlib import Path
from waiting_overlap import run_waiting_overlap,receipt,exact_root

class WaitingOverlapTest(unittest.TestCase):
    def test_actual_causal_order_and_independent_java_clock(self):
        with tempfile.TemporaryDirectory() as parent:
            root=Path(parent)/'gameyfin-overlap-fixture-0000000000000001';root.mkdir()
            worker=None;stop=threading.Event()
            def marker(name,value):
                pending=root/(name+'.pending');pending.write_text(value)
                os.link(pending,root/name,follow_symlinks=False);pending.unlink()
            def trigger():
                nonlocal worker
                def run():
                    marker('entered','waiting-provider-v1\n-300\n')
                    end=time.monotonic()+1
                    while not stop.is_set() and not (root/'release').exists() and time.monotonic()<end:time.sleep(.001)
                    if stop.is_set():return
                    marker('released','waiting-provider-v1\n-200\n')
                    marker('exited','waiting-provider-v1\n-100\n')
                worker=threading.Thread(target=run);worker.start()
            try:result=run_waiting_overlap(root,trigger,lambda:{'rendered':True})
            finally:
                stop.set()
                if worker:worker.join(1.5)
            self.assertFalse(worker.is_alive())
            self.assertTrue(result['workerLifetimeOverlapVerified'])
            self.assertEqual(result['providerNanoTime']['entered'],-300)
            with self.assertRaises(ValueError):run_waiting_overlap(root,trigger,lambda:None)
    def test_failed_browser_still_releases(self):
        with tempfile.TemporaryDirectory() as parent:
            root=Path(parent)/'gameyfin-overlap-fixture-0000000000000002';root.mkdir()
            def trigger():(root/'entered').write_text('waiting-provider-v1\n1\n')
            def fail():raise RuntimeError('callback test failure')
            with self.assertRaises(RuntimeError):run_waiting_overlap(root,trigger,fail)
            self.assertEqual((root/'release').read_bytes(),b'release\n')
    def test_expired_provider_is_not_overlap(self):
        with tempfile.TemporaryDirectory() as parent:
            root=Path(parent)/'gameyfin-overlap-fixture-0000000000000003';root.mkdir()
            def trigger():
                (root/'entered').write_text('waiting-provider-v1\n1\n')
                (root/'exited').write_text('waiting-provider-v1\n2\n')
            with self.assertRaises(RuntimeError):run_waiting_overlap(root,trigger,lambda:None)
    def test_invalid_receipt_and_wrong_leaf(self):
        with tempfile.TemporaryDirectory() as parent:
            with self.assertRaises(ValueError):exact_root(parent)
            root=Path(parent)/'gameyfin-overlap-fixture-0000000000000004';root.mkdir()
            (root/'entered').write_bytes(b'x'*129)
            with self.assertRaises(ValueError):receipt(root,'entered')
            with self.assertRaises(ValueError):receipt(root,'untrusted')
if __name__=='__main__':unittest.main()
