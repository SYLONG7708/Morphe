import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

from derive_uis7870_patch_bundle import (
    GMS_CLASS,
    GMS_QUERIES_CLASS,
    GMS_EXTENSION,
    MANIFEST,
    YOUTUBE_GMS_CLASS,
    YOUTUBE_GMS_CONSTANTS_CLASS,
    derive_bundle,
    replace_manifest_once,
)
from check_patch_bundle_compat import required_patcher_version


def minimal_class(*values: bytes) -> bytes:
    pool = bytearray()
    for value in values:
        pool.append(1)
        pool.extend(struct.pack(">H", len(value)))
        pool.extend(value)
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x3d" + struct.pack(">H", len(values) + 1) + pool


class ManifestReplacementTest(unittest.TestCase):
    def test_replaces_only_complete_field_and_preserves_other_bytes(self):
        for newline in ("\n", "\r\n", "\r"):
            for trailing_newline in ("", newline):
                with self.subTest(newline=newline, trailing_newline=trailing_newline):
                    lines = [
                        "Manifest-Version: 1.0",
                        "Version: 2.0.0",
                        "Patcher-Version: 2.0.0",
                        "Notes: Version: 2.0.0",
                        "License: GNU General Public License v3.0, with additional GPL section 7 ",
                        " requirements",
                    ]
                    original = newline.join(lines) + trailing_newline
                    lines[1] = "Version: 2.0.0.7870.2"
                    self.assertEqual(
                        replace_manifest_once(original, "Version: 2.0.0", lines[1]),
                        newline.join(lines) + trailing_newline,
                    )

    def test_rejects_missing_changed_duplicate_or_continued_version(self):
        for manifest in (
            "Patcher-Version: 2.0.0\n",
            "Version: 2.0.01\n",
            "Version: 2.0.0-dev\n",
            "Version: 2.0.0\nVersion: 2.0.0\n",
            "Version: 2.0.0\nVersion: 2.0.1\n",
            "Version: 2.0.0\n -dev\n",
            "Notes: version follows\n Version: 2.0.0\n",
        ):
            with self.subTest(manifest=manifest):
                with self.assertRaises(ValueError):
                    replace_manifest_once(manifest, "Version: 2.0.0", "Version: 2.0.0.7870.2")


