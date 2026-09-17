"""Do not skip a scheduled release when only Manager source changed."""
import re
import subprocess
import sys

EXCLUDED = ["app-release.json", "patches-bundle.json", ".github/maintenance-heartbeat.txt", "*.md", "docs/**"]


def unchanged(tag: str) -> bool:
    if not re.fullmatch(r"symorphe-v\d+\.\d+\.\d+", tag):
        return False
    ref = f"refs/tags/{tag}^{{commit}}"
    if subprocess.run(["git", "rev-parse", "--verify", ref], capture_output=True).returncode:
        return False
    result = subprocess.run(["git", "diff", "--quiet", ref, "HEAD", "--", ".",
                             *[":(top,exclude,glob)" + path for path in EXCLUDED]], capture_output=True)
    return result.returncode == 0


if __name__ == "__main__":
    sys.exit(0 if len(sys.argv) == 2 and unchanged(sys.argv[1]) else 1)
