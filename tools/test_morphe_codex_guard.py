import copy
import base64
import io
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import zipfile

import morphe_codex_guard as guard


class MorpheGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = {"repository": guard.REPOSITORY, "runtime_root": str(self.root),
                       "git": "git", "gh": "gh", "python": "python", "codex": "codex"}
        self.run = {"id": 123, "head_sha": "a" * 40, "head_branch": "main",
                    "repository": {"full_name": guard.REPOSITORY},
                    "head_repository": {"full_name": guard.REPOSITORY},
                    "path": ".github/workflows/release.yml", "event": "schedule",
                    "status": "completed", "conclusion": "failure",
                    "updated_at": "2026-10-20T01:00:00Z", "created_at": "2026-10-20T00:00:00Z"}
        self.now = guard.timestamp("2026-10-20T02:00:00Z")

    def tearDown(self):
        self.temp.cleanup()

    def test_same_failure_on_repeated_schedule_deduplicates_by_source(self):
        first = guard.incident_for(self.run, "release.yml", self.now)
        self.run["id"] = 456
        second = guard.incident_for(self.run, "release.yml", self.now)
        self.assertEqual(first["key"], second["key"])

    def test_pr_external_branch_success_and_recent_failures_do_not_launch(self):
        changes = ({"event": "pull_request"}, {"head_branch": "feature"},
                   {"head_repository": {"full_name": "external/fork"}},
                   {"conclusion": "success"}, {"status": "in_progress"},
                   {"updated_at": "2026-10-20T01:59:00Z"})
        for change in changes:
            self.assertIsNone(guard.incident_for(dict(self.run, **change), "release.yml", self.now))

    def test_green_sync_with_blocked_diagnosis_is_an_incident(self):
        self.run.update(path=".github/workflows/upstream-sync.yml", conclusion="success")
        result = guard.incident_for(self.run, "upstream-sync.yml", self.now,
                                   {"blocked": True, "tag": "v1.35.0"})
        self.assertEqual("upstream_integration_blocked", result["reason"])

    def test_daily_budget_recovers_next_day_and_respects_cooldown(self):
        self.assertFalse(guard.eligible({"attempts": [self.now - 60] * 3}, self.now))
        self.assertFalse(guard.eligible({"next_attempt": self.now + 10}, self.now))
        self.assertTrue(guard.eligible({"attempts": [self.now - 86401] * 3}, self.now))

    def test_kernel_lock_prevents_duplicate_workers_and_releases_after_exit(self):
        with guard.exclusive_lock(self.root / "lock") as first:
            self.assertTrue(first)
            with guard.exclusive_lock(self.root / "lock") as second:
                self.assertFalse(second)
        with guard.exclusive_lock(self.root / "lock") as after:
            self.assertTrue(after)

    def test_persist_attempt_before_worker_crash_and_do_not_claim_success(self):
        instance = guard.Guard(self.config)
        incident = guard.incident_for(self.run, "release.yml", self.now)
        instance.scan = Mock(return_value=({}, [incident]))
        def crash(_):
            state = guard.read_json(instance.state_file)
            self.assertEqual("running", state["incidents"][incident["key"]]["phase"])
            raise RuntimeError("agent could not authenticate")
        instance.repair = crash
        instance.cycle()
        self.assertEqual("unresolved", guard.read_json(instance.status_file)["phase"])
        instance.repair = Mock()
        instance.cycle()
        instance.repair.assert_not_called()

    def test_successful_agent_is_not_enough_when_cloud_verification_fails(self):
        instance = guard.Guard(self.config)
        instance.api = Mock(return_value={"workflow_runs": [
            dict(self.run, head_sha="a" * 40, created_at=guard.utc(), html_url="https://example.invalid/run")
        ]})
        with self.assertRaisesRegex(RuntimeError, "Verification failed"):
            instance.wait_run("release.yml", "main", "a" * 40, time.time())

    def fixture_source(self):
        before = {}
        for name in guard.PROTECTED:
            before[name] = b"fixed-trust-and-identity"
        for name in guard.WORKFLOWS:
            before[f".github/workflows/{name}"] = b"on:\n  workflow_dispatch:\n  schedule:\n    - cron: '17 2 * * *'\n"
        before[".github/workflows/build.yml"] += (
            b"testDebugUnitTest lintRelease assembleRelease -PnoDeviceLicense unittest discover\n")
        for name, value in before.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(value)
        return before

    def test_schedule_signing_identity_and_required_build_checks_are_preserved(self):
        before = self.fixture_source()
        guard.validate_candidate(self.root, before)
        for name, value in (
            (guard.PROTECTED[0], b"replacement-key"),
            (".github/workflows/release.yml", b"on:\n  workflow_dispatch:\n"),
            (".github/workflows/build.yml", before[".github/workflows/build.yml"].replace(b"lintRelease", b""))):
            (self.root / name).write_bytes(value)
            with self.assertRaises(RuntimeError):
                guard.validate_candidate(self.root, before)
            (self.root / name).write_bytes(before[name])

    def test_blocked_artifact_uses_real_state_field_without_extracting_code(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as archive:
            archive.writestr("upstream-sync.json", json.dumps({"state": "blocked", "tag": "v1.35.0"}))
            archive.writestr("../../unexpected.py", "must not be executed")
        instance = guard.Guard(self.config)
        instance.api = Mock(return_value={"artifacts": [
            {"name": "official-sync-diagnosis-1", "expired": False, "size_in_bytes": 500, "id": 4}]})
        with patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, content.getvalue())):
            self.assertEqual("v1.35.0", instance.blocked_sync(self.run)["tag"])
        self.assertFalse((self.root / "unexpected.py").exists())

    def test_diagnosis_only_and_owner_stop_do_not_launch_codex(self):
        instance = guard.Guard(self.config)
        incident = guard.incident_for(self.run, "release.yml", self.now)
        instance.scan = Mock(return_value=({}, [incident]))
        instance.repair = Mock()
        instance.cycle(diagnose_only=True)
        instance.repair.assert_not_called()
        (self.root / "STOP").touch()
        instance.cycle()
        self.assertEqual("paused_by_owner", guard.read_json(instance.status_file)["phase"])
        instance.repair.assert_not_called()

    def test_public_discovery_is_checked_against_verified_manifest(self):
        instance = guard.Guard(dict(self.config, java="java"))
        probe = self.root / "release-probe"
        probe.mkdir()
        tag = "symorphe-v1.1.182"
        base = f"https://github.com/{guard.REPOSITORY}/releases/download/{tag}/"
        def artifact(name, **extra):
            return {"url": base + name, "signature_url": base + name + ".sig",
                    "sha256": "a" * 64, **extra}
        manager = artifact("manager.apk", package_name="com.sylong.symorphe", version_name="1.1.182")
        patches = artifact("patches.mpp")
        microg = artifact("microg.apk")
        guard.write_json(probe / "update-manifest.json", {
            "schema": 1, "channel": "stable", "manager": {"artifacts": [manager]},
            "patches": {"version": "1.47", "artifacts": [patches]}, "microg": {"artifacts": [microg]}})
        assets = [{"name": name, "digest": "sha256:" + "a" * 64}
                  for name in ("manager.apk", "patches.mpp", "microg.apk")]
        assets += [{"name": a["name"] + ".sig"} for a in list(assets)]
        responses = [{"tag_name": tag, "html_url": base, "assets": assets}]
        for a, version in ((manager, "1.1.182"), (patches, "1.47")):
            responses.append({"content": base64.b64encode(json.dumps(
                {"version": version, "download_url": a["url"],
                 "signature_download_url": a["signature_url"]}).encode()).decode()})
        instance.command = Mock()
        instance.api = Mock(side_effect=copy.deepcopy(responses))
        self.assertEqual(tag, instance.audit_release()["tag"])
        instance.api = Mock(side_effect=copy.deepcopy(responses))
        instance.command = Mock(side_effect=RuntimeError("Signature verification failed"))
        with self.assertRaisesRegex(RuntimeError, "Signature verification failed"):
            instance.audit_release()
        instance.command = Mock()
        bad = copy.deepcopy(responses)
        bad[0]["assets"][0]["digest"] = "sha256:" + "b" * 64
        instance.api = Mock(side_effect=bad)
        with self.assertRaisesRegex(RuntimeError, "digest"):
            instance.audit_release()

    def test_candidate_is_not_published_when_cloud_build_fails_or_main_advances(self):
        before = self.fixture_source()
        for scenario in ("build_failed", "main_advanced"):
            instance = guard.Guard(self.config)
            previous = str(self.root / "previous-attempt")
            instance.state["incidents"][scenario] = {"last_job": previous}
            commands = []
            committed = False
            def execute(command, **kwargs):
                nonlocal committed
                commands.append(command)
                if command[:2] == ["git", "clone"]:
                    source = Path(command[-1])
                    for name, content in before.items():
                        target = source / name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(content)
                elif command[0] == "codex":
                    result = Path(command[command.index("-o") + 1])
                    guard.write_json(result, {"outcome": "candidate_ready", "summary": "fixed", "tests": []})
                elif command[:2] == ["git", "rev-parse"]:
                    return ("b" if committed else "a") * 40
                elif command[:2] == ["git", "status"]:
                    return " M tools/fix.py"
                elif "--name-only" in command:
                    return "tools/fix.py\n"
                elif "commit" in command:
                    committed = True
                return ""
            instance.command = execute
            refs = [{"object": {"sha": "a" * 40}}, {"object": {"sha": "c" * 40}}]
            instance.api = Mock(side_effect=refs)
            if scenario == "build_failed":
                instance.wait_run = Mock(side_effect=RuntimeError("Verification failed"))
            else:
                instance.wait_run = Mock(return_value={"conclusion": "success"})
            incident = {"key": scenario, "workflow": "release.yml", "reason": "workflow_failed", "run_id": 1}
            with self.assertRaises(RuntimeError):
                instance.repair(incident)
            self.assertFalse(any("HEAD:refs/heads/main" in command for command in commands))
            self.assertTrue(any(command[:3] == ["gh", "workflow", "run"] for command in commands))
            saved_job = Path(guard.read_json(instance.state_file)["incidents"][scenario]["last_job"])
            self.assertIn(previous, (saved_job / "prompt.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
