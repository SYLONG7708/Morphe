import copy
import unittest
from cloud import asset, next_code, variants, validate_source, GOOGLE_CERTS, ABIS

class CloudPolicyTest(unittest.TestCase):
    def test_old_channel_migrates_monotonically(self):
        self.assertEqual(next_code({'sequence':2026092404}),2026100901)
    def test_future_channel_never_downgrades(self):
        self.assertEqual(next_code({'sequence':2026100900}),2026100901)
    def test_installed_release_code_also_counted(self):
        self.assertEqual(next_code({'packages':[{'package':'app.morphe.android.youtube','version_code':2026100999}]}),2026101000)
    def test_exhaustion_is_safe(self):
        with self.assertRaises(ValueError):next_code({'sequence':2147483647})
    def test_ambiguous_assets_rejected(self):
        with self.assertRaises(ValueError):asset({'assets':[{'name':'a.mpp'},{'name':'b.mpp'}]},r'.+\.mpp')
    def test_missing_digest_rejected(self):
        with self.assertRaises(ValueError):asset({'assets':[{'name':'a.mpp'}]},r'.+\.mpp')
    def test_selects_monolithic_universal_only(self):
        page=''.join('<div class="table-row headerFont"><a href="/'+name+'-android-apk-download/"></a>'+kind+'>universal</div>'
            for name,kind in [('bundle','BUNDLE</span>'),('plain','>APK</span>')])
        self.assertEqual(variants(page,'https://www.apkmirror.com'),['https://www.apkmirror.com/plain-android-apk-download/'])
    def source(self):
        return dict(package='com.google.android.youtube',version_name='21.16.256',signature_verified=True,
                    signer_sha256=[next(iter(GOOGLE_CERTS))],abis=list(ABIS),min_sdk=28)
    def test_valid_source(self):validate_source(self.source(),'21.16.256')
    def test_invalid_source_identity(self):
        for field,value in [('package','evil'),('version_name','9.9.9'),('signature_verified',False),
                            ('signer_sha256',['0'*64]),('abis',['arm64-v8a']),('min_sdk',29)]:
            with self.subTest(field=field):
                item=self.source();item[field]=value
                with self.assertRaises(ValueError):validate_source(item,'21.16.256')

if __name__=='__main__':unittest.main()
