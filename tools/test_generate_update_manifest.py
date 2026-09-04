import unittest

from generate_update_manifest import manager_component, min_sdk_from_badging


class MinSdkBadgingTest(unittest.TestCase):
    def test_reads_aapt_format(self) -> None:
        self.assertEqual(26, min_sdk_from_badging("sdkVersion:'26'\n"))

    def test_reads_aapt2_format(self) -> None:
        self.assertEqual(24, min_sdk_from_badging("minSdkVersion:'24'\n"))

    def test_rejects_missing_minimum_sdk(self) -> None:
        with self.assertRaisesRegex(ValueError, "minimum SDK"):
            min_sdk_from_badging("targetSdkVersion:'37'\n")


class ManagerComponentTest(unittest.TestCase):
    def test_keeps_both_manager_editions(self) -> None:
        licensed = {
            "package_name": "com.sylong.symorphe",
            "version_name": "1.1.60",
            "version_code": 100,
        }
        no_license = {
            "package_name": "com.sylong.symorphe.nolicense",
            "version_name": "1.1.60-no-license",
            "version_code": 100,
        }

        component = manager_component([licensed, no_license])

        self.assertEqual("1.1.60", component["version"])
        self.assertEqual([licensed, no_license], component["artifacts"])

    def test_rejects_duplicate_manager_packages(self) -> None:
        manager = {
            "package_name": "com.sylong.symorphe",
            "version_name": "1.1.60",
            "version_code": 100,
        }
        with self.assertRaisesRegex(ValueError, "distinct package names"):
            manager_component([manager, manager.copy()])


if __name__ == "__main__":
    unittest.main()
