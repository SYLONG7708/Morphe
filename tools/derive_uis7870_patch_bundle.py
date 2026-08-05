#!/usr/bin/env python3
"""Derive a fail-closed UIS7870 patch bundle from an official Morphe .mpp release."""

from __future__ import annotations

import argparse
import subprocess
import struct
import tempfile
import zipfile
from pathlib import Path

from prepare_uis7870_sources import PACKAGE_RE, load_profile


GMS_CLASS = "app/morphe/patches/shared/misc/gms/GmsCoreSupportPatchKt.class"
YOUTUBE_GMS_CLASS = "app/morphe/patches/youtube/misc/gms/GmsCoreSupportPatchKt.class"
YOUTUBE_GMS_CONSTANTS_CLASS = "app/morphe/patches/youtube/misc/gms/Constants.class"
MANIFEST = "META-INF/MANIFEST.MF"


def transform_class_utf8(class_bytes: bytes, old: bytes, new: bytes) -> tuple[bytes, int]:
    if class_bytes[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Target is not a Java class file")

    cp_count = struct.unpack_from(">H", class_bytes, 8)[0]
    output = bytearray(class_bytes[:10])
    offset = 10
    index = 1
    replacements = 0

    while index < cp_count:
        tag = class_bytes[offset]
        output.append(tag)
        offset += 1

        if tag == 1:  # CONSTANT_Utf8
            length = struct.unpack_from(">H", class_bytes, offset)[0]
            offset += 2
            value = class_bytes[offset : offset + length]
            offset += length
            changed = value.replace(old, new)
            replacements += value.count(old)
            if len(changed) > 0xFFFF:
                raise ValueError("Replacement exceeds Java class UTF-8 entry limit")
            output.extend(struct.pack(">H", len(changed)))
            output.extend(changed)
        elif tag in {3, 4}:  # Integer, Float
            output.extend(class_bytes[offset : offset + 4])
            offset += 4
        elif tag in {5, 6}:  # Long, Double; occupies two entries
            output.extend(class_bytes[offset : offset + 8])
            offset += 8
            index += 1
        elif tag in {7, 8, 16, 19, 20}:  # Class, String, MethodType, Module, Package
            output.extend(class_bytes[offset : offset + 2])
            offset += 2
        elif tag in {9, 10, 11, 12, 17, 18}:  # refs, NameAndType, Dynamic
            output.extend(class_bytes[offset : offset + 4])
            offset += 4
        elif tag == 15:  # MethodHandle
            output.extend(class_bytes[offset : offset + 3])
            offset += 3
        else:
            raise ValueError(f"Unsupported Java constant-pool tag {tag} at index {index}")
        index += 1

    output.extend(class_bytes[offset:])
    return bytes(output), replacements


def replace_manifest_once(text: str, old: str, new: str) -> str:
    count = text.count(old)
    if count != 1:
        raise ValueError(f"Expected one manifest field {old!r}, found {count}")
    return text.replace(old, new)


def rebuild_dex(class_entries: dict[str, bytes], d8_path: Path) -> bytes:
    if not d8_path.is_file():
        raise ValueError(f"D8 executable does not exist: {d8_path}")

    with tempfile.TemporaryDirectory(prefix="autopatch-d8-") as directory:
        root = Path(directory)
        classes_jar = root / "classes.jar"
        output_dir = root / "output"
        output_dir.mkdir()
        with zipfile.ZipFile(classes_jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in class_entries.items():
                archive.writestr(name, data)

        process = subprocess.run(
            [
                str(d8_path),
                "--min-api",
                "26",
                "--output",
                str(output_dir),
                str(classes_jar),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if process.returncode != 0:
            details = (process.stderr or process.stdout).strip()
            raise ValueError(f"D8 failed with exit code {process.returncode}: {details}")

        dex = output_dir / "classes.dex"
        if not dex.is_file() or dex.stat().st_size == 0:
            raise ValueError("D8 did not produce classes.dex")
        return dex.read_bytes()


def derive_bundle(
    source: Path,
    output: Path,
    upstream_version: str,
    profile: dict[str, str],
    vendor_override: str | None = None,
    d8_path: Path | None = None,
) -> str:
    vendor = vendor_override or profile["vendorGroup"]
    if not PACKAGE_RE.fullmatch(vendor):
        raise ValueError(f"Invalid vendor group: {vendor}")
    derived_version = f"{upstream_version}.7870.{profile['profileRevision']}"
    youtube_package = f"{vendor}.android.youtube"

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source, "r") as source_zip:
        names = set(source_zip.namelist())
        missing = {
            GMS_CLASS,
            YOUTUBE_GMS_CLASS,
            YOUTUBE_GMS_CONSTANTS_CLASS,
            MANIFEST,
        } - names
        if missing:
            raise ValueError(f"Official bundle is missing: {', '.join(sorted(missing))}")

        original_class = source_zip.read(GMS_CLASS)
        derived_class, replacements = transform_class_utf8(
            original_class,
            b"app.revanced",
            vendor.encode("ascii"),
        )
        if replacements < 2:
            raise ValueError(
                f"Expected multiple GmsCore vendor constants, replaced only {replacements}"
            )
        if b"app.revanced" in derived_class:
            raise ValueError("Old GmsCore vendor remains in the target patch class")

        derived_youtube_classes: dict[str, bytes] = {}
        for class_name in (YOUTUBE_GMS_CLASS, YOUTUBE_GMS_CONSTANTS_CLASS):
            derived_youtube_class, youtube_replacements = transform_class_utf8(
                source_zip.read(class_name),
                b"app.morphe.android.youtube",
                youtube_package.encode("ascii"),
            )
            if youtube_replacements < 1:
                raise ValueError(
                    f"Expected the Morphe YouTube fallback package in {class_name}"
                )
            if b"app.morphe.android.youtube" in derived_youtube_class:
                raise ValueError(
                    f"Old Morphe YouTube fallback remains in {class_name}"
                )
            derived_youtube_classes[class_name] = derived_youtube_class

        transformed_classes = {
            GMS_CLASS: derived_class,
            **derived_youtube_classes,
        }
        derived_dex: bytes | None = None
        if "classes.dex" in names:
            if d8_path is None:
                raise ValueError(
                    "Official Android bundle contains classes.dex; --d8 is required "
                    "so runtime bytecode cannot retain old package identities"
                )
            class_entries = {
                name: transformed_classes.get(name, source_zip.read(name))
                for name in names
                if name.endswith(".class")
            }
            derived_dex = rebuild_dex(class_entries, d8_path)
            if vendor.encode("ascii") not in derived_dex:
                raise ValueError("Derived vendor group is absent from rebuilt classes.dex")
            if youtube_package.encode("ascii") not in derived_dex:
                raise ValueError("Derived YouTube package is absent from rebuilt classes.dex")
            if b"app.revanced" in derived_dex:
                raise ValueError("Old GmsCore vendor remains in rebuilt classes.dex")
            if b"app.morphe.android.youtube" in derived_dex:
                raise ValueError("Old Morphe YouTube fallback remains in rebuilt classes.dex")

        manifest = source_zip.read(MANIFEST).decode("utf-8")
        manifest = replace_manifest_once(
            manifest,
            "Name: Morphe Patches",
            "Name: SyMorphe UIS7870 Patches",
        )
        manifest = replace_manifest_once(
            manifest,
            "Description: Patches for Morphe",
            "Description: Morphe patches derived for safe UIS7870 coexistence",
        )
        manifest = replace_manifest_once(
            manifest,
            f"Version: {upstream_version}",
            f"Version: {derived_version}",
        )
        manifest = replace_manifest_once(
            manifest,
            "Source: git@github.com:MorpheApp/morphe-patches.git",
            "Source: https://github.com/SYLONG7708/Morphe",
        )

        with zipfile.ZipFile(output, "w") as output_zip:
            for info in source_zip.infolist():
                data = source_zip.read(info.filename)
                if info.filename in transformed_classes:
                    data = transformed_classes[info.filename]
                elif info.filename == "classes.dex" and derived_dex is not None:
                    data = derived_dex
                elif info.filename == MANIFEST:
                    data = manifest.encode("utf-8")
                output_zip.writestr(info, data)

    with zipfile.ZipFile(output, "r") as result:
        result.testzip()
        if vendor.encode("ascii") not in result.read(GMS_CLASS):
            raise ValueError("Derived vendor group is absent from output class")
        for class_name in (YOUTUBE_GMS_CLASS, YOUTUBE_GMS_CONSTANTS_CLASS):
            youtube_class = result.read(class_name)
            if youtube_package.encode("ascii") not in youtube_class:
                raise ValueError(f"Derived YouTube package is absent from {class_name}")
            if b"app.morphe.android.youtube" in youtube_class:
                raise ValueError(
                    f"Old Morphe YouTube fallback remains in {class_name}"
                )
        if "classes.dex" in result.namelist():
            dex = result.read("classes.dex")
            if vendor.encode("ascii") not in dex or youtube_package.encode("ascii") not in dex:
                raise ValueError("Rebuilt runtime DEX does not contain safe package identities")
            if b"app.revanced" in dex or b"app.morphe.android.youtube" in dex:
                raise ValueError("Rebuilt runtime DEX retains an old package identity")
        if f"Version: {derived_version}" not in result.read(MANIFEST).decode("utf-8"):
            raise ValueError("Derived version is absent from output manifest")
    return derived_version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--patch-version", required=True)
    parser.add_argument("--vendor-group")
    parser.add_argument("--d8", type=Path)
    args = parser.parse_args()

    version = derive_bundle(
        source=args.source,
        output=args.output,
        upstream_version=args.patch_version,
        profile=load_profile(args.profile),
        vendor_override=args.vendor_group,
        d8_path=args.d8,
    )
    print(f"PATCH_VERSION={version}")
    print(f"PATCH_BUNDLE={args.output}")


if __name__ == "__main__":
    main()
