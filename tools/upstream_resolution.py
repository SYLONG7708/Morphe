"""Apply reviewed integration repairs only to their exact audited Git blobs."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile


def git_blob(spec: str) -> bytes | None:
    result = subprocess.run(["git", "show", spec], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def sha(data: bytes | None) -> str | None:
    # Absence is an exact input, not a wildcard or the hash of an empty file.
    return hashlib.sha256(data).hexdigest() if data is not None else None


def relative_path(value: str) -> str:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(p in ("..", ".git") for p in path.parts) or "\\" in value or ":" in value:
        raise ValueError("Unsafe resolution path")
    return str(path)


def resolve_reviewed(conflicts: list[str]) -> list[str]:
    # Read rules and patches from the trusted pre-merge commit, never the incoming tree.
    raw = git_blob("HEAD:config/upstream-resolutions.json")
    if raw is None:
        return []
    catalog = json.loads(raw)
    if catalog.get("schema") != 1:
        raise ValueError("Unsupported resolution schema")
    repaired = []
    requirements = set()
    # Some clean textual merges still need an integration adjustment (for example,
    # a new icon generator must use this channel's existing launcher artwork).
    # These rules additionally pin the automatic merge result and apply to that
    # result; conflict rules default to the local stage.
    merged_paths = {r["path"] for r in catalog["resolutions"] if "merged" in r["inputs"]}
    merge_base = None
    for path in dict.fromkeys([*conflicts, *sorted(merged_paths - set(conflicts))]):
        relative_path(path)
        if path in conflicts:
            blobs = {name: git_blob(f":{stage}:{path}")
                     for stage, name in ((1, "base"), (2, "local"), (3, "upstream"))}
            source = blobs["local"]
        else:
            if merge_base is None:
                merge_base = subprocess.run(
                    ["git", "merge-base", "HEAD", "MERGE_HEAD"], check=True,
                    capture_output=True, text=True).stdout.strip()
            blobs = {name: git_blob(f"{ref}:{path}") for name, ref in (
                ("base", merge_base), ("local", "HEAD"), ("upstream", "MERGE_HEAD"),
                ("merged", ":0"))}
            source = blobs["merged"]
        # Only modifications and reviewed upstream deletions are supported.
        if (blobs["local"] is None or source is None
                or (path in conflicts and blobs["base"] is None)):
            continue
        digests = {name: sha(value) for name, value in blobs.items()}
        matches = [r for r in catalog["resolutions"] if r["path"] == path and r["inputs"] == digests]
        if not matches:
            continue
        if len(matches) != 1:
            raise ValueError("Ambiguous reviewed resolution")
        rule = matches[0]
        patch_base = rule.get("patch_base", "merged" if "merged" in blobs else "local")
        if patch_base not in ("local", "upstream", "merged") or blobs.get(patch_base) is None:
            raise ValueError("Unsupported reviewed patch base")
        source = blobs[patch_base]
        patch_path = relative_path(rule["patch"])
        patch = git_blob("HEAD:" + patch_path)
        if patch is None or sha(patch) != rule["patch_sha256"]:
            raise ValueError("Reviewed resolution patch hash mismatch")
        # Apply off-tree. A corrupt rule cannot leave a partially repaired merge behind.
        with tempfile.TemporaryDirectory(prefix="morphe-reviewed-merge-") as directory:
            temporary = Path(directory)
            target = temporary / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source)
            patch_file = temporary / "reviewed.patch"
            patch_file.write_bytes(patch)
            result = subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf",
                                     "apply", "--no-index", "--allow-empty", "--whitespace=nowarn",
                                     "--include=" + path, str(patch_file)],
                                    cwd=temporary, capture_output=True)
            if result.returncode:
                raise ValueError("Reviewed resolution patch did not apply")
            resolved = target.read_bytes() if target.exists() else None
            if sha(resolved) != rule["result_sha256"]:
                raise ValueError("Reviewed resolution result hash mismatch")
        if resolved is None:
            Path(path).unlink()
        else:
            Path(path).write_bytes(resolved)
        subprocess.run(["git", "add", "--", path], check=True, capture_output=True)
        repaired.append(rule["id"])
        requirements.update(rule.get("requires", []))
    if requirements - set(repaired):
        raise ValueError("Reviewed resolution dependency did not match")
    return repaired
