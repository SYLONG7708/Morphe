"""Adapt the MicroG trust pin with a scoped, fail-closed dexlib2 transformation."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = {
    "org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar": "5a5c8982d8bd7d6e3bb1a0713049e3c78b719ec32b20f6b619885cec30a0dd61",
    "com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar": "1e301f0c52ac248b0b14fdc3d12283c77252d4d6f48521d572e7d8c4c2cc4ac7",
    "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar": "cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb",
}


def java_command() -> list[str]:
    cache = ROOT / "build/microg-trust"
    cache.mkdir(parents=True, exist_ok=True)
    jars = []
    for coordinate, expected in DEPENDENCIES.items():
        destination = cache / coordinate.rsplit("/", 1)[1]
        data = destination.read_bytes() if destination.is_file() else None
        if data is None:
            with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + coordinate, timeout=30) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"MicroG derivation dependency hash mismatch: {destination.name}")
        if not destination.exists():
            destination.write_bytes(data)
        jars.append(str(destination))
    executable = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    return [executable, "-cp", os.pathsep.join(jars), str(ROOT / "tools/CoexistenceMicroG.java")]


def derive_runtime(source: bytes, certificate: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="microg-trust-") as directory:
        root = Path(directory)
        input_path, output_path = root / "upstream.mpe", root / "derived.mpe"
        input_path.write_bytes(source)
        subprocess.run([*java_command(), str(input_path), str(output_path), certificate], check=True)
        return output_path.read_bytes()
