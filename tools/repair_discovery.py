#!/usr/bin/env python3
"""Repair discovery pointers using only the latest release's RSA-verified manifest."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from generate_update_manifest import write_json


DISCOVERY_FILES = ("app-release.json", "patches-bundle.json")


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


def verified_discovery(root: Path) -> tuple[str, dict, dict]:
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
    return latest, manager, patches


def git(root: Path, *args: str, **kwargs) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, **kwargs).strip()


def fetch_main(root: Path) -> str:
    git(root, "fetch", "--no-tags", "origin", "refs/heads/main")
    return git(root, "rev-parse", "FETCH_HEAD")


def discovery_tree(root: Path, base: str, manager: dict, patches: dict) -> str:
    # Use a separate index so build edits, staged files and the checked-out
    # branch cannot enter the publication or be discarded by a reset/rebase.
    with tempfile.TemporaryDirectory(prefix="discovery-index-") as temp:
        directory = Path(temp)
        env = {**os.environ, "GIT_INDEX_FILE": str(directory / "index")}
        git(root, "read-tree", base, env=env)
        for filename, data in zip(DISCOVERY_FILES, (manager, patches)):
            path = directory / filename
            write_json(path, data)
            blob = git(root, "hash-object", "-w", "--", str(path))
            git(root, "update-index", "--add", "--cacheinfo", f"100644,{blob},{filename}", env=env)
        return git(root, "write-tree", env=env)


def publish(root: Path) -> None:
    for attempt in range(3):
        base = fetch_main(root)
        latest, manager, patches = verified_discovery(root)
        tree = discovery_tree(root, base, manager, patches)
        if latest_tag() != latest:
            print("Latest release changed during discovery publication; re-verifying")
            continue
        if tree == git(root, "rev-parse", f"{base}^{{tree}}"):
            print(f"Verified discovery metadata is already current for {latest}")
            return
        commit = git(
            root, "-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit-tree", tree, "-p", base,
            "-m", "chore: update signed release discovery metadata [skip ci]",
        )
        try:
            # A normal fast-forward push is the compare-and-swap for this base.
            git(root, "push", "origin", f"{commit}:refs/heads/main")
        except subprocess.CalledProcessError:
            if fetch_main(root) == base:
                raise  # Authentication/policy/network failures are not merge races.
            print(f"main advanced during discovery publication; retry {attempt + 1}/3")
            continue
        print(f"Published verified discovery metadata for {latest}")
        return
    raise RuntimeError("Discovery publication kept changing; retry later")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true",
                        help="publish verified pointers on current origin/main with bounded retries")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.publish:
        publish(root)
    else:
        latest, manager, patches = verified_discovery(root)
        if latest_tag() != latest:
            raise RuntimeError("Latest release changed during discovery repair; retry later")
        for filename, data in zip(DISCOVERY_FILES, (manager, patches)):
            write_json(root / filename, data)
        print(f"Verified discovery metadata for {latest}")


if __name__ == "__main__":
    main()
