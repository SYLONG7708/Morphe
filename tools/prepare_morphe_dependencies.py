#!/usr/bin/env python3
"""Restore missing public Morphe Maven dependencies from their exact official tags."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = {
    "morphe-patcher": ("MorpheApp/morphe-patcher", "app/morphe/morphe-patcher"),
    "morphe-library": ("MorpheApp/morphe-library", "app/morphe/morphe-library"),
    "jadb": ("MorpheApp/jadb", "app/morphe/jadb"),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def restore(root: Path = ROOT) -> None:
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    provenance = root / "config/dependency-provenance.json"
    records = json.loads(provenance.read_text(encoding="utf-8")) if provenance.exists() else {}
    for name, (repository, coordinate) in DEPENDENCIES.items():
        match = re.search(rf'(?m)^{re.escape(name)}\s*=\s*"(\d+\.\d+\.\d+)"\s*$', catalog)
        if not match:
            if name == "jadb":
                continue  # Indirect dependency already pinned by morphe-library's POM.
            raise ValueError(f"No stable version declared for {name}")
        version = match.group(1)
        target = root / "vendor/m2" / coordinate / version
        jar = target / f"{name}-{version}.jar"
        if jar.is_file() and (target / f"{name}-{version}.pom").is_file():
            recorded = records.get(f"{name}:{version}")
            if recorded:
                for filename, digest in recorded["sha256"].items():
                    if sha256(target / filename) != digest:
                        raise ValueError(f"Vendored dependency hash mismatch: {filename}")
            continue
        with tempfile.TemporaryDirectory(prefix=f"{name}-", dir=root) as staging:
            source = Path(staging) / "source"
            subprocess.run(["git", "clone", "--depth", "1", "--branch", f"v{version}",
                            f"https://github.com/{repository}.git", str(source)], check=True)
            commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
            wrapper = source / ("gradlew.bat" if os.name == "nt" else "gradlew")
            if os.name != "nt":
                wrapper.chmod(0o755)
            # Generate official artifacts without the private GPG signing/publish task.
            publication = f"{name}-publication"
            subprocess.run([str(wrapper), "test", "jar", "sourcesJar",
                            f"generatePomFileFor{publication.capitalize()}Publication",
                            f"generateMetadataFileFor{publication.capitalize()}Publication",
                            "--no-daemon", "--console=plain"], cwd=source, check=True)
            target.mkdir(parents=True, exist_ok=True)
            for suffix in (".jar", "-sources.jar"):
                shutil.copyfile(source / "build/libs" / f"{name}-{version}{suffix}",
                                target / f"{name}-{version}{suffix}")
            for original, suffix in (("pom-default.xml", ".pom"), ("module.json", ".module")):
                shutil.copyfile(source / "build/publications" / publication / original,
                                target / f"{name}-{version}{suffix}")
            if not jar.is_file():
                raise RuntimeError(f"Official {name} build did not publish {jar.name}")
            records[f"{name}:{version}"] = {
                "repository": repository, "tag": f"v{version}", "commit": commit,
                "sha256": {p.name: sha256(p) for p in sorted(target.iterdir()) if p.is_file()},
            }
    provenance.write_text(json.dumps(records, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    restore()
