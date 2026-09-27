#!/usr/bin/env python3
"""Track a stable upstream merge conflict once per release, without repeated failed runs."""
import json
import os
from pathlib import Path
import re
import subprocess


def issue_details(report: dict, repository: str, run_id: str) -> tuple[str, str, str]:
    tag = report.get("tag", "")
    commit = report.get("commit", "")
    conflicts = report.get("conflicts", [])
    if (report.get("state") != "blocked" or not re.fullmatch(r"v\d+\.\d+\.\d+", tag)
            or not re.fullmatch(r"[0-9a-f]{40}", commit)
            or not isinstance(conflicts, list) or not conflicts):
        raise ValueError("Expected a blocked stable release with a commit and conflict list")
    marker = f"<!-- official-manager-sync:{tag} -->"
    title = f"官方 Manager {tag} 需要整合檢查"
    paths = "\n".join(f"- `{path.replace('`', '')}`" for path in conflicts)
    body = (
        f"{marker}\n\n"
        f"官方 [Morphe Manager {tag}](https://github.com/MorpheApp/morphe-manager/releases/tag/{tag}) "
        f"(`{commit}`) 與客製版有合併衝突，需要檢查。\n\n"
        f"現有已簽章版本保留；同步工作沒有發布未完成的合併。\n\n"
        f"衝突檔案：\n{paths}\n\n"
        f"整合時請保留客製授權及簽章流程，並在合併前建置、驗證兩種版本。\n\n"
        f"診斷執行紀錄：https://github.com/{repository}/actions/runs/{run_id}\n"
    )
    return marker, title, body


def main() -> None:
    report = json.loads(Path("verification/upstream-sync.json").read_text(encoding="utf-8"))
    repository = os.environ["GH_REPO"]
    marker, title, body = issue_details(report, repository, os.environ["GITHUB_RUN_ID"])
    try:
        listed = subprocess.run(
            ["gh", "issue", "list", "--repo", repository, "--state", "all", "--limit", "500",
             "--json", "number,body"], capture_output=True, text=True, encoding="utf-8", check=True)
        existing = next((issue for issue in json.loads(listed.stdout) if marker in issue.get("body", "")), None)
        if existing:
            print(f"Official Manager {report['tag']} is tracked in issue #{existing['number']}.")
            return
        created = subprocess.run(
            ["gh", "issue", "create", "--repo", repository, "--title", title, "--body", body],
            capture_output=True, text=True, encoding="utf-8", check=True)
        print(f"Official Manager {report['tag']} integration issue: {created.stdout.strip()}")
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"::warning::Could not record integration issue ({type(error).__name__}); diagnosis artifact retained.")


if __name__ == "__main__":
    main()
