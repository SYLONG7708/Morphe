import unittest

from generate_update_manifest import min_sdk_from_badging


class MinSdkBadgingTest(unittest.TestCase):
    def test_reads_aapt_format(self) -> None:
        self.assertEqual(26, min_sdk_from_badging("sdkVersion:'26'\n"))

    def test_reads_aapt2_format(self) -> None:
        self.assertEqual(24, min_sdk_from_badging("minSdkVersion:'24'\n"))

    def test_rejects_missing_minimum_sdk(self) -> None:
        with self.assertRaisesRegex(ValueError, "minimum SDK"):
            min_sdk_from_badging("targetSdkVersion:'37'\n")


if __name__ == "__main__":
    unittest.main()
