import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import repair_discovery
from repair_discovery import discovery


class DiscoveryRepairTest(unittest.TestCase):
    def manifest(self):
        return {
            "schema": 1, "channel": "stable", "published_at": "2026-09-15T10:00:00Z",
            "manager": {"artifacts": [
                {"package_name": "com.sylong.symorphe.nolicense", "version_name": "1.1.99-no-license",
                 "url": "https://example.invalid/no-license.apk", "signature_url": "https://example.invalid/no-license.sig"},
                {"package_name": "com.sylong.symorphe", "version_name": "1.1.99",
                 "url": "https://example.invalid/licensed.apk", "signature_url": "https://example.invalid/licensed.sig"},
            ]},
            "patches": {"version": "1.43.0.7870.1", "artifacts": [
                {"url": "https://example.invalid/patch.mpp", "signature_url": "https://example.invalid/patch.sig"},
            ]},
        }

    def test_repair_uses_package_identity_even_when_asset_order_changes(self):
        manager, patches = discovery(self.manifest())
        self.assertEqual("1.1.99", manager["version"])
        self.assertEqual("https://example.invalid/licensed.apk", manager["download_url"])
        self.assertEqual("1.43.0.7870.1", patches["version"])

    def test_unexpected_channel_cannot_replace_stable_pointers(self):
        manifest = self.manifest()
        manifest["channel"] = "nightly"
        with self.assertRaises(ValueError):
            discovery(manifest)


class DiscoveryVerificationTest(unittest.TestCase):
    def test_signature_failure_stops_before_manifest_is_used(self):
        def process(command, **kwargs):
            if command[0] == "java":
                raise subprocess.CalledProcessError(1, command)
            return subprocess.CompletedProcess(command, 0)

        with patch("repair_discovery.latest_tag", return_value="symorphe-v1.1.206"), \
             patch("repair_discovery.subprocess.run", side_effect=process) as run, \
             patch("repair_discovery.discovery") as parse:
            root = Path(__file__).resolve().parents[1]
            with self.assertRaises(subprocess.CalledProcessError):
                repair_discovery.verified_discovery(root)
            parse.assert_not_called()
            verifier = run.call_args.args[0]
            self.assertEqual("java", verifier[0])
            self.assertEqual(str(root / "app/src/main/res/raw/update_signing_cert.der"), verifier[4])
            self.assertTrue(run.call_args.kwargs["check"])


