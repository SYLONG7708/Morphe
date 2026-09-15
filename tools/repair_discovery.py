#!/usr/bin/env python3
"""Repair discovery pointers using only the latest release's RSA-verified manifest."""
import json
from pathlib import Path
import subprocess
import tempfile
from generate_update_manifest import write_json


def discovery(manifest: dict) -> tuple[dict, dict]:
    if manifest.get("schema") != 1 or manifest.get("channel") != "stable":
        raise ValueError("Unsupported signed manifest")
    manager = next(a for a in manifest["manager"]["artifacts"]
                   if a.get("package_name") == "com.sylong.symorphe")
    patch = manifest["patches"]["artifacts"][0]
    return tuple({
        "version": version, "download_url": artifact["url"],
        "signature_download_url": artifact["signature_url"],
        "created_at": manifest["published_at"], "description": description,
    } for artifact, version, description in (
        (manager, manager["version_name"], "SyMorphe signed stable release"),
        (patch, manifest["patches"]["version"], "Morphe patch bundle derived for the signed SyMorphe UIS7870 coexistence profile"),
    ))


def latest_tag() -> str:
    return json.loads(subprocess.check_output([
        "gh", "release", "view", "--repo", "SYLONG7708/Morphe", "--json", "tagName",
    ], text=True))["tagName"]


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    latest = latest_tag()
    with tempfile.TemporaryDirectory(prefix="verified-discovery-") as temp:
        directory = Path(temp)
        subprocess.run(["gh", "release", "download", latest, "--repo", "SYLONG7708/Morphe",
                        "--pattern", "update-manifest.json*", "--dir", str(directory)], check=True)
        subprocess.run(["javac", "-d", str(directory), str(root / "tools/VerifyFile.java")], check=True)
        manifest = directory / "update-manifest.json"
        subprocess.run(["java", "-cp", str(directory), "VerifyFile",
                        str(root / "app/src/main/res/raw/update_signing_cert.der"),
                        str(manifest), str(manifest) + ".sig"], check=True)
        manager, patches = discovery(json.loads(manifest.read_text(encoding="utf-8")))
        if latest_tag() != latest:
            raise RuntimeError("Latest release changed during discovery repair; retry later")
        write_json(root / "app-release.json", manager)
        write_json(root / "patches-bundle.json", patches)
    print(f"Verified discovery metadata for {latest}")


if __name__ == "__main__":
    main()
