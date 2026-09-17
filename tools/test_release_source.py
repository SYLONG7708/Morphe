import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from release_source import unchanged


class ReleaseSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        Path("app").mkdir()
        Path("app/source.kt").write_text("working app\n")
        self.commit()
        self.git("tag", "symorphe-v1.1.80")

    def tearDown(self):
        os.chdir(self.previous)
        self.temp.cleanup()

    @staticmethod
    def git(*args):
        subprocess.run(["git", *args], check=True, capture_output=True)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def test_metadata_only_does_not_republish(self):
        Path("app-release.json").write_text('{}\n')
        Path(".github").mkdir()
        Path(".github/maintenance-heartbeat.txt").write_text("2026-09\n")
        Path("README.md").write_text("updated documentation\n")
        self.commit()
        self.assertTrue(unchanged("symorphe-v1.1.80"))

    def test_manager_only_change_requires_release(self):
        Path("app/source.kt").write_text("updated upstream app\n")
        self.commit()
        self.assertFalse(unchanged("symorphe-v1.1.80"))

    def test_missing_or_invalid_tag_cannot_suppress_release(self):
        for tag in ("symorphe-v1.1.999", "--bad", "main"):
            self.assertFalse(unchanged(tag))
