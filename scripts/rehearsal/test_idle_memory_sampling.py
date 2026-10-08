"""Natural-settling sampler contract; no real waiting or containers."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("image_scan", Path(__file__).with_name("run-image-scan.py"))
image_scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image_scan)


class IdleSamplingTest(unittest.TestCase):
    def test_five_and_fifteen_minute_windows_keep_distinct_memory_metrics(self):
        for duration in (300, 900):
            with self.subTest(duration=duration):
                elapsed = [0]
                def pause(seconds):
                    elapsed[0] += seconds
                report = image_scan.sample_post_task_idle(duration, 15,
                    lambda: {"heapUsedBytes": 100, "jvmRssBytes": 200,
                        "containerCgroupCurrentBytes": 300, "containerAnonymousBytes": 180,
                        "containerFileCacheBytes": 100}, lambda: elapsed[0], pause)
                self.assertEqual(report["observedSeconds"], duration)
                self.assertEqual(report["samples"][0]["elapsedSeconds"], 0)
                self.assertEqual(report["samples"][-1]["elapsedSeconds"], duration)
                self.assertEqual(len(report["samples"]), duration // 15 + 1)
                self.assertEqual(report["samples"][-1]["heapUsedBytes"], 100)
                self.assertEqual(report["samples"][-1]["jvmRssBytes"], 200)
                self.assertEqual(report["samples"][-1]["containerCgroupCurrentBytes"], 300)
                self.assertTrue(report["targetsAreNotLimits"])

    def test_nondivisible_window_samples_exact_final_checkpoint(self):
        elapsed = [0]
        def pause(seconds):
            elapsed[0] += seconds
        report = image_scan.sample_post_task_idle(31, 15, lambda: {}, lambda: elapsed[0], pause)
        self.assertEqual([sample["elapsedSeconds"] for sample in report["samples"]], [0, 15, 30, 31])

    def test_invalid_windows_rejected(self):
        for duration, interval in ((0, 15), (901, 15), (300, 0), (5, 15)):
            with self.assertRaises(ValueError):
                image_scan.sample_post_task_idle(duration, interval, lambda: {})

    def test_measurement_failure_not_fabricated(self):
        def fail():
            raise RuntimeError("metric unavailable")
        with self.assertRaisesRegex(RuntimeError, "unavailable"):
            image_scan.sample_post_task_idle(300, 15, fail)


if __name__ == "__main__":
    unittest.main()
