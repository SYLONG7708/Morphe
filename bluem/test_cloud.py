import copy
import unittest
from cloud import asset, next_code, variants, validate_source, validate_patch_result, GOOGLE_CERTS, ABIS

class CloudPolicyTest(unittest.TestCase):
    def test_old_channel_migrates_monotonically(self):
        self.assertEqual(next_code({'sequence':2026092404}),2026100910)
    def test_cloud_release_upgrades_locally_delivered_recovery(self):
        current = {'sequence':2026100905, 'packages':[
            {'package':'app.morphe.android.youtube','version_code':2026100905},
            {'package':'app.revanced.android.gms','version_code':255070225}]}
        self.assertGreater(next_code(current), 2026100909)
    def test_future_channel_never_downgrades(self):
        self.assertEqual(next_code({'sequence':2026100999}),2026101000)
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

class PatchResultTest(unittest.TestCase):
    def setUp(self):
        self.selection = {'youtube_version': '21.20.405', 'default_patch_support_count': 2}
        # Desktop 1.19.0 report shape from the failed Blue M run 39828820427.
        self.current = {
            'success': True,
            'packageName': 'com.google.android.youtube',
            'packageVersion': '21.20.405',
            'appliedPatches': ['GmsCore support', 'Spoof video streams'],
            'failedPatches': [],
            'stepResults': [{'step': 'PATCHING', 'success': True},
                            {'step': 'REBUILDING', 'success': True}],
        }
        self.legacy = copy.deepcopy(self.current)
        self.legacy['patchingSteps'] = self.legacy.pop('stepResults')
        self.legacy.pop('success')  # Legacy Kotlin serialization omits default True.
        self.legacy['appliedPatches'] = [{'name': name} for name in self.current['appliedPatches']]

    def reports(self):
        return [('stepResults', copy.deepcopy(self.current)),
                ('patchingSteps', copy.deepcopy(self.legacy))]

    def test_current_engine_report_and_legacy_cli_report(self):
        for key, report in self.reports():
            with self.subTest(schema=key):
                validate_patch_result(report, self.selection)
                report['success'] = True
                validate_patch_result(report, self.selection)

    def test_current_report_requires_explicit_success(self):
        self.current.pop('success')
        with self.assertRaises(ValueError):
            validate_patch_result(self.current, self.selection)

    def test_overall_failure_and_non_boolean_success_rejected(self):
        for key, report in self.reports():
            for success in (False, None, 'true', 'false', 1, 0):
                with self.subTest(schema=key, success=success):
                    report['success'] = success
                    with self.assertRaises(ValueError):
                        validate_patch_result(report, self.selection)

    def test_failed_patches_and_malformed_failure_list_rejected(self):
        for key, report in self.reports():
            for failed in ([{'name': 'GmsCore support', 'error': 'failed'}], None, {}, '', False):
                with self.subTest(schema=key, failed=failed):
                    report['failedPatches'] = failed
                    with self.assertRaises(ValueError):
                        validate_patch_result(report, self.selection)

    def test_missing_required_report_fields_rejected(self):
        for key, report in self.reports():
            for field in (key, 'failedPatches', 'packageVersion', 'appliedPatches'):
                with self.subTest(schema=key, field=field):
                    incomplete = copy.deepcopy(report)
                    incomplete.pop(field)
                    with self.assertRaises(ValueError):
                        validate_patch_result(incomplete, self.selection)

    def test_empty_and_malformed_step_results_rejected(self):
        for key, report in self.reports():
            for steps in ([], None, {}, 'success', [None], [{}],
                          [{'step': 'PATCHING'}], [{'step': [], 'success': True}]):
                with self.subTest(schema=key, steps=steps):
                    report[key] = steps
                    with self.assertRaises(ValueError):
                        validate_patch_result(report, self.selection)

    def test_each_step_must_explicitly_succeed(self):
        for key, report in self.reports():
            for index in range(2):
                for success in (False, None, 'true', 'false', 1, 0):
                    with self.subTest(schema=key, index=index, success=success):
                        failed = copy.deepcopy(report)
                        failed[key][index]['success'] = success
                        with self.assertRaises(ValueError):
                            validate_patch_result(failed, self.selection)

    def test_both_unsigned_build_steps_required(self):
        for key, report in self.reports():
            for step in report[key]:
                with self.subTest(schema=key, remaining=step['step']):
                    incomplete = copy.deepcopy(report)
                    incomplete[key] = [step]
                    with self.assertRaises(ValueError):
                        validate_patch_result(incomplete, self.selection)

    def test_extra_failed_step_is_not_ignored(self):
        for key, report in self.reports():
            with self.subTest(schema=key):
                report[key].append({'step': 'SIGNING', 'success': False})
                with self.assertRaises(ValueError):
                    validate_patch_result(report, self.selection)

    def test_one_schema_cannot_mask_failure_in_the_other(self):
        report = dict(self.current, patchingSteps=self.legacy['patchingSteps'])
        validate_patch_result(report, self.selection)
        for key in ('stepResults', 'patchingSteps'):
            for steps in (None, [], [{'step': 'PATCHING', 'success': False}]):
                with self.subTest(schema=key, steps=steps):
                    failed = copy.deepcopy(report)
                    failed[key] = steps
                    with self.assertRaises(ValueError):
                        validate_patch_result(failed, self.selection)

    def test_wrong_target_and_incomplete_patch_selection_rejected(self):
        for key, report in self.reports():
            for field, value in (('packageVersion', '21.16.256'),
                                 ('appliedPatches', ['GmsCore support']),
                                 ('appliedPatches', []), ('appliedPatches', None),
                                 ('appliedPatches', 'not a patch list'),
                                 ('appliedPatches', {'one': True, 'two': True})):
                with self.subTest(schema=key, field=field, value=value):
                    incomplete = copy.deepcopy(report)
                    incomplete[field] = value
                    with self.assertRaises(ValueError):
                        validate_patch_result(incomplete, self.selection)

    def test_non_object_reports_rejected(self):
        for report in (None, [], 'success', True):
            with self.subTest(report=report), self.assertRaises(ValueError):
                validate_patch_result(report, self.selection)


if __name__=='__main__':unittest.main()
