import unittest
from unittest.mock import patch
from subprocess import CompletedProcess
from ci_retry import retryable, run


class RetryPolicyTest(unittest.TestCase):
    def test_temporary_network_failures_are_retryable(self):
        for text in ("HTTP 503", "Connection reset", "Could not GET artifact", "Connection timed out"):
            self.assertTrue(retryable(text), text)

    def test_real_faults_are_not_hidden_by_network_messages(self):
        for text in ("signature verification failed", "hash mismatch", "Compilation error",
                     "Failed to find package 'tools'", "Patch requires morphe-patcher 9.0"):
            self.assertFalse(retryable("HTTP 503\n" + text), text)

    @patch("ci_retry.time.sleep")
    @patch("ci_retry.subprocess.run")
    def test_recovers_after_temporary_failure(self, process, sleep):
        process.side_effect = [CompletedProcess([], 1, "HTTP 503"), CompletedProcess([], 0, "OK")]
        self.assertEqual(0, run(["test"]))
        self.assertEqual(2, process.call_count)
        sleep.assert_called_once_with(10)

    @patch("ci_retry.time.sleep")
    @patch("ci_retry.subprocess.run")
    def test_retry_budget_is_bounded(self, process, sleep):
        process.return_value = CompletedProcess([], 1, "HTTP 503")
        self.assertEqual(1, run(["test"]))
        self.assertEqual(3, process.call_count)

    @patch("ci_retry.subprocess.run")
    def test_permanent_failure_runs_once(self, process):
        process.return_value = CompletedProcess([], 1, "2 tests failed")
        self.assertEqual(1, run(["test"]))
        self.assertEqual(1, process.call_count)
