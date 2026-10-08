import copy,json,unittest
from pathlib import Path
from recommended_policy import recommended
ROOT=Path(__file__).resolve().parent
class StableSelectionTest(unittest.TestCase):
    def setUp(self):
        self.release={'tag_name':'v1.46.0','draft':False,'prerelease':False}
        self.meta=json.loads((ROOT/'test-fixtures/patches-list.json').read_text())
    def test_current_official_target(self):
        self.assertEqual(recommended(self.release,self.meta)['youtube_version'],'21.16.256')
    def test_reject_prerelease_even_with_stable_app(self):
        self.release['prerelease']=True
        with self.assertRaises(ValueError):recommended(self.release,self.meta)
    def test_reject_dev_tag_with_false_flag(self):
        self.release['tag_name']='v1.47.0-dev.1'
        with self.assertRaises(ValueError):recommended(self.release,self.meta)
    def test_reject_unmatched_tag_metadata(self):
        self.meta['version']='1.45.0'
        with self.assertRaises(ValueError):recommended(self.release,self.meta)
    def test_no_experimental_fallback(self):
        for p in self.meta['patches']:
            for package in p.get('compatiblePackages') or []:
                for t in package.get('targets') or []:t['isExperimental']=True
        with self.assertRaises(ValueError):recommended(self.release,self.meta)
    def test_untyped_flag_not_stable(self):
        for p in self.meta['patches']:
            for package in p.get('compatiblePackages') or []:
                for t in package.get('targets') or []:t['isExperimental']='false'
        with self.assertRaises(ValueError):recommended(self.release,self.meta)
    def test_default_patch_intersection_support(self):
        self.meta['patches']=[{'default':True,'compatiblePackages':[{'packageName':'com.google.android.youtube','targets':[
            {'version':'21.16.256','isExperimental':False,'minSdk':28},{'version':'21.40.161','isExperimental':True,'minSdk':28}]}]}]
        self.assertEqual(recommended(self.release,self.meta)['youtube_version'],'21.16.256')
    def test_new_patch_same_youtube_still_changes_release(self):
        before=recommended(self.release,self.meta)
        self.release['tag_name']='v1.47.0';self.meta['version']='1.47.0'
        after=recommended(self.release,self.meta)
        self.assertEqual(before['youtube_version'],after['youtube_version'])
        self.assertNotEqual(before,after)
    def test_sdk_incompatible_fails_closed(self):
        with self.assertRaises(ValueError):recommended(self.release,self.meta,sdk=20)
if __name__=='__main__':unittest.main(verbosity=2)
