import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from derive_uis7870_patch_bundle import (
    GMS_CLASS,
    MANIFEST,
    YOUTUBE_GMS_CLASS,
    YOUTUBE_GMS_CONSTANTS_CLASS,
    derive_bundle,
)


def minimal_class(*values: bytes) -> bytes:
    pool = bytearray()
    for value in values:
        pool.append(1)
        pool.extend(struct.pack(">H", len(value)))
        pool.extend(value)
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x3d" + struct.pack(">H", len(values) + 1) + pool


class DeriveUis7870PatchBundleTest(unittest.TestCase):
    def test_derives_vendor_group_and_manifest(self) -> None:
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

            version = derive_bundle(
                source,
                output,
                "1.37.0",
                {"vendorGroup": "com.sylong.autopatch", "profileRevision": "1"},
                vendor_override="com.sylong.autopatch.test",
            )

            self.assertEqual("1.37.0.7870.1", version)
            with zipfile.ZipFile(output) as bundle:
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
