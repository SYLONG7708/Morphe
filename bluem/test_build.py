from pathlib import Path
import tempfile
import unittest
import zipfile

import build


class ApkPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source.apk'
        self.original = {'AndroidManifest.xml': b'original manifest',
                         'META-INF/services/loader': b'original service',
                         'res/raw/data': b'original resource',
                         'META-INF/CERT.RSA': b'old signature',
                         'META-INF/CERT.SF': b'old signature file',
                         'META-INF/MANIFEST.MF': b'old manifest digest',
                         'stamp-cert-sha256': b'old stamp'}
        for index in range(1, 10):
            self.original['classes' + (str(index) if index > 1 else '') + '.dex'] = f'original {index}'.encode()
        with zipfile.ZipFile(self.source, 'w') as archive:
            for name, data in self.original.items():
                archive.writestr(name, data)
        self.client = self.root / 'client.dex'
        self.client.write_bytes(b'update client')

    def rewritten(self, names):
        result = {}
        for name in names:
            path = self.root / name
            path.write_bytes(('rewritten ' + name).encode())
            result[name] = path
        return result

    def test_split_dex_and_client_are_packaged_without_collisions_or_lost_entries(self):
        for spills in ([], ['classes10.dex'], ['classes10.dex', 'classes11.dex']):
            for changed in (['classes.dex'], ['classes.dex', 'classes2.dex']):
                with self.subTest(spills=spills, changed=changed):
                    rewritten = self.rewritten(['AndroidManifest.xml', *changed, *spills])
                    output = self.root / 'unsigned.apk'
                    build.package_apk(self.source, output, rewritten, self.client)
                    with zipfile.ZipFile(output) as archive:
                        names = archive.namelist()
                        self.assertEqual(len(names), len(set(names)))
                        removed = {'META-INF/CERT.RSA', 'META-INF/CERT.SF', 'META-INF/MANIFEST.MF', 'stamp-cert-sha256'}
                        client_name = f'classes{10 + len(spills)}.dex'
                        cert_name = 'assets/bluem-updates/release-cert.der'
                        self.assertEqual(set(names), (set(self.original) - removed) | set(spills) | {client_name, cert_name})
                        for name, data in self.original.items():
                            if name not in removed:
                                self.assertEqual(archive.read(name), rewritten[name].read_bytes() if name in rewritten else data)
                        for name in spills:
                            self.assertEqual(archive.read(name), rewritten[name].read_bytes())
                        self.assertEqual(archive.read(client_name), self.client.read_bytes())
                        self.assertEqual(archive.read(cert_name), (build.ROOT / 'assets/release-cert.der').read_bytes())

    def test_unexpected_new_files_are_rejected(self):
        rewritten = self.rewritten(['AndroidManifest.xml', 'classes.dex', 'unreviewed.bin'])
        with self.assertRaises(AssertionError):
            build.package_apk(self.source, self.root / 'unsigned.apk', rewritten, self.client)

    def test_existing_rewrite_boundaries_still_enforced(self):
        for names in (['classes.dex'], ['AndroidManifest.xml'],
                      ['AndroidManifest.xml', 'classes.dex', 'classes2.dex', 'classes3.dex']):
            with self.subTest(names=names), self.assertRaises(AssertionError):
                build.package_apk(self.source, self.root / 'unsigned.apk', self.rewritten(names), self.client)


if __name__ == '__main__':
    unittest.main()
