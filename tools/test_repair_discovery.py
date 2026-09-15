import unittest
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
