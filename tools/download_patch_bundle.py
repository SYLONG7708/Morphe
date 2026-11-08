#!/usr/bin/env python3
"""Download the exact official patch asset, with a verified GitHub API fallback."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from bluem_probe import GitHubAPIAuth


REPOSITORY = "MorpheApp/morphe-patches"


def patch_asset(release: dict) -> dict:
    assets = [asset for asset in release["assets"] if asset["name"].endswith(".mpp")]
    if len(assets) != 1:
        raise ValueError("Expected exactly one official patch bundle")
    asset = assets[0]
    if (type(asset.get("id")) is not int or asset["id"] <= 0
            or type(asset.get("size")) is not int or asset["size"] <= 0
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or "")):
        raise ValueError("Official patch asset requires an ID, size and SHA-256 digest")
    tag = urllib.parse.quote(release["tag_name"], safe="")
    name = urllib.parse.quote(asset["name"], safe="")
    if (asset.get("url") != f"https://api.github.com/repos/{REPOSITORY}/releases/assets/{asset['id']}"
            or asset.get("browser_download_url") !=
            f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}"):
        raise ValueError("Unexpected official patch asset origin or identity")
    return asset


def transient(error: Exception) -> bool:
    if isinstance(error, urllib.error.HTTPError):
        return error.code in {429, 500, 502, 503, 504}
    if isinstance(error, urllib.error.URLError):
        error = error.reason
    return isinstance(error, (TimeoutError, ConnectionError, socket.gaierror, http.client.IncompleteRead))


def transfer(opener, url: str, temporary: Path, asset: dict) -> None:
    request = urllib.request.Request(url, headers={"Accept": "application/octet-stream"})
    digest = hashlib.sha256()
    size = 0
    with opener.open(request, timeout=90) as response, temporary.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > asset["size"]:
                raise ValueError("Official patch asset size mismatch")
            digest.update(chunk)
            output.write(chunk)
    if size != asset["size"] or "sha256:" + digest.hexdigest() != asset["digest"]:
        raise ValueError("Official patch asset hash mismatch or size mismatch")


def download(release: dict, output: Path) -> None:
    asset = patch_asset(release)
    opener = urllib.request.build_opener(GitHubAPIAuth(os.environ.get("GH_TOKEN", "")))
    output.parent.mkdir(parents=True, exist_ok=True)
    # Failed transfers and verification never replace an existing usable file.
    with tempfile.TemporaryDirectory(prefix=".patch-download-", dir=output.parent) as staging:
        temporary = Path(staging) / "patches.mpp"
        for index, url in enumerate((asset["browser_download_url"], asset["url"])):
            try:
                transfer(opener, url, temporary, asset)
            except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.IncompleteRead) as error:
                if index or not transient(error):
                    raise
            else:
                break
            # Leave the first exception's context before the API attempt, so an
            # API auth error is not classified as the earlier transient failure.
            print("::warning::Official download temporarily unavailable; trying the same asset through the GitHub API.",
                  flush=True)
        temporary.replace(output)
    print(f"Verified official patch bundle: {output.name} ({asset['size']} bytes)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    download(json.loads(args.release_json.read_text(encoding="utf-8")), args.output)


if __name__ == "__main__":
    main()
