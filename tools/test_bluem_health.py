import copy
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock

import bluem_health
import morphe_codex_guard as guard


class BlueMHealthTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((Path(__file__).resolve().parents[1] /
                                    'channels/blue-m-stable.json').read_text(encoding='utf-8'))
        self.release = {'tag_name': 'blue-m-' + str(self.manifest['sequence']), 'draft': False,
                        'prerelease': False, 'assets': [
                            {'name': p['url'].rsplit('/', 1)[1], 'size': p['size'],
                             'state': 'uploaded', 'digest': 'sha256:' + p['sha256']}
                            for p in self.manifest['packages']]}

    def test_valid_public_pair_and_reject_tampered_release(self):
        self.assertEqual(self.release['tag_name'], bluem_health.validate(self.manifest, self.release))
        for key, value in [('draft', True), ('prerelease', True), ('tag_name', 'different')]:
            bad = dict(self.release, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                bluem_health.validate(self.manifest, bad)
        for key, value in [('size', 1), ('digest', 'sha256:' + '0' * 64), ('state', 'new')]:
            bad = copy.deepcopy(self.release)
            bad['assets'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                bluem_health.validate(self.manifest, bad)

    def test_downgrade_wrong_signer_missing_package_and_foreign_url_rejected(self):
        with self.assertRaises(ValueError):
            bluem_health.validate(self.manifest, self.release, self.manifest['sequence'] + 1)
        for key, value in [('signer_sha256', '0' * 64), ('url', 'https://example.com/file.apk'),
                           ('url', self.manifest['packages'][0]['url'] + '?token=wrong'),
                           ('version_code', 1)]:
            bad = copy.deepcopy(self.manifest)
            bad['packages'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                bluem_health.validate(bad, self.release)
        bad = copy.deepcopy(self.manifest)
        bad['packages'].pop()
        with self.assertRaises(ValueError):
            bluem_health.validate(bad, self.release)

    def test_blue_m_health_failure_routes_to_codex_after_three_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            config = dict(repository=guard.REPOSITORY, runtime_root=temp, git='git', gh='gh',
                          python='python', codex='codex', workflows=['blue-m-stable.yml'])
            instance = guard.Guard(config)
            now = guard.utc()
            run = dict(id=12, head_sha='a' * 40, head_branch='main', event='workflow_dispatch',
                       repository={'full_name': guard.REPOSITORY}, head_repository={'full_name': guard.REPOSITORY},
                       path='.github/workflows/blue-m-stable.yml', status='completed',
                       conclusion='success', created_at=now, updated_at=now)
            instance.api = Mock(side_effect=lambda endpoint: {'workflow_runs': [run]}
                                if '/runs?' in endpoint else {'state': 'active'})
            instance.audit_blue_m = Mock(side_effect=RuntimeError('APK public download unavailable'))
            instance.audit_release = Mock(side_effect=AssertionError('Unrelated Manager was inspected'))
            instance.repair = Mock(return_value={'verified_run': 'fixture'})
            for attempt in range(3):
                instance.state.setdefault('blue_m_release', {})['next_check'] = 0
                instance.cycle()
                self.assertEqual(attempt == 2, instance.repair.called)
            self.assertEqual('blue-m-stable.yml', instance.repair.call_args.args[0]['workflow'])
            self.assertEqual('public_update_health', instance.repair.call_args.args[0]['reason'])
            instance.audit_release.assert_not_called()

    def test_failed_signature_does_not_download_apks_or_parse_untrusted_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            instance = guard.Guard(dict(repository=guard.REPOSITORY, runtime_root=temp,
                                        git='git', gh='gh', python='python', codex='codex', java='java'))
            instance.api = Mock(return_value={'object': {'sha': 'a' * 40}})
            commands = []
            def execute(argv, **kwargs):
                commands.append(argv)
                if argv[0] == 'java':
                    raise RuntimeError('Detached signature verification failed')
            instance.command = execute
            with self.assertRaisesRegex(RuntimeError, 'signature'):
                instance.audit_blue_m()
            self.assertEqual(1, instance.api.call_count)
            self.assertEqual(3, len(commands))
            self.assertTrue(all(not any('.apk' in a for a in cmd) for cmd in commands))


if __name__ == '__main__':
    unittest.main()
