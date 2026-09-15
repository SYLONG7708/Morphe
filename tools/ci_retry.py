#!/usr/bin/env python3
"""Retry bounded, recognizable infrastructure failures; never retry failed tests as a fix."""
import argparse
import re
import subprocess
import sys
import time

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
    r"requires morphe-patcher|keystore was tampered|password was incorrect",
    re.IGNORECASE,
)


def retryable(output: str) -> bool:
    return bool(TRANSIENT.search(output)) and not PERMANENT.search(output)


def run(command: list[str], attempts: int = 3, delay: float = 10) -> int:
    for attempt in range(1, attempts + 1):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
        print(result.stdout, end="", flush=True)
        if result.returncode == 0 or not retryable(result.stdout) or attempt == attempts:
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
