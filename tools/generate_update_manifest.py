#!/usr/bin/env python3
"""Generate deterministic update metadata from release artifacts."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def android_tool(name: str) -> str:
    sdk = Path(os.environ["ANDROID_HOME"])
    candidates = sorted(
        sdk.glob(f"build-tools/*/{name}*"),
        key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.parent.name)),
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError(f"{name} not found under {sdk}")
    return str(candidates[0])


def apk_info(path: Path) -> dict:
    output = subprocess.check_output(
        [android_tool("aapt"), "dump", "badging", str(path)],
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    package = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        output,
    )
    if not package:
        raise ValueError(f"Unable to read APK metadata: {path}")
    min_sdk = re.search(r"sdkVersion:'(\d+)'", output)
    native_lines = re.findall(r"^(?:alt-)?native-code:\s*(.+)$", output, re.MULTILINE)
    abis = sorted({
        abi
        for line in native_lines
        for abi in re.findall(r"'([^']+)'", line)
    })
    certs = subprocess.check_output(
        [android_tool("apksigner"), "verify", "--print-certs", str(path)],
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    fingerprints = {
        value.replace(":", "").lower()
        for value in re.findall(
            r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)",
            certs,
        )
    }
    if not fingerprints:
        raise ValueError(f"Unable to read APK signer: {path}")
    return {
        "package_name": package.group(1),
        "version_code": int(package.group(2)),
        "version_name": package.group(3),
        "min_sdk": int(min_sdk.group(1)) if min_sdk else 1,
        "abis": abis,
        "signer_sha256": sorted(fingerprints),
    }


def artifact(path: Path, base_url: str, apk: bool = False) -> dict:
    result = {
        "name": path.name,
        "url": f"{base_url}/{path.name}",
        "sha256": sha256(path),
        "size": path.stat().st_size,
        "signature_url": f"{base_url}/{path.name}.sig",
    }
    if apk:
        result.update(apk_info(path))
    return result


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manager", type=Path, required=True)
    parser.add_argument("--patches", type=Path, required=True)
    parser.add_argument("--patch-version", required=True)
    parser.add_argument("--microg", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--sequence", type=int)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    published = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    sequence = args.sequence or int(published.strftime("%Y%m%d%H%M"))
    manager = artifact(args.manager, args.base_url, apk=True)
    patches = artifact(args.patches, args.base_url)
    microg = artifact(args.microg, args.base_url, apk=True)

    manifest = {
        "schema": 1,
        "sequence": sequence,
        "channel": "stable",
        "published_at": published.isoformat().replace("+00:00", "Z"),
        "manager": {
            "version": manager["version_name"],
            "version_code": manager["version_code"],
            "artifacts": [manager],
        },
        "patches": {
            "version": args.patch_version,
            "artifacts": [patches],
        },
        "microg": {
            "version": microg["version_name"],
            "version_code": microg["version_code"],
            "artifacts": [microg],
        },
    }
    write_json(args.output_dir / "update-manifest.json", manifest)
    write_json(
        args.output_dir / "app-release.json",
        {
            "version": manager["version_name"],
            "download_url": manager["url"],
            "signature_download_url": manager["signature_url"],
            "created_at": manifest["published_at"],
            "description": "AutoPatch Hub signed stable release",
        },
    )
    write_json(
        args.output_dir / "patches-bundle.json",
        {
            "version": args.patch_version,
            "download_url": patches["url"],
            "signature_download_url": patches["signature_url"],
            "created_at": manifest["published_at"],
            "description": "Unmodified Morphe patch bundle mirror with AutoPatch Hub transport signature",
        },
    )


if __name__ == "__main__":
    main()
