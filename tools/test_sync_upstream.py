import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sync_upstream


class OfficialSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.upstream = self.root / "official"
        self.fork = self.root / "fork"
        self.upstream.mkdir()
        self.cmd(self.upstream, "init", "-q")
        self.identity(self.upstream)
        (self.upstream / ".github/workflows").mkdir(parents=True)
        (self.upstream / ".github/workflows/release.yml").write_text("official old\n")
        (self.upstream / "app.txt").write_text("base\n")
        self.commit(self.upstream, "base")
        self.cmd(self.root, "clone", "-q", str(self.upstream), str(self.fork))
        self.identity(self.fork)
        (self.fork / ".github/workflows/release.yml").write_text("signed SyMorphe\n")
        (self.fork / "config").mkdir()
        (self.fork / "config/profile.txt").write_text("custom profile\n")
        self.commit(self.fork, "derivative")
        (self.upstream / ".github/workflows/release.yml").write_text("official new\n")
        (self.upstream / ".github/workflows/new-publish.yml").write_text("upstream signing job\n")
        (self.upstream / "app.txt").write_text("upstream improvement\n")
        self.commit(self.upstream, "stable update")
        self.cmd(self.upstream, "tag", "v1.31.0")
        self.previous = Path.cwd()
        os.chdir(self.fork)

    def tearDown(self):
        os.chdir(self.previous)
        self.temp.cleanup()

    @staticmethod
    def cmd(directory, *args):
        return subprocess.run(["git", "-C", str(directory), *args], check=True,
                              capture_output=True, text=True)

    def identity(self, directory):
        self.cmd(directory, "config", "user.name", "Fixture")
        self.cmd(directory, "config", "user.email", "fixture@example.invalid")

    def commit(self, directory, message):
        self.cmd(directory, "add", "app.txt", ".github", "--ignore-errors")
        if (directory / "config").exists():
            self.cmd(directory, "add", "config")
        self.cmd(directory, "commit", "-qm", message)

    def prepare(self):
        real_git = sync_upstream.git

        def local_git(*args, **kwargs):
            args = tuple(str(self.upstream) if value == "https://github.com/MorpheApp/morphe-manager.git" else value for value in args)
            return real_git(*args, **kwargs)

        with patch("sync_upstream.git", side_effect=local_git), \
             patch("sync_upstream.subprocess.check_output", return_value=json.dumps({"tag_name": "v1.31.0"})), \
             patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.root / "output")}):
            sync_upstream.main()

    def test_merges_source_and_preserves_owned_release_jobs(self):
        self.prepare()
        self.assertEqual("upstream improvement\n", (self.fork / "app.txt").read_text())
        self.assertEqual("signed SyMorphe\n", (self.fork / ".github/workflows/release.yml").read_text())
        self.assertFalse((self.fork / ".github/workflows/new-publish.yml").exists())
        self.assertIn("changed=true", (self.root / "output").read_text())
        self.commit(self.fork, "verified merge")
        self.prepare()
        self.assertIn("changed=false", (self.root / "output").read_text())

    def test_source_conflict_stops_before_replacing_current_release(self):
        (self.fork / "app.txt").write_text("custom behavior\n")
        self.commit(self.fork, "custom behavior")
        before = self.cmd(self.fork, "rev-parse", "HEAD").stdout
        with self.assertRaisesRegex(RuntimeError, "current release retained"):
            self.prepare()
        self.assertEqual(before, self.cmd(self.fork, "rev-parse", "HEAD").stdout)
