import copy
import unittest
from cloud_recovery import decision


class CloudRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.repo = "SYLONG7708/Morphe"
        self.run = {"id": 123, "repository": {"full_name": self.repo},
                    "head_repository": {"full_name": self.repo}, "head_branch": "main",
                    "path": ".github/workflows/upstream-sync.yml", "event": "schedule",
                    "status": "completed", "conclusion": "failure", "head_sha": "a" * 40,
                    "run_attempt": 1}

    def choose(self, logs="HTTP 503", sha="a" * 40):
        return decision(self.run, self.repo, sha, logs)

    def test_sync_and_release_are_both_recovered(self):
        for path in (".github/workflows/upstream-sync.yml", ".github/workflows/release.yml"):
            self.run["path"] = path
            self.assertEqual("retry", self.choose()["action"])

    def test_maximum_three_total_attempts(self):
        for attempt in (1, 2, 3, 4):
            self.run["run_attempt"] = attempt
            self.assertEqual("retry" if attempt < 3 else "retain", self.choose()["action"])

    def test_merge_code_signature_and_test_failures_are_not_retried(self):
        for error in ("Upstream merge needs a code change", "Compilation error", "hash mismatch",
                      "signature verification failed", "2 tests failed", "e: file:///Home.kt: Unresolved reference",
                      "Reviewed resolution result hash mismatch", "Bad credentials"):
            self.assertEqual("retain", self.choose("HTTP 503\n" + error)["action"], error)

    def test_newer_main_is_not_replaced_with_obsolete_code(self):
        self.assertEqual("retain", self.choose(sha="b" * 40)["action"])

    def test_fork_pr_other_branch_and_unknown_workflow_are_rejected(self):
        original = copy.deepcopy(self.run)
        for key, value in (("head_repository", {"full_name": "external/fork"}), ("event", "pull_request"),
                           ("head_branch", "feature"), ("path", ".github/workflows/untrusted.yml")):
            self.run = copy.deepcopy(original)
            self.run[key] = value
            self.assertEqual("retain", self.choose()["action"])

    def test_success_cancelled_and_running_do_not_trigger_recovery(self):
        for conclusion in ("success", "cancelled", "skipped"):
            self.run["conclusion"] = conclusion
            self.assertEqual("retain", self.choose()["action"])
        self.run.update(conclusion="failure", status="in_progress")
        self.assertEqual("retain", self.choose()["action"])

    def test_runner_timeout_and_missing_startup_logs_can_retry(self):
        for conclusion in ("timed_out", "startup_failure"):
            self.run["conclusion"] = conclusion
            self.assertEqual("retry", self.choose(logs="")["action"])
