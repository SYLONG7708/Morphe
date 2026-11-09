import unittest
from unittest.mock import patch
from subprocess import CompletedProcess
from ci_retry import retryable, run


class RetryPolicyTest(unittest.TestCase):
    def test_temporary_network_failures_are_retryable(self):
        for text in ("HTTP 503", "Connection reset", "Could not GET artifact", "Connection timed out"):
            self.assertTrue(retryable(text), text)

    def test_curl_temporary_http_errors_are_retryable(self):
        for status in (429, 500, 502, 503, 504):
            with self.subTest(status=status):
                self.assertTrue(retryable(f"curl: (22) The requested URL returned error: {status}\n"))

    def test_curl_client_and_unknown_http_errors_are_not_retryable(self):
        for status in (400, 401, 403, 404, 410, 422, 501, 505, 5040):
            with self.subTest(status=status):
                self.assertFalse(retryable(f"curl: (22) The requested URL returned error: {status}\n"))

    def test_curl_authentication_errors_override_transient_errors(self):
        for status in (401, 403):
            with self.subTest(status=status):
                self.assertFalse(retryable(
                    f"HTTP 503\ncurl: (22) The requested URL returned error: {status}\n"))

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
    def test_curl_failure_keeps_the_three_attempt_budget_and_nonzero_exit(self, process, sleep):
        process.return_value = CompletedProcess([], 22, "curl: (22) The requested URL returned error: 504\n")
        self.assertEqual(22, run(["curl", "--fail", "https://example.invalid/patches.mpp"]))
        self.assertEqual(3, process.call_count)
        self.assertEqual([10, 20], [call.args[0] for call in sleep.call_args_list])

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
