import subprocess
import unittest
from unittest.mock import Mock, patch

import ci_retry


MISSING = "   > Could not find org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22.\n"


class GradleRecoveryTest(unittest.TestCase):
    def test_exact_published_coordinates_are_checked(self):
        probe = Mock(return_value=True)
        self.assertTrue(ci_retry.published_dependencies_available(MISSING * 2, probe))
        probe.assert_called_once_with("org.jetbrains.kotlin", "kotlin-stdlib-common", "1.9.22")

    def test_true_missing_versions_unknown_formats_and_code_errors_do_not_retry(self):
        for text in (MISSING, "Could not find private/library:unknown.\n",
                     MISSING + "Compilation error", MISSING + "signature verification failed"):
            self.assertFalse(ci_retry.published_dependencies_available(text, lambda *args: False))
        probe = Mock(return_value=True)
        self.assertFalse(ci_retry.published_dependencies_available(MISSING + "Compilation error", probe))
        probe.assert_not_called()

    def test_all_missing_coordinates_must_exist(self):
        text = MISSING + "Could not find org.example:missing:1.0.\n"
        self.assertFalse(ci_retry.published_dependencies_available(text, lambda g, a, v: g != "org.example"))

    def test_retry_refreshes_negative_cache_and_keeps_build_tasks(self):
        first = subprocess.CompletedProcess([], 1, MISSING)
        second = subprocess.CompletedProcess([], 0, "BUILD SUCCESSFUL\n")
        with patch.object(ci_retry.subprocess, "run", side_effect=[first, second]) as runner, \
                patch.object(ci_retry, "central_artifact_exists", return_value=True), \
                patch.object(ci_retry.time, "sleep"):
            self.assertEqual(0, ci_retry.run(["./gradlew", "assembleRelease", "--configuration-cache"]))
        self.assertEqual(["./gradlew", "assembleRelease", "--refresh-dependencies", "--no-configuration-cache"],
                         runner.call_args_list[1].args[0])

    def test_failed_refresh_is_bounded_and_still_fails(self):
        with patch.object(ci_retry.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, MISSING)) as runner, \
                patch.object(ci_retry, "central_artifact_exists", return_value=True), \
                patch.object(ci_retry.time, "sleep"):
            self.assertEqual(1, ci_retry.run(["./gradlew", "assembleRelease"]))
        self.assertEqual(3, runner.call_count)

    def test_offline_and_non_gradle_commands_are_not_rewritten(self):
        self.assertIsNone(ci_retry.refreshed_gradle_command(["python", "build.py"]))
        self.assertIsNone(ci_retry.refreshed_gradle_command(["./gradlew", "--offline"]))
        command = ["gradlew.bat", "test", "--refresh-dependencies", "--no-configuration-cache"]
        self.assertEqual(command, ci_retry.refreshed_gradle_command(command))


if __name__ == "__main__":
    unittest.main()
