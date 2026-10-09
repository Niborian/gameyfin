"""Natural-settling sampler contract; no real waiting or containers."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("image_scan", Path(__file__).with_name("run-image-scan.py"))
image_scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image_scan)


class IdleSamplingTest(unittest.TestCase):
    def test_idle_requires_coordinator_and_draining_worker_meters_zero(self):
        for active, draining in ((1, 0), (0, 1), (1, 1)):
            with self.subTest(active=active, draining=draining):
                values = {"gameyfin_scans_active": active, "gameyfin_scans_draining": draining}
                with self.assertRaisesRegex(RuntimeError, "active or draining"):
                    image_scan.require_scan_quiescence(lambda name, text: values[name], "snapshot")

    def test_idle_does_not_default_missing_draining_meter_to_zero(self):
        def metric(name, text):
            if name == "gameyfin_scans_active":
                return 0
            raise RuntimeError("Required synthetic telemetry meter absent: " + name)
        with self.assertRaisesRegex(RuntimeError, "meter absent: gameyfin_scans_draining"):
            image_scan.require_scan_quiescence(metric, "snapshot")

    def test_idle_accepts_both_mandatory_meters_zero_from_same_snapshot(self):
        observed = []
        def metric(name, text):
            observed.append((name, text))
            return 0
        image_scan.require_scan_quiescence(metric, "one-snapshot")
        self.assertEqual(observed, [("gameyfin_scans_active", "one-snapshot"),
                                   ("gameyfin_scans_draining", "one-snapshot")])

    def test_option_probe_runs_as_jvm_owner_and_emits_only_fixed_key(self):
        with patch.object(image_scan.subprocess, "check_output", return_value="-Xms128m -Xmx512m\n") as output:
            self.assertEqual(image_scan.observe_jvm_options(["sudo", "-n", "docker"], "owned-fixture", "1337", "1337"),
                ["-Xms128m -Xmx512m"])
        command = output.call_args.args[0]
        self.assertEqual(command[:9], ["sudo", "-n", "docker", "exec", "--user", "1337:1337", "owned-fixture", "sh", "-c"])
        self.assertIn('sed -n "s/^JDK_JAVA_OPTIONS=//p"', command[-1])
        self.assertNotIn("APP_KEY", command[-1])
        self.assertNotIn("privileged", command)
        self.assertEqual(output.call_args.kwargs, {"text": True, "timeout": 10})

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
