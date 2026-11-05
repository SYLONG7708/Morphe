import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import publish_upstream


class SourcePublicationTest(unittest.TestCase):
    """Exercise source publication against local bare Git remotes only."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="source-publication-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.origin = self.root / "origin.git"
        self.writer = self.root / "writer"
        self.checkout = self.root / "checkout"
        self.git(self.root, "init", "--bare", "--initial-branch=main", str(self.origin))
        self.git(self.root, "init", "--initial-branch=main", str(self.writer))
        self.identity(self.writer)
        (self.writer / ".gitattributes").write_text("* text=auto\n")
        (self.writer / "source.txt").write_text("original source\n")
        (self.writer / "app-release.json").write_text("old discovery\n")
        (self.writer / "patches-bundle.json").write_text("old patches\n")
        (self.writer / "config").mkdir()
        (self.writer / "config/upstream-manager.json").write_text("old upstream\n")
        (self.writer / "config/dependency-provenance.json").write_text("{}\n")
        (self.writer / "vendor/m2").mkdir(parents=True)
        (self.writer / "vendor/m2/artifact.txt").write_text("verified artifact\n")
        self.commit(self.writer, "original")
        self.git(self.writer, "remote", "add", "origin", str(self.origin))
        self.git(self.writer, "push", "-q", "origin", "HEAD:main")
        self.git(self.root, "clone", "-q", str(self.origin), str(self.checkout))
        self.identity(self.checkout)
        self.base = self.git(self.checkout, "rev-parse", "HEAD")
        self.git(self.writer, "switch", "-qc", "official")
        (self.writer / "source.txt").write_text("official stable source\n")
        self.commit(self.writer, "official stable update")
        self.official = self.git(self.writer, "rev-parse", "HEAD")
        self.git(self.writer, "switch", "-q", "main")
        self.git(self.checkout, "fetch", "-q", str(self.writer), "official")
        self.git(self.checkout, "merge", "--no-ff", "--no-commit", "FETCH_HEAD")
        (self.checkout / "config/upstream-manager.json").write_text("new upstream\n")
        self.commit(self.checkout, "verified official source merge")
        self.candidate = self.git(self.checkout, "rev-parse", "HEAD")
        self.log = self.root / "verification.log"
        self.verifier = self.root / "verify.py"
        self.verifier.write_text(
            "from pathlib import Path\nimport subprocess, sys\n"
            "tree = subprocess.check_output(['git', 'write-tree'], text=True).strip()\n"
            "with Path(sys.argv[1]).open('a') as log:\n    log.write(tree + '\\n')\n")
        self.command = [sys.executable, str(self.verifier), str(self.log)]
        self.sequence = 0

    @staticmethod
    def git(root, *args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True,
                                       encoding="utf-8", stderr=subprocess.PIPE).strip()

    def identity(self, root):
        self.git(root, "config", "user.name", "Fixture")
        self.git(root, "config", "user.email", "fixture@example.invalid")
        self.git(root, "config", "core.autocrlf", "false")

    def commit(self, root, message):
        self.git(root, "add", ".")
        self.git(root, "commit", "-qm", message)

    def advance(self, conflict=False):
        self.sequence += 1
        (self.writer / "app-release.json").write_text(f"signed discovery {self.sequence}\n")
        (self.writer / "patches-bundle.json").write_text(f"signed patches {self.sequence}\n")
        (self.writer / ("source.txt" if conflict else "concurrent.txt")).write_text(
            f"concurrent source {self.sequence}\n")
        self.commit(self.writer, f"concurrent update {self.sequence}")
        self.git(self.writer, "push", "-q", "origin", "HEAD:main")
        return self.git(self.writer, "rev-parse", "HEAD")

    def publish(self):
        publish_upstream.publish(self.checkout, self.command)

    def assert_published(self, validations):
        published = self.git(self.origin, "rev-parse", "main")
        self.assertEqual(self.git(self.checkout, "rev-parse", "HEAD"), published)
        self.git(self.origin, "merge-base", "--is-ancestor", self.official, published)
        self.git(self.origin, "merge-base", "--is-ancestor", self.candidate, published)
        self.assertEqual("official stable source", self.git(self.origin, "show", "main:source.txt"))
        self.assertEqual("new upstream", self.git(self.origin, "show", "main:config/upstream-manager.json"))
        if self.sequence:
            self.assertEqual(f"signed discovery {self.sequence}",
                             self.git(self.origin, "show", "main:app-release.json"))
            self.assertEqual(f"signed patches {self.sequence}",
                             self.git(self.origin, "show", "main:patches-bundle.json"))
            self.assertEqual(f"concurrent source {self.sequence}",
                             self.git(self.origin, "show", "main:concurrent.txt"))
        records = self.log.read_text().splitlines() if self.log.exists() else []
        self.assertEqual(validations, len(records))
        if records:
            self.assertEqual(self.git(self.origin, "rev-parse", "main^{tree}"), records[-1])

    def test_unchanged_main_publishes_the_original_verified_merge(self):
        self.publish()
        self.assert_published(0)
        self.assertEqual(self.candidate, self.git(self.origin, "rev-parse", "main"))

    def test_stale_checkout_merges_latest_metadata_and_source_then_reverifies(self):
        latest = self.advance()
        self.publish()
        self.assert_published(1)
        self.git(self.origin, "merge-base", "--is-ancestor", latest, "main")

    def test_main_advancing_between_fetch_and_push_reverifies_before_retry(self):
        real_git = publish_upstream.git
        pushes = []

        def racing_git(root, *args):
            if args[0] == "push":
                pushes.append(args)
                if len(pushes) == 1:
                    self.advance()
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=racing_git):
            self.publish()
        self.assertEqual(2, len(pushes))
        self.assert_published(1)

    def test_each_further_main_update_requires_another_full_verification(self):
        self.advance()
        real_git = publish_upstream.git
        pushes = []

        def racing_git(root, *args):
            if args[0] == "push":
                pushes.append(args)
                if len(pushes) == 1:
                    self.advance()
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=racing_git):
            self.publish()
        self.assertEqual(2, len(pushes))
        self.assert_published(2)

    def test_failed_verification_never_publishes_the_combined_tree(self):
        latest = self.advance()
        self.verifier.write_text(self.verifier.read_text() + "raise SystemExit(1)\n")
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual(self.candidate, self.git(self.checkout, "rev-parse", "HEAD"))

    def test_unreviewed_conflict_aborts_without_replacing_main_or_candidate(self):
        latest = self.advance(conflict=True)
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual(self.candidate, self.git(self.checkout, "rev-parse", "HEAD"))
        self.assertEqual("", self.git(self.checkout, "status", "--porcelain"))
        self.assertFalse(self.log.exists())

    def test_dirty_checkout_and_existing_merge_are_preserved(self):
        (self.checkout / "source.txt").write_text("user work\n")
        with self.assertRaisesRegex(RuntimeError, "clean committed candidate"):
            self.publish()
        self.assertEqual("user work\n", (self.checkout / "source.txt").read_text())
        self.assertEqual(self.base, self.git(self.origin, "rev-parse", "main"))
        self.git(self.checkout, "restore", "source.txt")
        self.advance()
        self.git(self.checkout, "fetch", "-q", "origin", "main")
        self.git(self.checkout, "merge", "--no-ff", "--no-commit", "FETCH_HEAD")
        before = self.git(self.checkout, "status", "--porcelain")
        with self.assertRaisesRegex(RuntimeError, "clean committed candidate"):
            self.publish()
        self.assertEqual(before, self.git(self.checkout, "status", "--porcelain"))
        self.assertEqual(self.git(self.writer, "rev-parse", "HEAD"),
                         self.git(self.checkout, "rev-parse", "MERGE_HEAD"))

    def test_verification_mutating_source_stops_before_push(self):
        latest = self.advance()
        self.verifier.write_text(self.verifier.read_text()
                                 + "Path('source.txt').write_text('unverified source\\n')\n")
        with self.assertRaisesRegex(RuntimeError, "unexpected source files"):
            self.publish()
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))

    def test_verification_staging_changed_source_still_stops_before_push(self):
        latest = self.advance()
        self.verifier.write_text(self.verifier.read_text()
                                 + "Path('source.txt').write_text('unverified source\\n')\n"
                                 + "subprocess.run(['git', 'add', 'source.txt'], check=True)\n")
        with self.assertRaisesRegex(RuntimeError, "unexpected source files"):
            self.publish()
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))

    def test_merge_failure_without_merge_head_retains_the_actual_error(self):
        latest = self.advance()
        real_git = publish_upstream.git
        failure = subprocess.CalledProcessError(128, ["git", "merge"])

        def failed_merge(root, *args):
            if args[0] == "merge":
                self.assertNotIn("--abort", args)
                raise failure
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=failed_merge):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.publish()
        self.assertIs(failure, raised.exception)
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))
        self.assertEqual("", self.git(self.checkout, "status", "--porcelain"))

    def test_regenerated_dependency_provenance_is_included(self):
        self.advance()
        self.verifier.write_text(self.verifier.read_text()
                                 + "Path('config/dependency-provenance.json').write_text('{\"verified\": true}\\n')\n")
        self.publish()
        self.assertEqual({"verified": True}, json.loads(
            self.git(self.origin, "show", "main:config/dependency-provenance.json")))

    def test_push_failure_without_main_advance_is_not_retried(self):
        real_git = publish_upstream.git
        pushes = []
        failure = subprocess.CalledProcessError(128, ["git", "push"])

        def refused_git(root, *args):
            if args[0] == "push":
                pushes.append(args)
                raise failure
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=refused_git):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.publish()
        self.assertIs(failure, raised.exception)
        self.assertEqual(1, len(pushes))
        self.assertEqual(self.base, self.git(self.origin, "rev-parse", "main"))
        self.assertFalse(self.log.exists())

    def test_repeated_push_races_stop_after_three_attempts(self):
        real_git = publish_upstream.git
        pushes = []

        def racing_git(root, *args):
            if args[0] == "push":
                pushes.append(args)
                self.advance()
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=racing_git):
            with self.assertRaisesRegex(RuntimeError, "kept changing"):
                self.publish()
        self.assertEqual(3, len(pushes))
        self.assertEqual(2, len(self.log.read_text().splitlines()))
        self.assertEqual(self.git(self.writer, "rev-parse", "HEAD"),
                         self.git(self.origin, "rev-parse", "main"))

    def test_lost_successful_push_response_does_not_duplicate_publication(self):
        real_git = publish_upstream.git
        pushes = []

        def lost_response(root, *args):
            result = real_git(root, *args)
            if args[0] == "push":
                pushes.append(args)
                raise subprocess.CalledProcessError(1, ["git", "push"])
            return result

        with patch("publish_upstream.git", side_effect=lost_response):
            self.publish()
        self.assertEqual(1, len(pushes))
        self.assert_published(0)

    def test_lost_successful_response_on_last_attempt_is_recognized(self):
        real_git = publish_upstream.git
        pushes = []

        def racing_then_lost_response(root, *args):
            if args[0] == "push":
                pushes.append(args)
                if len(pushes) < 3:
                    self.advance()
                real_git(root, *args)
                raise subprocess.CalledProcessError(1, ["git", "push"])
            return real_git(root, *args)

        with patch("publish_upstream.git", side_effect=racing_then_lost_response):
            self.publish()
        self.assertEqual(3, len(pushes))
        self.assert_published(2)

    def test_rewritten_main_history_is_not_merged_or_overwritten(self):
        self.git(self.writer, "checkout", "--orphan", "rewritten")
        self.commit(self.writer, "rewritten history")
        self.git(self.writer, "push", "-q", "--force", "origin", "HEAD:main")
        latest = self.git(self.origin, "rev-parse", "main")
        with self.assertRaisesRegex(RuntimeError, "history changed"):
            self.publish()
        self.assertEqual(latest, self.git(self.origin, "rev-parse", "main"))
        self.assertFalse(self.log.exists())

    def test_reverification_command_is_required(self):
        with self.assertRaisesRegex(ValueError, "re-verification command"):
            publish_upstream.publish(self.checkout, [])
        self.assertEqual(self.base, self.git(self.origin, "rev-parse", "main"))


if __name__ == "__main__":
    unittest.main()
