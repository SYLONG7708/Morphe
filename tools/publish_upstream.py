#!/usr/bin/env python3
"""Publish a verified source commit, re-verifying merges with concurrent main updates."""
import argparse
from pathlib import Path
import subprocess


GENERATED = ("config/upstream-manager.json", "config/dependency-provenance.json", "vendor/m2")


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True,
                                   encoding="utf-8", stderr=subprocess.PIPE).strip()


def fetch_main(root: Path) -> str:
    git(root, "fetch", "--no-tags", "origin", "refs/heads/main")
    return git(root, "rev-parse", "FETCH_HEAD")


def ancestor(root: Path, older: str, newer: str) -> bool:
    result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", older, newer],
                            capture_output=True)
    if result.returncode not in (0, 1):
        raise RuntimeError("Cannot establish source publication ancestry")
    return result.returncode == 0


def merge_in_progress(root: Path) -> bool:
    return subprocess.run(["git", "-C", str(root), "rev-parse", "-q", "--verify", "MERGE_HEAD"],
                          capture_output=True).returncode == 0


def publish(root: Path, verify_command: list[str]) -> None:
    if not verify_command:
        raise ValueError("Source publication requires a re-verification command")
    if merge_in_progress(root) or git(root, "status", "--porcelain", "--untracked-files=no"):
        raise RuntimeError("Source publication requires a clean committed candidate; existing work retained")
    verified = git(root, "rev-parse", "HEAD")
    base = git(root, "rev-parse", "HEAD^1")
    for attempt in range(3):
        latest = fetch_main(root)
        if ancestor(root, verified, latest):
            # A push can succeed even when its response is lost.
            print("Verified source commit is already present on main")
            return
        if not ancestor(root, base, latest):
            raise RuntimeError("main history changed; current release retained")
        if not ancestor(root, latest, verified):
            try:
                git(root, "merge", "--no-ff", "--no-commit", latest)
            except subprocess.CalledProcessError:
                if merge_in_progress(root):
                    git(root, "merge", "--abort")
                raise
            print("main advanced; verifying the combined source and both editions", flush=True)
            merged_tree = git(root, "write-tree")
            # Never push a rebase or merge on the strength of the old build.
            # Leave a failed verification available for diagnosis in this CI checkout.
            subprocess.run(verify_command, cwd=root, check=True)
            git(root, "diff", "--check")
            if git(root, "diff", "--name-only", merged_tree, "--", ".",
                   *(f":(exclude){path}" for path in GENERATED)):
                raise RuntimeError("Verification changed unexpected source files; publication stopped")
            git(root, "add", "--", *GENERATED)
            git(root, "commit", "--no-edit")
            verified = git(root, "rev-parse", "HEAD")
        try:
            # Normal fast-forward only; a newer main is never overwritten.
            git(root, "push", "origin", f"{verified}:refs/heads/main")
        except subprocess.CalledProcessError:
            fresh = fetch_main(root)
            if ancestor(root, verified, fresh):
                print("Verified source commit is already present on main")
                return
            if fresh == latest:
                raise  # Authentication, policy and network errors are not merge races.
            print(f"main advanced during source publication (attempt {attempt + 1}/3)", flush=True)
            continue
        print("Published verified official source merge")
        return
    raise RuntimeError("main kept changing during source publication; retry later")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a re-verification command is required")
    publish(Path.cwd(), command)


if __name__ == "__main__":
    main()
