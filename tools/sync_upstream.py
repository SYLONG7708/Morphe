#!/usr/bin/env python3
"""Prepare a merge of the official stable Manager; ambiguous conflicts stop before publication."""
import json
import os
from pathlib import Path
import re
import subprocess

OWNED = {".github/workflows/release.yml", "README.md", "app-release.json", "app/gradle.properties"}
UPSTREAM_ONLY_WORKFLOWS = {
    ".github/workflows/build_pull_request.yml", ".github/workflows/crowdin_pull.yml",
    ".github/workflows/crowdin_push.yml", ".github/workflows/open_pull_request.yml",
    ".github/workflows/test_fcm.yml",
}


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], check=check, text=True, capture_output=True)


def main() -> None:
    release = json.loads(subprocess.check_output(
        ["gh", "api", "repos/MorpheApp/morphe-manager/releases/latest"], text=True))
    tag = release["tag_name"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or release.get("prerelease") or release.get("draft"):
        raise ValueError("Official source must be a published stable tag")
    git("fetch", "https://github.com/MorpheApp/morphe-manager.git", f"refs/tags/{tag}")
    commit = git("rev-parse", "FETCH_HEAD^{commit}").stdout.strip()
    if git("merge-base", "--is-ancestor", commit, "HEAD", check=False).returncode == 0:
        changed = False
    else:
        result = git("merge", "--no-ff", "--no-commit", commit, check=False)
        print(result.stdout, result.stderr)
        original_conflicts = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
        # GitHub jobs belong to this signed release channel; upstream's publishing,
        # translation and notification jobs must not replace or add to them.
        git("restore", "--source=HEAD", "--staged", "--worktree", "--", ".github/workflows")
        conflicts = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
        for path in conflicts:
            if path in OWNED:
                git("restore", "--ours", "--", path)
                git("add", "--", path)
            elif path in UPSTREAM_ONLY_WORKFLOWS:
                git("rm", "--", path)
        remaining = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
        if remaining:
            raise RuntimeError("Upstream merge needs a code change; current release retained: " + ", ".join(remaining))
        if result.returncode and not original_conflicts:
            raise RuntimeError("Official merge failed; current release retained")
        changed = True
        Path("config/upstream-manager.json").write_text(json.dumps({
            "repository": "MorpheApp/morphe-manager", "tag": tag, "commit": commit,
        }, indent=2) + "\n", encoding="utf-8")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"changed={str(changed).lower()}\ntag={tag}\ncommit={commit}\n")
    print(f"Official Manager {tag}: {commit}; changed={changed}")


if __name__ == "__main__":
    main()
