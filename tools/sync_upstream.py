#!/usr/bin/env python3
"""Prepare a merge of the official stable Manager; ambiguous conflicts stop before publication."""
import json
import os
from pathlib import Path
import re
import subprocess
from upstream_resolution import resolve_reviewed

OWNED = {".github/workflows/release.yml", "README.md", "app-release.json", "app/gradle.properties"}
UPSTREAM_ONLY_WORKFLOWS = {
    ".github/workflows/build_pull_request.yml", ".github/workflows/crowdin_pull.yml",
    ".github/workflows/crowdin_push.yml", ".github/workflows/open_pull_request.yml",
    ".github/workflows/test_fcm.yml",
}


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["git", *args], text=True, encoding="utf-8", errors="replace", capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f"git {args[0]} failed: {result.stdout}{result.stderr}")
    return result


def prepare(status: dict) -> None:
    release = json.loads(subprocess.check_output(
        ["gh", "api", "repos/MorpheApp/morphe-manager/releases/latest"], text=True, encoding="utf-8"))
    tag = release["tag_name"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or release.get("prerelease") or release.get("draft"):
        raise ValueError("Official source must be a published stable tag")
    git("fetch", "https://github.com/MorpheApp/morphe-manager.git", f"refs/tags/{tag}")
    commit = git("rev-parse", "FETCH_HEAD^{commit}").stdout.strip()
    status.update(tag=tag, commit=commit)
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
        status["repairs"] = resolve_reviewed(remaining)
        remaining = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
        if remaining:
            status["conflicts"] = remaining
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
    status.update(state="prepared" if changed else "current", changed=changed)
    print(f"Official Manager {tag}: {commit}; changed={changed}")
    if status.get("repairs"):
        print("Reviewed automatic repairs: " + ", ".join(status["repairs"]))


def main() -> None:
    # Never abort a merge or discard changes that predate this invocation.
    if git("status", "--porcelain", "--untracked-files=no").stdout.strip() or git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode == 0:
        raise RuntimeError("Official sync requires a clean checkout; existing work retained")
    status = {"state": "checking", "repairs": [], "conflicts": []}
    try:
        prepare(status)
    except Exception as error:
        status.update(state="blocked", error=str(error))
        # A retry starts from a clean checkout; retain the report, not a half-merged index.
        if git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode == 0:
            git("merge", "--abort")
        raise
    finally:
        report = Path(os.environ.get("SYNC_STATUS_PATH", "verification/upstream-sync.json"))
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
