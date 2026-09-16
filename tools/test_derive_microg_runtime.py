import subprocess
import unittest

from derive_microg_runtime import java_command


class DeriveMicrogRuntimeTest(unittest.TestCase):
    def test_dex_roundtrip_scope_and_unknown_layout_rejection(self):
        subprocess.run([*java_command(), "--self-test"], check=True, timeout=60)