class DeriveUis7870PatchBundleTest(unittest.TestCase):
    def fixture_bundle(self, root, extra, *, version="1.47.0", patcher_version="1.14.0"):
        source = root / "official.mpp"
        with zipfile.ZipFile(source, "w") as bundle:
            bundle.writestr(GMS_CLASS, minimal_class(b"app.revanced", b"app.revanced.android.gms"))
            for name in (YOUTUBE_GMS_CLASS, YOUTUBE_GMS_CONSTANTS_CLASS):
                bundle.writestr(name, minimal_class(b"app.morphe.android.youtube"))
            bundle.writestr(MANIFEST, f"Name: Morphe Patches\nDescription: Patches for Morphe\nVersion: {version}\nSource: git@github.com:MorpheApp/morphe-patches.git\nPatcher-Version: {patcher_version}\n")
            bundle.writestr(GMS_EXTENSION, b"official-extension")
            for name, content in extra.items():
                bundle.writestr(name, content)
        return source

    @patch("derive_uis7870_patch_bundle.derive_runtime", return_value=b"derived-extension")
    def test_bundle_version_can_equal_required_patcher_version(self, runtime):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.fixture_bundle(root, {}, version="2.0.0", patcher_version="2.0.0")
            output = root / "derived.mpp"
            version = derive_bundle(
                source, output, "2.0.0",
                {"vendorGroup": "com.sylong.autopatch", "profileRevision": "2", "microgSignerSha256": "test-pin"},
            )
            self.assertEqual(version, "2.0.0.7870.2")
            self.assertEqual(required_patcher_version(output), "2.0.0")
            with zipfile.ZipFile(output) as bundle:
                self.assertEqual(bundle.read(MANIFEST).decode(),
                                 "Name: SyMorphe UIS7870 Patches\n"
                                 "Description: Morphe patches derived for safe UIS7870 coexistence\n"
                                 "Version: 2.0.0.7870.2\n"
                                 "Source: https://github.com/SYLONG7708/Morphe\n"
                                 "Patcher-Version: 2.0.0\n")

    @patch("derive_uis7870_patch_bundle.derive_runtime", return_value=b"derived-extension")
    def test_new_package_visibility_query_matches_derived_microg_and_dex(self, runtime):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.fixture_bundle(root, {
                GMS_QUERIES_CLASS: minimal_class(b"app.revanced.android.gms", b"com.mgoogle.android.gms", b"org.microg.gms"),
                "classes.dex": b"original-dex",
            })
            def rebuild(classes, d8):
                query = classes[GMS_QUERIES_CLASS]
                self.assertIn(b"com.sylong.autopatch.android.gms", query)
                self.assertIn(b"com.mgoogle.android.gms", query)
                self.assertIn(b"org.microg.gms", query)
                self.assertNotIn(b"app.revanced", query)
                return b"dex\ncom.sylong.autopatch\0com.sylong.autopatch.android.youtube\0"
            with patch("derive_uis7870_patch_bundle.rebuild_dex", side_effect=rebuild) as rebuild_mock:
                derive_bundle(source, root / "derived.mpp", "1.47.0",
                              {"vendorGroup": "com.sylong.autopatch", "profileRevision": "2", "microgSignerSha256": "test-pin"},
                              d8_path=root / "d8")
            rebuild_mock.assert_called_once()
            with zipfile.ZipFile(root / "derived.mpp") as bundle:
                self.assertIn(b"com.sylong.autopatch.android.gms", bundle.read(GMS_QUERIES_CLASS))

    @patch("derive_uis7870_patch_bundle.derive_runtime", return_value=b"derived-extension")
    def test_unreviewed_new_classes_remain_blocked_and_identified(self, runtime):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.fixture_bundle(root, {"unreviewed/NewPatch.class": minimal_class(b"app.revanced.android.gms")})
            with self.assertRaisesRegex(ValueError, "Unreviewed.*unreviewed/NewPatch.class"):
                derive_bundle(source, root / "derived.mpp", "1.47.0",
                              {"vendorGroup": "com.sylong.autopatch", "profileRevision": "2", "microgSignerSha256": "test-pin"})
            self.assertFalse((root / "derived.mpp").exists())

    @patch("derive_uis7870_patch_bundle.derive_runtime", return_value=b"derived-extension")
    def test_changed_query_structure_fails_closed(self, runtime):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.fixture_bundle(root, {GMS_QUERIES_CLASS: minimal_class(b"different.vendor.android.gms")})
            with self.assertRaisesRegex(ValueError, "Expected one GmsCore package query"):
                derive_bundle(source, root / "derived.mpp", "1.47.0",
                              {"vendorGroup": "com.sylong.autopatch", "profileRevision": "2", "microgSignerSha256": "test-pin"})

    @patch("derive_uis7870_patch_bundle.derive_runtime", return_value=b"derived-extension")
    def test_derives_vendor_group_and_manifest(self, runtime) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "official.mpp"
            output = root / "derived.mpp"
            manifest = "\r\n".join(
                (
                    "Manifest-Version: 1.0",
                    "Name: Morphe Patches",
                    "Description: Patches for Morphe",
                    "Version: 1.37.0",
                    "Source: git@github.com:MorpheApp/morphe-patches.git",
                    "",
                )
            )
            with zipfile.ZipFile(source, "w") as bundle:
                bundle.writestr(
                    GMS_CLASS,
                    minimal_class(
                        b"app.revanced",
                        b"app.revanced.android.gms",
                        b"app.revanced.MICROG_PACKAGE_NAME",
                    ),
                )
                bundle.writestr(
                    YOUTUBE_GMS_CLASS,
                    minimal_class(b"app.morphe.android.youtube"),
                )
                bundle.writestr(
                    YOUTUBE_GMS_CONSTANTS_CLASS,
                    minimal_class(b"app.morphe.android.youtube"),
                )
                bundle.writestr(MANIFEST, manifest)
                bundle.writestr(GMS_EXTENSION, b"official-extension")

            version = derive_bundle(
                source,
                output,
                "1.37.0",
                {"vendorGroup": "com.sylong.autopatch", "profileRevision": "1", "microgSignerSha256": "test-pin"},
                vendor_override="com.sylong.autopatch.test",
            )

            self.assertEqual("1.37.0.7870.1", version)
            runtime.assert_called_once_with(b"official-extension", "test-pin")
            with zipfile.ZipFile(output) as bundle:
                self.assertEqual(b"derived-extension", bundle.read(GMS_EXTENSION))
                class_bytes = bundle.read(GMS_CLASS)
                self.assertIn(b"com.sylong.autopatch.test.android.gms", class_bytes)
                self.assertNotIn(b"app.revanced", class_bytes)
                for class_name in (YOUTUBE_GMS_CLASS, YOUTUBE_GMS_CONSTANTS_CLASS):
                    youtube_class = bundle.read(class_name)
                    self.assertIn(
                        b"com.sylong.autopatch.test.android.youtube",
                        youtube_class,
                    )
                    self.assertNotIn(b"app.morphe.android.youtube", youtube_class)
                derived_manifest = bundle.read(MANIFEST).decode()
                self.assertIn("Version: 1.37.0.7870.1", derived_manifest)
                self.assertIn("Name: SyMorphe UIS7870 Patches", derived_manifest)


if __name__ == "__main__":
    unittest.main()
