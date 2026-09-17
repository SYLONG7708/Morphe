"""Apply reviewed conflict repairs only to the exact three audited Git blobs."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile


def git_blob(spec: str) -> bytes | None:
    result = subprocess.run(["git", "show", spec], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


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
    for path in conflicts:
        relative_path(path)
        blobs = {name: git_blob(f":{stage}:{path}")
                 for stage, name in ((1, "base"), (2, "local"), (3, "upstream"))}
        if any(value is None for value in blobs.values()):
            continue
        digests = {name: sha(value) for name, value in blobs.items()}
        matches = [r for r in catalog["resolutions"] if r["path"] == path and r["inputs"] == digests]
        if not matches:
            continue
        if len(matches) != 1:
            raise ValueError("Ambiguous reviewed resolution")
        rule = matches[0]
        patch_path = relative_path(rule["patch"])
        patch = git_blob("HEAD:" + patch_path)
        if patch is None or sha(patch) != rule["patch_sha256"]:
            raise ValueError("Reviewed resolution patch hash mismatch")
        # Apply off-tree. A corrupt rule cannot leave a partially repaired merge behind.
        with tempfile.TemporaryDirectory(prefix="morphe-reviewed-merge-") as directory:
            temporary = Path(directory)
            target = temporary / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(blobs["local"])
            patch_file = temporary / "reviewed.patch"
            patch_file.write_bytes(patch)
            result = subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf",
                                     "apply", "--no-index", "--whitespace=nowarn",
                                     "--include=" + path, str(patch_file)],
                                    cwd=temporary, capture_output=True)
            if result.returncode:
                raise ValueError("Reviewed resolution patch did not apply")
            resolved = target.read_bytes()
            if sha(resolved) != rule["result_sha256"]:
                raise ValueError("Reviewed resolution result hash mismatch")
        Path(path).write_bytes(resolved)
        subprocess.run(["git", "add", "--", path], check=True, capture_output=True)
        repaired.append(rule["id"])
    return repaired
