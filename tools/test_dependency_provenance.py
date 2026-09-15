import hashlib
import json
from pathlib import Path
import unittest


class DependencyProvenanceTest(unittest.TestCase):
    def test_checkout_bytes_match_pinned_public_artifacts(self):
        root = Path(__file__).resolve().parents[1]
        records = json.loads((root / 'config/dependency-provenance.json').read_text(encoding='utf-8'))
        for coordinate, record in records.items():
            name, version = coordinate.split(':')
            directory = root / 'vendor/m2/app/morphe' / name / version
            for filename, expected in record['sha256'].items():
                with self.subTest(artifact=filename):
                    data = (directory / filename).read_bytes()
                    self.assertEqual(expected, hashlib.sha256(data).hexdigest())
                    if filename.endswith(('.pom', '.module')):
                        self.assertNotIn(b'\r', data)
