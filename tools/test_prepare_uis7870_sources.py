import tempfile
import unittest
from pathlib import Path

from prepare_uis7870_sources import load_profile, prepare_sources


class PrepareUis7870SourcesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.profile_path = self.root / "safe.properties"
        self.profile_path.write_text(
            "\n".join(
                (
                    "managerPackage=com.sylong.autopatchhub",
                    "vendorGroup=com.sylong.autopatch",
                    "patchedYouTubePackage=com.sylong.autopatch.android.youtube",
                    "microgPackage=com.sylong.autopatch.android.gms",
                    "profileRevision=1",
                )
            ),
            encoding="utf-8",
        )
        self.patches = self.root / "patches"
        self.microg = self.root / "microg"
        gms = (
            self.patches
            / "patches/src/main/kotlin/app/morphe/patches/shared/misc/gms"
        )
        gms.mkdir(parents=True)
        (gms / "GmsCoreSupportPatch.kt").write_text(
            '\n'.join(
                (
                    'internal const val GMS_CORE_VENDOR_GROUP_ID = "app.revanced"',
                    'setAttribute("android:name", "app.revanced.MICROG_PACKAGE_NAME")',
                )
            ),
            encoding="utf-8",
        )
        youtube_gms = (
            self.patches
            / "patches/src/main/kotlin/app/morphe/patches/youtube/misc/gms"
        )
        youtube_gms.mkdir(parents=True)
        (youtube_gms / "Constants.kt").write_text(
            'const val MORPHE_YOUTUBE_PACKAGE_NAME = "app.morphe.android.youtube"\n',
            encoding="utf-8",
        )
        (self.patches / "gradle.properties").write_text(
            "version = 1.37.0\n", encoding="utf-8"
        )
        (self.patches / "patches/build.gradle.kts").parent.mkdir(exist_ok=True)
        (self.patches / "patches/build.gradle.kts").write_text(
            "\n".join(
                (
                    'name = "Morphe Patches"',
                    'description = "Patches for Morphe"',
                    'source = "git@github.com:MorpheApp/morphe-patches.git"',
                )
            ),
            encoding="utf-8",
        )
        self.microg.mkdir(parents=True)
        (self.microg / "build.gradle").write_text(
            '\n'.join(
                (
                    'ext.basePackageName = "app.revanced"',
                    "ext.appVersionName = '6.1.4'",
                    "ext.appVersionCode = 255034004",
                )
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_prepares_matching_vendor_group_and_versions(self) -> None:
        profile = load_profile(self.profile_path)
        patch_version, microg_version = prepare_sources(
            self.patches,
            self.microg,
            profile,
            "1.37.0",
            "6.1.4",
            260727001,
            vendor_override="com.sylong.autopatch.test",
        )

        self.assertEqual("1.37.0.7870.1", patch_version)
        self.assertEqual("6.1.4-7870.1", microg_version)
        gms_text = next(self.patches.rglob("GmsCoreSupportPatch.kt")).read_text()
        self.assertIn('"com.sylong.autopatch.test"', gms_text)
        self.assertNotIn('"app.revanced.MICROG_PACKAGE_NAME"', gms_text)
        youtube_constants = (
            self.patches
            / "patches/src/main/kotlin/app/morphe/patches/youtube/misc/gms/Constants.kt"
        ).read_text()
        self.assertIn(
            '"com.sylong.autopatch.test.android.youtube"',
            youtube_constants,
        )
        self.assertNotIn('"app.morphe.android.youtube"', youtube_constants)
        microg_text = (self.microg / "build.gradle").read_text()
        self.assertIn('ext.basePackageName = "com.sylong.autopatch.test"', microg_text)
        self.assertIn("ext.appVersionCode = 260727001", microg_text)

    def test_rejects_mismatched_profile_packages(self) -> None:
        self.profile_path.write_text(
            self.profile_path.read_text().replace(
                "microgPackage=com.sylong.autopatch.android.gms",
                "microgPackage=app.revanced.android.gms",
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "microgPackage"):
            load_profile(self.profile_path)


if __name__ == "__main__":
    unittest.main()
