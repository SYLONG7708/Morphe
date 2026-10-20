#!/usr/bin/env python3
"""Retry bounded, recognizable infrastructure failures; never retry failed tests as a fix."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

TRANSIENT = re.compile(
    r"timed? out|timeout|connection (?:reset|refused|closed)|temporary failure|"
    r"remote host terminated|unexpected end of file|HTTP[^\n]*(?:429|500|502|503|504)|"
    r"status code[^\n]*(?:429|500|502|503|504)|could not (?:GET|HEAD)|"
    r"unable to access[^\n]*(?:resolve host|connection)|network is unreachable",
    re.IGNORECASE,
)
PERMANENT = re.compile(
    r"signature verification failed|hash mismatch|compilation error|"
    r"tests? (?:failed|completed, [1-9].* failed)|failed to find package|"
    r"status code[^\n]*(?:401|403)|"
    r"requires morphe-patcher|keystore was tampered|password was incorrect|"
    r"Upstream merge needs a code change|Reviewed resolution.*(?:mismatch|did not apply)|"
    r"requires a clean checkout|CONFLICT \(|"
    r"ValueError:|AssertionError:|TypeError:|Unreviewed package identities",
    re.IGNORECASE,
)
MISSING_MODULE = re.compile(
    r"Could not find ([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+):"
    r"([A-Za-z0-9_-]+):([0-9][A-Za-z0-9_.-]*?)(?:\.\s*(?:\n|$)|\s*$)",
    re.MULTILINE,
)


def central_artifact_exists(group: str, artifact: str, version: str) -> bool:
    """Confirm exact published coordinates; a real missing version must still fail."""
    base = ("https://repo.maven.apache.org/maven2/"
            f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}")
    try:
        for suffix in (".pom", ".jar"):
            with urllib.request.urlopen(
                urllib.request.Request(base + suffix, method="HEAD"), timeout=15
            ) as response:
                if response.status != 200:
                    return False
        return True
    except (OSError, urllib.error.URLError):
        return False


def published_dependencies_available(output: str, probe=None) -> bool:
    if PERMANENT.search(output):
        return False
    modules = sorted(set(MISSING_MODULE.findall(output)))
    # Unknown formats / excessive failures are diagnostic incidents, not evidence
    # that every missing module can be repaired by discarding negative cache entries.
    missing_lines = [line for line in output.splitlines() if "Could not find " in line]
    if not modules or len(modules) > 8 or any(not MISSING_MODULE.search(line) for line in missing_lines):
        return False
    probe = probe or central_artifact_exists
    return all(probe(*module) for module in modules)


def refreshed_gradle_command(command: list[str]) -> list[str] | None:
    if (not command or Path(command[0]).name.lower() not in
            {"gradlew", "gradlew.bat", "gradle", "gradle.bat"} or "--offline" in command):
        return None
    result = [part for part in command if part != "--configuration-cache"]
    for option in ("--refresh-dependencies", "--no-configuration-cache"):
        if option not in result:
            result.append(option)
    return result


def retryable(output: str) -> bool:
    return bool(TRANSIENT.search(output)) and not PERMANENT.search(output)


def run(command: list[str], attempts: int = 3, delay: float = 10) -> int:
    command = list(command)
    for attempt in range(1, attempts + 1):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
        print(result.stdout, end="", flush=True)
        if result.returncode == 0 or attempt == attempts:
            return result.returncode
        refreshed = refreshed_gradle_command(command)
        if refreshed is not None and published_dependencies_available(result.stdout):
            command = refreshed
            print("::warning::Exact missing Maven artifacts are published; refresh Gradle dependency metadata.",
                  flush=True)
        elif not retryable(result.stdout):
            return result.returncode
        print(f"::warning::Transient infrastructure failure; retry {attempt + 1}/{attempts}", flush=True)
        time.sleep(min(delay * 2 ** (attempt - 1), 60))
    return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or not 1 <= args.attempts <= 3:
        parser.error("a command and 1..3 attempts are required")
    sys.exit(run(command, args.attempts))
