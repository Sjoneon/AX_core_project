import importlib.util
import unittest
from datetime import datetime, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location('report_metrics', Path(__file__).resolve().parents[1] / 'report-metrics.py')
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class MonitoringTests(unittest.TestCase):
    def test_memory_accounts_for_available_reclaimable_cache(self):
        value = metrics.memory_used_percent('MemTotal: 2000 kB\nMemFree: 100 kB\nMemAvailable: 1500 kB\n')
        self.assertEqual(value, 25)

    def test_certificate_expiry_uses_utc_and_can_report_expired_certificates(self):
        date = datetime(2026, 10, 13, 1, 29, 8, tzinfo=timezone.utc)
        self.assertEqual(metrics.certificate_hours('notAfter=Oct 19 01:29:08 2026 GMT\n', date), 144)
        self.assertEqual(metrics.certificate_hours('notAfter=Oct 12 01:29:08 2026 GMT\n', date), -24)


if __name__ == '__main__':
    unittest.main()
