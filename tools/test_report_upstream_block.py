import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from report_upstream_block import issue_details, main


class UpstreamBlockReportTest(unittest.TestCase):
    def test_issue_identifies_release_and_preserves_signed_version(self):
        marker, title, body = issue_details(
            {"state": "blocked", "tag": "v1.33.0", "commit": "a" * 40,
             "conflicts": ["app/src/main/java/Manager.kt"]}, "SYLONG7708/Morphe", "123")
        self.assertIn("v1.33.0", marker)
        self.assertIn("v1.33.0", title)
        self.assertIn("現有已簽章版本保留", body)
        self.assertIn("app/src/main/java/Manager.kt", body)
        self.assertIn("SYLONG7708/Morphe/actions/runs/123", body)

    def test_non_blocked_or_untrusted_release_is_rejected(self):
        for state, tag in (("current", "v1.33.0"), ("blocked", "latest")):
            with self.assertRaises(ValueError):
                issue_details({"state": state, "tag": tag, "commit": "a" * 40,
                               "conflicts": ["app.txt"]}, "SYLONG7708/Morphe", "123")

    def test_existing_release_issue_prevents_duplicate_notification(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "verification").mkdir()
            (root / "verification/upstream-sync.json").write_text(json.dumps({
                "state": "blocked", "tag": "v1.33.0", "commit": "a" * 40,
                "conflicts": ["app.txt"],
            }), encoding="utf-8")
            previous = Path.cwd()
            try:
                os.chdir(root)
                listed = subprocess.CompletedProcess([], 0, json.dumps([{
                    "number": 17, "body": "<!-- official-manager-sync:v1.33.0 -->",
                }]), "")
                with patch.dict(os.environ, {"GH_REPO": "SYLONG7708/Morphe", "GITHUB_RUN_ID": "123"}), \
                     patch("report_upstream_block.subprocess.run", return_value=listed) as run:
                    main()
                    self.assertEqual(1, run.call_count)
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
