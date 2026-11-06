import tempfile
import unittest
import zipfile
from pathlib import Path

from build import package_unsigned


class PackageUnsignedTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source.apk'
        self.entries = {
            'AndroidManifest.xml': b'original manifest',
            'classes.dex': b'original primary dex',
            'classes2.dex': b'original secondary dex',
            'classes3.dex': b'unchanged dex',
            'resources.arsc': b'original resources',
            'lib/arm64-v8a/libexample.so': b'original native code',
            'META-INF/services/example.Provider': b'original provider',
            'META-INF/CERT.RSA': b'old signature',
            'META-INF/MANIFEST.MF': b'old manifest signature',
            'stamp-cert-sha256': b'old stamp',
        }
        with zipfile.ZipFile(self.source, 'w') as source:
            for name, data in self.entries.items():
                source.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        self.patched = self.root / 'patched'
        self.patched.mkdir()
        self.changes = {'AndroidManifest.xml': b'updated manifest', 'classes.dex': b'hooked dex'}
        self.client = self.root / 'client.dex'
        self.client.write_bytes(b'update client')
        self.certificate = self.root / 'certificate.der'
        self.certificate.write_bytes(b'pinned certificate')
        self.destination = self.root / 'unsigned.apk'

    def package(self):
        for name, data in self.changes.items():
            (self.patched / name).write_bytes(data)
        return package_unsigned(self.source, self.patched, self.client, self.certificate, self.destination)

    def check_contents(self, client_index):
        with zipfile.ZipFile(self.destination) as apk:
            expected = dict(self.entries)
            for name in ('META-INF/CERT.RSA', 'META-INF/MANIFEST.MF', 'stamp-cert-sha256'):
                del expected[name]
            expected.update(self.changes)
            expected['classes' + str(client_index) + '.dex'] = self.client.read_bytes()
            expected['assets/bluem-updates/release-cert.der'] = self.certificate.read_bytes()
            self.assertEqual(set(apk.namelist()), set(expected))
            self.assertEqual(len(apk.namelist()), len(expected), 'No duplicate ZIP entries')
            for name, data in expected.items():
                self.assertEqual(apk.read(name), data, name)

    def test_no_overflow_retains_existing_layout_and_resources(self):
        self.package()
        self.check_contents(4)

    def test_overflow_dex_and_client_never_overwrite_each_other(self):
        self.changes.update({'classes2.dex': b'second hooked dex',
                             'classes4.dex': b'first overflow', 'classes5.dex': b'second overflow'})
        self.package()
        self.check_contents(6)

    def test_extra_non_dex_entry_rejected(self):
        self.changes['unexpected.txt'] = b'unexpected'
        with self.assertRaisesRegex(ValueError, 'Unexpected patched APK entries'):
            self.package()

    def test_missing_overflow_index_rejected(self):
        self.changes['classes5.dex'] = b'gap'
        with self.assertRaisesRegex(ValueError, 'Non-contiguous overflow DEX entries'):
            self.package()

    def test_missing_manifest_rejected(self):
        del self.changes['AndroidManifest.xml']
        with self.assertRaisesRegex(ValueError, 'Unexpected patched APK entries'):
            self.package()


if __name__ == '__main__':
    unittest.main()
