"""Public Blue M updater audit using its pinned signing certificate, no login cookies."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

REPO = "SYLONG7708/Morphe"
SIGNERS = {
    "app.morphe.android.youtube": "7cee829c140e3ba32767e541e98d99a87214b80c7b3095692549131a2d6ebf02",
    "app.revanced.android.gms": "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7",
}


def validate(manifest, release, previous=0):
    sequence = manifest.get("sequence")
    if (manifest.get("schema") != 1 or manifest.get("channel") != "blue-m-stable"
            or type(sequence) is not int or sequence < previous):
        raise ValueError("Blue M channel identity or rollback check failed")
    tag = f"blue-m-{sequence}"
    if release.get("tag_name") != tag or release.get("draft") or release.get("prerelease"):
        raise ValueError("Blue M stable release is not publicly published")
    packages = manifest.get("packages", [])
    if len(packages) != 2 or {p.get("package") for p in packages} != set(SIGNERS):
        raise ValueError("Blue M package set differs from the supported pair")
    upstream = manifest.get("upstream", {})
    if (upstream.get("policy") != "morphe-recommended-stable" or upstream.get("prerelease")
            or upstream.get("experimental") or manifest.get("client", {}).get("revision", 0) < 8):
        raise ValueError("Blue M stable policy or in-app updater revision is invalid")
    assets = {a["name"]: a for a in release["assets"]}
    for item in packages:
        url = urlsplit(item["url"])
        prefix = f"/{REPO}/releases/download/{tag}/"
        name = url.path.removeprefix(prefix)
        if (url.scheme != "https" or url.netloc != "github.com" or url.query or url.fragment
                or not url.path.startswith(prefix) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk", name)):
            raise ValueError("Blue M asset URL is outside its versioned release")
        if item.get("signer_sha256") != SIGNERS[item["package"]]:
            raise ValueError("Blue M signer changed")
        if not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")) or item.get("size", 0) <= 0:
            raise ValueError("Blue M asset digest or size missing")
        asset = assets.get(name, {})
        if (asset.get("size") != item["size"] or asset.get("state") != "uploaded"
                or asset.get("digest") != "sha256:" + item["sha256"]):
            raise ValueError("Blue M release asset differs from signed metadata")
        if item["package"] == "app.morphe.android.youtube" and item["version_code"] != sequence:
            raise ValueError("Blue M sequence differs from APK version code")
    return tag


def audit(guard):
    from morphe_codex_guard import utc, write_json
    probe = guard.root / "blue-m-probe"
    probe.mkdir(exist_ok=True)
    def download(url, destination):
        guard.command([guard.config.get("curl", "curl"), "-fLsS", "--retry", "2",
                       "--connect-timeout", "20", "--max-time", "600", "--proto", "=https",
                       "--proto-redir", "=https", "-H", "Cache-Control: no-cache",
                       url, "-o", str(destination)], timeout=700)
    # Both files come from one commit so a concurrent atomic publication cannot mix generations.
    sha = guard.api(f"repos/{REPO}/git/ref/heads/main")["object"]["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid channel commit")
    for name in ("blue-m-stable.json", "blue-m-stable.json.sig"):
        download(f"https://raw.githubusercontent.com/{REPO}/{sha}/channels/{name}", probe / name)
    guard.command([guard.config["java"], str(guard.root / "VerifyFile.java"),
                   str(guard.root / "bluem-release-cert.der"), str(probe / "blue-m-stable.json"),
                   str(probe / "blue-m-stable.json.sig")], log=probe / "signature.log")
    manifest = json.loads((probe / "blue-m-stable.json").read_text(encoding="utf-8"))
    sequence = manifest.get("sequence")
    if type(sequence) is not int or not 0 < sequence <= 2147483647:
        raise ValueError("Invalid channel sequence")
    release = guard.api(f"repos/{REPO}/releases/tags/blue-m-{sequence}")
    previous = guard.state.get("blue_m_release", {}).get("result", {}).get("sequence", 0)
    tag = validate(manifest, release, previous)
    checked = []
    for item in manifest["packages"]:
        cached = probe / (item["sha256"] + ".apk")
        def matches(path):
            if not path.exists() or path.stat().st_size != item["size"]:
                return False
            with path.open("rb") as stream:
                return hashlib.file_digest(stream, "sha256").hexdigest() == item["sha256"]
        if not matches(cached):
            temporary = cached.with_suffix(".partial")
            download(item["url"], temporary)
            if not matches(temporary):
                raise ValueError("Public Blue M APK download failed SHA-256 or length verification")
            os.replace(temporary, cached)
        else:
            guard.command([guard.config.get("curl", "curl"), "-fILsS", "--retry", "2",
                           "--connect-timeout", "20", "--max-time", "90", "--proto", "=https",
                           "--proto-redir", "=https", item["url"], "-o", os.devnull], timeout=120)
        checked.append({"package": item["package"], "sha256": item["sha256"], "size": item["size"]})
    result = {"tag": tag, "sequence": sequence, "commit": sha, "verified_at": utc(),
              "public_download_hashes_verified": True, "packages": checked}
    write_json(probe / "result.json", result)
    return result
