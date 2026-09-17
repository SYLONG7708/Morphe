#!/usr/bin/env python3
"""Diagnose trusted main-branch sync/release runs and request bounded retries."""
import json
import os
from pathlib import Path
import re
import subprocess
import time

from ci_retry import PERMANENT

WORKFLOWS = {".github/workflows/upstream-sync.yml", ".github/workflows/release.yml"}
HARD_FAILURE = re.compile(
    r"Upstream merge needs a code change|Reviewed resolution.*(?:mismatch|did not apply)|"
    r"Official merge failed|requires a clean checkout|CONFLICT \(|"
    r"FAILURES!!!|[1-9]\d* tests? failed|FAILED \(failures=|"
    r"Unresolved reference|e: file:|Lint found errors|"
    r"Resource not accessible by integration|Bad credentials", re.IGNORECASE,
)


def failure_output(logs: str) -> str:
    # gh can fall back to whole-job logs ("UNKNOWN STEP"). Earlier successful
    # regression tests/repairs may themselves print example failure messages.
    # Classify the command containing the final runner error, not those examples.
    error = logs.rfind("##[error]")
    if error >= 0:
        start = logs.rfind("##[group]Run ", 0, error)
        if start >= 0:
            end = logs.find("\n", error)
            return logs[start:end if end >= 0 else len(logs)]
    return logs


def decision(run: dict, repository: str, current_sha: str, logs: str) -> dict:
    result = {"run_id": run["id"], "attempt": run.get("run_attempt", 1),
              "workflow": run.get("path"), "action": "retain", "reason": ""}
    if (run.get("repository", {}).get("full_name") != repository or
        run.get("head_repository", {}).get("full_name") != repository or
        run.get("head_branch") != "main" or run.get("path") not in WORKFLOWS or
        run.get("event") not in {"push", "schedule", "workflow_dispatch"}):
        result["reason"] = "Untrusted or unsupported run; no action taken."
    elif run.get("status") != "completed" or run.get("conclusion") not in {"failure", "timed_out", "startup_failure"}:
        result["reason"] = "Run does not need recovery."
    elif run.get("head_sha") != current_sha:
        result["reason"] = "A newer main exists; obsolete code will not be rerun."
    elif PERMANENT.search(logs) or HARD_FAILURE.search(logs):
        result["reason"] = "Code, compatibility, credentials or verification needs attention; current release retained."
    elif result["attempt"] >= 3:
        result["reason"] = "Three attempts exhausted; current release retained until the next scheduled check."
    else:
        result.update(action="retry", reason="Retry on a fresh runner within the three-attempt budget.")
    return result


def api(endpoint: str, *, post: bool = False):
    command = ["gh", "api"]
    if post:
        command += ["--method", "POST"]
    output = subprocess.check_output(command + [endpoint], text=True, encoding="utf-8")
    return json.loads(output) if output.strip() else None


def main() -> None:
    repository = os.environ["GH_REPO"]
    manual = os.environ.get("FAILED_RUN", "")
    if manual:
        if not manual.isdigit():
            raise ValueError("Failed run must be numeric")
        run_id = int(manual)
    else:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        run_id = int(event["workflow_run"]["id"])
    run = api(f"repos/{repository}/actions/runs/{run_id}")
    current = api(f"repos/{repository}/git/ref/heads/main")["object"]["sha"]
    check = decision(run, repository, current, "")
    # Logs are classified, never executed or printed. Do not consume failed-run artifacts/code.
    log_text = ""
    if check["action"] == "retry":
        logs = subprocess.run(["gh", "run", "view", str(run_id), "--repo", repository,
                               "--attempt", str(run.get("run_attempt", 1)), "--log-failed"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        log_text = failure_output(logs.stdout) if logs.returncode == 0 else ""
        check = decision(run, repository, current, log_text)
    if check["action"] == "retry":
        time.sleep(30)
        # Races must not exceed the budget or run obsolete code.
        fresh = api(f"repos/{repository}/actions/runs/{run_id}")
        current = api(f"repos/{repository}/git/ref/heads/main")["object"]["sha"]
        if fresh.get("run_attempt") != run.get("run_attempt"):
            check.update(action="retain", reason="Another retry already started.")
        else:
            check = decision(fresh, repository, current, log_text)
            if check["action"] == "retry":
                api(f"repos/{repository}/actions/runs/{run_id}/rerun-failed-jobs", post=True)
                check["action"] = "retry_requested"
    report = Path("verification/cloud-recovery.json")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(check, indent=2) + "\n", encoding="utf-8")
    summary = f"Run {run_id}, attempt {check['attempt']}: {check['action']}. {check['reason']}"
    print(summary)
    if destination := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(destination, "a", encoding="utf-8") as stream:
            stream.write(summary + "\n")


if __name__ == "__main__":
    main()
