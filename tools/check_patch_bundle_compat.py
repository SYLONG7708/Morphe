#!/usr/bin/env python3
"""Fail a release when its patch bundle requires a newer Manager patcher API."""

from __future__ import annotations

import argparse
import re
import zipfile
from pathlib import Path


def version_key(value: str) -> tuple[int, ...]:
    numbers = tuple(int(part) for part in re.findall(r"\d+", value))
    if not numbers:
        raise ValueError(f"Invalid version: {value!r}")
    return numbers


def manifest_attributes(raw: str) -> dict[str, str]:
    unfolded = raw.replace("\r\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    for line in unfolded.splitlines():
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    return {
        key.strip(): value.strip()
        for line in lines
        if ": " in line
        for key, value in [line.split(": ", 1)]
    }


def required_patcher_version(bundle: Path) -> str:
    with zipfile.ZipFile(bundle) as archive:
        raw = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    value = manifest_attributes(raw).get("Patcher-Version")
    if not value:
        raise ValueError(f"{bundle} does not declare Patcher-Version")
    return value


def manager_patcher_version(catalog: Path) -> str:
    match = re.search(
        r'(?m)^morphe-patcher\s*=\s*"([^"]+)"\s*$',
        catalog.read_text(encoding="utf-8"),
    )
    if not match:
        raise ValueError(f"Unable to find morphe-patcher version in {catalog}")
    return match.group(1)


def assert_compatible(bundle: Path, catalog: Path) -> tuple[str, str]:
    required = required_patcher_version(bundle)
    available = manager_patcher_version(catalog)
    if version_key(required) > version_key(available):
        raise RuntimeError(
            f"Patch bundle requires morphe-patcher {required}, "
            f"but Manager provides {available}"
        )
    return required, available


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    args = parser.parse_args()
    required, available = assert_compatible(args.bundle, args.catalog)
    print(
        f"Compatible: bundle requires morphe-patcher {required}; "
        f"Manager provides {available}"
    )


if __name__ == "__main__":
    main()
