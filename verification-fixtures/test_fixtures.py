import json
import hashlib
import unittest
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from pathlib import Path
from fixtures import ROOT, build, build_recovery, score, FixtureHandler


class FrameworkTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT / "corpus" / "manifest.json").read_text())

    def test_exact_same_100_unique_independently_labelled_cases(self):
        cases = self.manifest["cases"]
        self.assertEqual(100, len(cases))
        self.assertEqual(100, len({c["id"] for c in cases}))
        self.assertEqual({"positive": 70, "negative": 20, "unknown": 10},
                         {label: sum(c["label"] == label for c in cases)
                          for label in ["positive", "negative", "unknown"]})

    def test_rebuild_is_deterministic(self):
        self.assertEqual(self.manifest, build(ROOT / "corpus"))

    def test_corpus_integrity(self):
        for case in self.manifest["cases"]:
            data = (ROOT / "corpus" / case["path"].lstrip("/")).read_bytes()
            self.assertEqual(case["sha256"], hashlib.sha256(data).hexdigest())

    def test_unrun_is_not_pass(self):
        self.assertEqual(100, score(self.manifest, [])["unrun"])

    def test_recovery_has_frozen_origins_and_deleted_target_negatives(self):
        recovery = json.loads((ROOT / "corpus" / "recovery" / "manifest.json").read_text())
        self.assertEqual(80, len(recovery["cases"]))
        self.assertEqual(20, len({c["original_id"] for c in recovery["cases"]}))
        original_hashes = {c["id"]: c["sha256"] for c in self.manifest["cases"]}
        for case in recovery["cases"]:
            self.assertEqual(original_hashes[case["original_id"]], case["original_sha256"])
            data = (ROOT / "corpus" / case["path"].lstrip("/")).read_bytes()
            self.assertEqual(case["sha256"], hashlib.sha256(data).hexdigest())
            if case["mutation"] == "target-deleted":
                self.assertEqual("negative", case["label"])
                self.assertNotIn(b"<video", data)
        self.assertEqual(recovery, build_recovery(ROOT / "corpus" / "recovery", self.manifest))

    def test_failures_never_confirm_video(self):
        for status in ["cancelled", "timeout", "error", "denied", "rate_limited", "unavailable"]:
            with self.assertRaises(ValueError):
                score(self.manifest, [{"id": "static-00", "status": status,
                                      "prediction": "positive", "evidence": "verified_media"}])

    def test_candidate_is_not_confirmation(self):
        with self.assertRaises(ValueError):
            score(self.manifest, [{"id": "negative-00", "status": "success",
                                  "prediction": "positive", "evidence": "candidate"}])

    def test_unknown_labels_not_used_as_accuracy(self):
        result = score(self.manifest, [{"id": "unknown-00", "status": "denied",
                                       "prediction": "unknown"}])
        self.assertEqual(1, result["unknown_ground_truth"])
        self.assertEqual(0, result["correct_negative"])

    def test_duplicate_is_rejected(self):
        row = {"id": "static-00", "status": "success", "prediction": "unknown"}
        with self.assertRaises(ValueError):
            score(self.manifest, [row, row])

    def test_false_positive_is_counted(self):
        result = score(self.manifest, [{"id": "negative-00", "status": "success",
                                       "prediction": "positive", "evidence": "verified_media"}])
        self.assertEqual(1, result["false_positive"])

    def test_missing_extraction_measurement_remains_unknown(self):
        result = score(self.manifest, [{"id": "static-00", "status": "success",
                                       "prediction": "positive", "evidence": "verified_media"}])
        self.assertIsNone(result["resource_extraction_rate"])
        self.assertEqual(0, result["extraction_measurement_coverage"])

    def test_loopback_http_success_and_denial(self):
        with ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                with urllib.request.urlopen(base + "/static-00.html", timeout=2) as response:
                    self.assertEqual(200, response.status)
                    self.assertIn(b"<video", response.read())
                for path in ["/unknown-00.html", "/"]:
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(base + path, timeout=2)
                    self.assertEqual(403, error.exception.code)
                request = urllib.request.Request(base + "/static-00.html", headers={"Host": "example.com"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request, timeout=2)
                self.assertEqual(403, error.exception.code)
            finally:
                server.shutdown()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
