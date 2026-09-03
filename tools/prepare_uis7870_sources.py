#!/usr/bin/env python3
"""Prepare pinned Morphe Patches and MicroG-RE sources for safe UIS7870 coexistence."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


PACKAGE_RE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")


def load_profile(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise ValueError(f"Malformed profile line: {raw_line!r}")
        values[key.strip()] = value.strip()

    required = {
        "managerPackage",
        "vendorGroup",
        "patchedYouTubePackage",
        "microgPackage",
        "profileRevision",
    }
    missing = required - values.keys()
    if missing:
        raise ValueError(f"Missing profile keys: {', '.join(sorted(missing))}")

    for key in required - {"profileRevision"}:
        if not PACKAGE_RE.fullmatch(values[key]):
            raise ValueError(f"Invalid Android package value for {key}: {values[key]}")

    vendor = values["vendorGroup"]
    if values["patchedYouTubePackage"] != f"{vendor}.android.youtube":
        raise ValueError("patchedYouTubePackage must match <vendorGroup>.android.youtube")
    if values["microgPackage"] != f"{vendor}.android.gms":
        raise ValueError("microgPackage must match <vendorGroup>.android.gms")
    if not values["profileRevision"].isdigit() or int(values["profileRevision"]) < 1:
        raise ValueError("profileRevision must be a positive integer")
    return values


def replace_once(path: Path, expected: str, replacement: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(expected)
    if count != 1:
        raise ValueError(f"Expected exactly one match in {path}, found {count}: {expected!r}")
    path.write_text(text.replace(expected, replacement), encoding="utf-8", newline="\n")


def replace_microg_version(
    path: Path,
    upstream_version: str,
    derived_version: str,
    version_code: int,
) -> None:
    """Update both the legacy and current MicroG-RE version declarations."""
    source = path.read_text(encoding="utf-8")
    name_pattern = re.compile(
        rf"(?m)^(?P<indent>\s*)(?P<key>ext\.appVersionName|def ourGmsVersionName)"
        rf"\s*=\s*(?P<quote>['\"]){re.escape(upstream_version)}(?P=quote)\s*$"
    )
    name_matches = list(name_pattern.finditer(source))
    if len(name_matches) != 1:
        raise ValueError(
            f"Expected exactly one MicroG version name in {path}, found {len(name_matches)}"
        )
    source = name_pattern.sub(
        lambda match: (
            f"{match.group('indent')}{match.group('key')} = "
            f"{match.group('quote')}{derived_version}{match.group('quote')}"
        ),
        source,
    )

    code_pattern = re.compile(
        r"(?m)^(?P<indent>\s*)(?P<key>ext\.appVersionCode|def ourGmsVersionCode)"
        r"\s*=\s*\d+\s*$"
    )
    code_matches = list(code_pattern.finditer(source))
    if len(code_matches) != 1:
        raise ValueError(
            f"Expected exactly one MicroG version code in {path}, found {len(code_matches)}"
        )
    source = code_pattern.sub(
        lambda match: (
            f"{match.group('indent')}{match.group('key')} = {version_code}"
        ),
        source,
    )
    path.write_text(source, encoding="utf-8", newline="\n")


def prepare_sources(
    patches_dir: Path,
    microg_dir: Path,
    profile: dict[str, str],
    upstream_patch_version: str,
    upstream_microg_version: str,
    microg_version_code: int,
    vendor_override: str | None = None,
) -> tuple[str, str]:
    vendor = vendor_override or profile["vendorGroup"]
    if not PACKAGE_RE.fullmatch(vendor):
        raise ValueError(f"Invalid vendor group: {vendor}")

    revision = profile["profileRevision"]
    derived_patch_version = f"{upstream_patch_version}.7870.{revision}"
    derived_microg_version = f"{upstream_microg_version}-7870.{revision}"

    gms_patch = (
        patches_dir
        / "patches/src/main/kotlin/app/morphe/patches/shared/misc/gms"
        / "GmsCoreSupportPatch.kt"
    )
    replace_once(
        gms_patch,
        'internal const val GMS_CORE_VENDOR_GROUP_ID = "app.revanced"',
        f'internal const val GMS_CORE_VENDOR_GROUP_ID = "{vendor}"',
    )
    replace_once(
        gms_patch,
        'setAttribute("android:name", "app.revanced.MICROG_PACKAGE_NAME")',
        'setAttribute("android:name", "$GMS_CORE_VENDOR_GROUP_ID.MICROG_PACKAGE_NAME")',
    )
    youtube_constants = (
        patches_dir
        / "patches/src/main/kotlin/app/morphe/patches/youtube/misc/gms"
        / "Constants.kt"
    )
    replace_once(
        youtube_constants,
        'const val MORPHE_YOUTUBE_PACKAGE_NAME = "app.morphe.android.youtube"',
        f'const val MORPHE_YOUTUBE_PACKAGE_NAME = "{vendor}.android.youtube"',
    )
    replace_once(
        patches_dir / "gradle.properties",
        f"version = {upstream_patch_version}",
        f"version = {derived_patch_version}",
    )
    patch_build = patches_dir / "patches/build.gradle.kts"
    replace_once(
        patch_build,
        'name = "Morphe Patches"',
        'name = "SyMorphe UIS7870 Patches"',
    )
    replace_once(
        patch_build,
        'description = "Patches for Morphe"',
        'description = "Morphe patches derived for safe UIS7870 package coexistence"',
    )
    replace_once(
        patch_build,
        'source = "git@github.com:MorpheApp/morphe-patches.git"',
        'source = "https://github.com/SYLONG7708/Morphe"',
    )

    microg_root_gradle = microg_dir / "build.gradle"
    replace_once(
        microg_root_gradle,
        'ext.basePackageName = "app.revanced"',
        f'ext.basePackageName = "{vendor}"',
    )
    replace_microg_version(
        microg_root_gradle,
        upstream_microg_version,
        derived_microg_version,
        microg_version_code,
    )

    return derived_patch_version, derived_microg_version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--patches-dir", type=Path, required=True)
    parser.add_argument("--microg-dir", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--patch-version", required=True)
    parser.add_argument("--microg-version", required=True)
    parser.add_argument("--microg-version-code", type=int, required=True)
    parser.add_argument("--vendor-group")
    args = parser.parse_args()

    profile = load_profile(args.profile)
    patch_version, microg_version = prepare_sources(
        patches_dir=args.patches_dir,
        microg_dir=args.microg_dir,
        profile=profile,
        upstream_patch_version=args.patch_version,
        upstream_microg_version=args.microg_version,
        microg_version_code=args.microg_version_code,
        vendor_override=args.vendor_group,
    )
    print(f"PATCH_VERSION={patch_version}")
    print(f"MICROG_VERSION={microg_version}")
    print(f"VENDOR_GROUP={args.vendor_group or profile['vendorGroup']}")


if __name__ == "__main__":
    main()
