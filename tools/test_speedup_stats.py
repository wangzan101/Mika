import unittest
from speedup_stats import summarize


class SpeedupStatsTest(unittest.TestCase):
    def test_absent_cache_fields_do_not_dilute_hit_rate(self):
        events = [
            {"type": "task_started", "atMs": 100},
            {"type": "model_reply", "inputTokens": 100, "cachedTokens": 50, "cacheReported": True, "latencyMs": 10},
            {"type": "model_reply", "inputTokens": 100, "cachedTokens": 0, "cacheReported": True, "latencyMs": 20},
            {"type": "model_reply", "inputTokens": 1000, "cacheReported": False},
            {"type": "model_reply", "inputTokens": 1000},
            {"type": "task_ended", "atMs": 300},
        ]
        result = summarize(events)
        self.assertEqual(2, result["cache_reported_calls"])
        self.assertEqual(2, result["cache_unreported_calls"])
        self.assertEqual(.25, result["cache_hit_rate"])
        self.assertEqual(15, result["model_latency"]["median_ms"])
        self.assertEqual(200, result["wall_clock_ms"])

    def test_old_or_incomplete_exports_do_not_claim_zero_latency_or_cache(self):
        result = summarize([{"type": "model_reply"}])
        self.assertIsNone(result["cache_hit_rate"])
        self.assertIsNone(result["model_latency"]["median_ms"])
        self.assertIsNone(result["wall_clock_ms"])


if __name__ == "__main__":
    unittest.main()