class DiscoveryPublicationTest(unittest.TestCase):
    """Exercise publication against local bare Git remotes, without network access."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="discovery-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.origin = self.root / "origin.git"
        self.writer = self.root / "writer"
        self.checkout = self.root / "checkout"
        self.git(self.root, "init", "--bare", "--initial-branch=main", str(self.origin))
        self.git(self.root, "init", "--initial-branch=main", str(self.writer))
        self.git(self.writer, "config", "user.name", "Fixture")
        self.git(self.writer, "config", "user.email", "fixture@example.invalid")
        self.git(self.writer, "config", "core.autocrlf", "false")
        (self.writer / "app").mkdir()
        (self.writer / "app/gradle.properties").write_text("version = 1.1.204\n")
        (self.writer / "source.txt").write_text("original source\n")
        self.write_release(self.writer, 204)
        self.git(self.writer, "add", ".")
        self.git(self.writer, "commit", "-qm", "original")
        self.git(self.writer, "remote", "add", "origin", str(self.origin))
        self.git(self.writer, "push", "-q", "origin", "HEAD:main")
        self.git(self.root, "clone", "-q", "--config", "core.autocrlf=false",
                 str(self.origin), str(self.checkout))
        self.original_head = self.git(self.checkout, "rev-parse", "HEAD")
        self.verified = self.enterContext(patch("repair_discovery.verified_discovery",
                                               return_value=self.release(206)))
        self.latest = self.enterContext(patch("repair_discovery.latest_tag",
                                             return_value=self.release(206)[0]))

    @staticmethod
    def git(root, *args):
        return subprocess.check_output(["git", "-C", str(root), *args],
                                       text=True, stderr=subprocess.PIPE).strip()

    @staticmethod
    def release(number):
        manifest = DiscoveryRepairTest().manifest()
        tag = f"symorphe-v1.1.{number}"
        manifest["published_at"] = f"2026-10-20T{number % 24:02}:00:00Z"
        for artifact in manifest["manager"]["artifacts"]:
            edition = "no-license" if artifact["package_name"].endswith(".nolicense") else "licensed"
            artifact["version_name"] = f"1.1.{number}" + ("-no-license" if edition == "no-license" else "")
            artifact["url"] = f"https://github.com/SYLONG7708/Morphe/releases/download/{tag}/{edition}.apk"
            artifact["signature_url"] = artifact["url"] + ".sig"
        artifact = manifest["patches"]["artifacts"][0]
        artifact["url"] = f"https://github.com/SYLONG7708/Morphe/releases/download/{tag}/patches.mpp"
        artifact["signature_url"] = artifact["url"] + ".sig"
        return tag, *discovery(manifest)

    def write_release(self, root, number):
        for filename, data in zip(repair_discovery.DISCOVERY_FILES, self.release(number)[1:]):
            repair_discovery.write_json(root / filename, data)

    def advance(self, number=205, source="concurrent source\n"):
        self.write_release(self.writer, number)
        (self.writer / "source.txt").write_text(source)
        self.git(self.writer, "add", ".")
        self.git(self.writer, "commit", "-qm", "concurrent update")
        self.git(self.writer, "push", "-q", "origin", "HEAD:main")
        return self.git(self.writer, "rev-parse", "HEAD")

    def assert_release(self, number):
        for filename, expected in zip(repair_discovery.DISCOVERY_FILES, self.release(number)[1:]):
            actual = json.loads(self.git(self.origin, "show", f"main:{filename}"))
            self.assertEqual(expected, actual)
        self.assertEqual(self.original_head, self.git(self.checkout, "rev-parse", "HEAD"))

    def test_stale_checkout_publishes_on_current_main_without_touching_build_or_index(self):
        base = self.advance()
        (self.checkout / "app/gradle.properties").write_text("version = 1.1.206\n")
        (self.checkout / "staged.txt").write_text("unrelated staged change\n")
        self.git(self.checkout, "add", "staged.txt")
        before = self.git(self.checkout, "status", "--porcelain")
        index = self.git(self.checkout, "write-tree")

        repair_discovery.publish(self.checkout)

        self.assert_release(206)
        self.assertEqual(base, self.git(self.origin, "rev-parse", "main^"))
        self.assertEqual("concurrent source", self.git(self.origin, "show", "main:source.txt"))
        self.assertEqual(set(repair_discovery.DISCOVERY_FILES),
                         set(self.git(self.origin, "diff", "--name-only", base, "main").splitlines()))
        self.assertEqual(before, self.git(self.checkout, "status", "--porcelain"))
        self.assertEqual(index, self.git(self.checkout, "write-tree"))
        self.assertEqual("version = 1.1.206\n", (self.checkout / "app/gradle.properties").read_text())

    def test_retry_rebuilds_commit_after_main_advances_before_push(self):
        self.advance()
        real_git = repair_discovery.git
        pushes = []

        def racing_git(root, *args, **kwargs):
            if args[0] == "push":
                pushes.append(args)
                if len(pushes) == 1:
                    self.advance(source="new source during push\n")
            return real_git(root, *args, **kwargs)

        with patch("repair_discovery.git", side_effect=racing_git):
            repair_discovery.publish(self.checkout)

        self.assert_release(206)
        self.assertEqual(2, len(pushes))
        self.assertEqual(2, self.verified.call_count)
        self.assertEqual("new source during push", self.git(self.origin, "show", "main:source.txt"))
        self.assertEqual(self.git(self.writer, "rev-parse", "HEAD"),
                         self.git(self.origin, "rev-parse", "main^"))

    def test_retry_reverifies_new_release_instead_of_overwriting_it_with_old_metadata(self):
        self.advance()
        real_git = repair_discovery.git

        def racing_git(root, *args, **kwargs):
            if args[0] == "push":
                self.advance(207)
                self.verified.return_value = self.release(207)
                self.latest.return_value = self.release(207)[0]
            return real_git(root, *args, **kwargs)

        with patch("repair_discovery.git", side_effect=racing_git):
            repair_discovery.publish(self.checkout)

        self.assert_release(207)
        self.assertEqual(2, self.verified.call_count)
        self.assertEqual(self.git(self.writer, "rev-parse", "HEAD"),
                         self.git(self.origin, "rev-parse", "main"))

    def test_latest_release_change_during_verification_is_retried(self):
        self.verified.side_effect = [self.release(206), self.release(207)]
        self.latest.return_value = self.release(207)[0]

        repair_discovery.publish(self.checkout)

        self.assert_release(207)
        self.assertEqual(2, self.verified.call_count)
        self.assertEqual(self.original_head, self.git(self.origin, "rev-parse", "main^"))

    def test_current_metadata_is_a_noop_even_with_a_stale_checkout(self):
        base = self.advance(206)
        with patch("repair_discovery.git", wraps=repair_discovery.git) as call:
            repair_discovery.publish(self.checkout)
        self.assert_release(206)
        self.assertEqual(base, self.git(self.origin, "rev-parse", "main"))
        self.assertFalse(any(c.args[1] == "push" for c in call.call_args_list))

    def test_signature_failure_leaves_remote_and_checkout_unchanged(self):
        base = self.advance()
        self.verified.side_effect = subprocess.CalledProcessError(1, ["java", "VerifyFile"])
        with self.assertRaises(subprocess.CalledProcessError):
            repair_discovery.publish(self.checkout)
        self.assert_release(205)
        self.assertEqual(base, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual("", self.git(self.checkout, "status", "--porcelain"))
        self.assertEqual(1, self.verified.call_count)

    def test_push_failure_without_remote_advance_is_not_hidden_or_retried(self):
        real_git = repair_discovery.git
        failure = subprocess.CalledProcessError(128, ["git", "push"])

        def refused_git(root, *args, **kwargs):
            if args[0] == "push":
                raise failure
            return real_git(root, *args, **kwargs)

        with patch("repair_discovery.git", side_effect=refused_git):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                repair_discovery.publish(self.checkout)
        self.assertIs(failure, raised.exception)
        self.assert_release(204)
        self.assertEqual(self.original_head, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual(1, self.verified.call_count)

    def test_repeated_push_races_have_a_bounded_retry_budget(self):
        real_git = repair_discovery.git
        pushes = []

        def racing_git(root, *args, **kwargs):
            if args[0] == "push":
                pushes.append(args)
                self.advance(source=f"concurrent change {len(pushes)}\n")
            return real_git(root, *args, **kwargs)

        with patch("repair_discovery.git", side_effect=racing_git):
            with self.assertRaisesRegex(RuntimeError, "kept changing"):
                repair_discovery.publish(self.checkout)
        self.assert_release(205)
        self.assertEqual(3, len(pushes))
        self.assertEqual(3, self.verified.call_count)
        self.assertEqual(self.git(self.writer, "rev-parse", "HEAD"),
                         self.git(self.origin, "rev-parse", "main"))
