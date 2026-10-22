import tempfile
import unittest
import zipfile
from pathlib import Path

from check_patch_bundle_compat import assert_compatible


class PatchBundleCompatibilityTest(unittest.TestCase):
    def create_inputs(
        self,
        required: str,
        available: str,
    ) -> tuple[Path, Path, tempfile.TemporaryDirectory]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        bundle = root / "patches.mpp"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                f"Manifest-Version: 1.0\r\nPatcher-Version: {required}\r\n\r\n",
            )
        catalog = root / "libs.versions.toml"
        catalog.write_text(
            f'[versions]\nmorphe-patcher = "{available}"\n',
            encoding="utf-8",
        )
        return bundle, catalog, temp

    def test_equal_versions_are_compatible(self) -> None:
        bundle, catalog, temp = self.create_inputs("1.7.0", "1.7.0")
        self.addCleanup(temp.cleanup)
        self.assertEqual(assert_compatible(bundle, catalog), ("1.7.0", "1.7.0"))

    def test_older_bundle_is_compatible(self) -> None:
        bundle, catalog, temp = self.create_inputs("1.6.0", "1.7.0")
        self.addCleanup(temp.cleanup)
        self.assertEqual(assert_compatible(bundle, catalog), ("1.6.0", "1.7.0"))

    def test_newer_bundle_is_rejected(self) -> None:
        bundle, catalog, temp = self.create_inputs("1.8.0", "1.7.0")
        self.addCleanup(temp.cleanup)
        with self.assertRaisesRegex(RuntimeError, "requires morphe-patcher 1.8.0"):
            assert_compatible(bundle, catalog)

    def test_patcher_2_bundle_is_rejected_by_patcher_1_manager(self) -> None:
        bundle, catalog, temp = self.create_inputs("2.0.0", "1.15.1")
        self.addCleanup(temp.cleanup)
        with self.assertRaisesRegex(RuntimeError, "requires morphe-patcher 2.0.0"):
            assert_compatible(bundle, catalog)

    def test_release_catalog_supports_current_and_previous_bundle_api(self) -> None:
        catalog = Path(__file__).resolve().parents[1] / "gradle/libs.versions.toml"
        # Published 1.47.0.7870.2 and official 1.48.0 respectively.
        for required in ("1.14.0", "2.0.0"):
            with self.subTest(required=required):
                bundle, _, temp = self.create_inputs(required, "1.15.1")
                self.addCleanup(temp.cleanup)
                self.assertEqual(assert_compatible(bundle, catalog)[0], required)


if __name__ == "__main__":
    unittest.main()
