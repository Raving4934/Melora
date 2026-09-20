#!/usr/bin/env python3
"""fnOS 卸载生命周期回归：root 回调只清理 Melora 私有根和内部恢复文件。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
FPK = REPO / 'packaging/fpk'
LIB = FPK / 'cmd/lib.sh'


class ResourceWizardTests(unittest.TestCase):
    def test_privileged_lifecycle_and_single_purge_choice(self):
        privilege = json.loads((FPK / 'config/privilege').read_text())
        self.assertEqual(privilege['defaults']['run-as'], 'root')
        steps = json.loads((FPK / 'wizard/uninstall').read_text())
        fields = {item['field']: item for step in steps for item in step['items'] if 'field' in item}
        self.assertEqual(set(fields), {'wizard_uninstall_data'})
        choice = fields['wizard_uninstall_data']
        self.assertEqual(choice['type'], 'checkbox')
        self.assertEqual(choice['initValue'], '')
        self.assertEqual([item['value'] for item in choice['options']], ['purge'])

    def test_service_and_non_callback_hooks_reexec_as_package(self):
        self.assertIn('dispatch_main "$0"', (FPK / 'cmd/main').read_text())
        for name in ('install_init', 'install_callback', 'upgrade_init', 'upgrade_callback',
                     'uninstall_init', 'config_init', 'config_callback'):
            self.assertIn(f'dispatch_hook {name} "$0"', (FPK / 'cmd' / name).read_text())
        self.assertIn('dispatch_hook uninstall_callback "$0"',
                      (FPK / 'cmd/uninstall_callback').read_text())


class LifecycleRoutingTests(unittest.TestCase):
    def invoke_init(self, **values):
        with tempfile.TemporaryDirectory(prefix='melora-uninstall-route-') as tmp:
            trace = Path(tmp) / 'trace'
            script = r'''
source "$REVIEW_LIB"
check_paths() { printf '%s\n' check_paths >> "$TRACE"; }
prepare_private_dirs() { printf '%s\n' prepare_private_dirs >> "$TRACE"; }
lock_runtime() { printf '%s\n' lock_runtime >> "$TRACE"; }
stop_service() { printf '%s\n' stop_service >> "$TRACE"; }
uninstall_init_service
'''
            env = dict(os.environ, REVIEW_LIB=str(LIB), TRACE=str(trace), **values)
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True,
                                    text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return trace.read_text().splitlines(), result.stdout

    def test_keep_and_purge_use_one_stop_chain(self):
        for values, phrase in [({}, '已选择保留'),
                               ({'wizard_uninstall_data': 'purge'}, '卸载回调将清除'),
                               ({'wizard_delete_data': 'true'}, '卸载回调将清除')]:
            with self.subTest(values=values):
                trace, output = self.invoke_init(**values)
                self.assertEqual(trace, ['check_paths', 'prepare_private_dirs', 'lock_runtime', 'stop_service'])
                self.assertIn(phrase, output)

    def test_ambiguous_choice_is_rejected(self):
        script = 'source "$REVIEW_LIB"; resolve_uninstall_mode'
        env = dict(os.environ, REVIEW_LIB=str(LIB), wizard_uninstall_data='purge,keep')
        result = subprocess.run(['bash', '-c', script], env=env, capture_output=True,
                                text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('未知的卸载数据选项', result.stderr)


class PurgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='melora-uninstall-purge-')
        self.root = Path(self.tmp.name)
        self.volume = self.root / 'vol3'
        self.music = self.root / 'music'
        self.music.mkdir()
        self.roots = {}
        for variable, container in (
            ('TRIM_PKGVAR', '@appdata'), ('TRIM_PKGETC', '@appconf'),
            ('TRIM_PKGTMP', '@apptemp'), ('TRIM_PKGHOME', '@apphome'),
            ('TRIM_PKGMETA', '@appmeta')):
            path = self.volume / container / 'melora'
            path.mkdir(parents=True)
            (path / 'private').write_text(variable)
            self.roots[variable] = path
        self.env = dict(os.environ, REVIEW_LIB=str(LIB), TRIM_APPNAME='melora',
                        TRIM_APPDEST_VOL=str(self.volume),
                        TRIM_DATA_ACCESSIBLE_PATHS=str(self.music),
                        **{key: str(value) for key, value in self.roots.items()})

    def tearDown(self):
        self.tmp.cleanup()

    def run_shell(self, body, expected=0, **extra):
        result = subprocess.run(['bash', '-c', 'source "$REVIEW_LIB"; ' + body],
                                env=dict(self.env, **extra), capture_output=True,
                                text=True, timeout=10)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def test_purge_removes_private_roots_and_only_internal_recovery_files(self):
        song = self.music / 'song.flac'
        completed = self.music / 'song.json'
        marker = self.music / '.melora-abc.json'
        partial = self.music / '.melora-def.part'
        for path, data in ((song, 'music'), (completed, 'metadata'),
                           (marker, 'checkpoint'), (partial, 'partial')):
            path.write_text(data)
        self.run_shell('uninstall_callback_service', wizard_uninstall_data='purge')
        self.assertTrue(song.is_file())
        self.assertTrue(completed.is_file())
        self.assertFalse(marker.exists())
        self.assertFalse(partial.exists())
        self.assertTrue(all(not path.exists() for path in self.roots.values()))

    def test_keep_preserves_everything(self):
        marker = self.music / '.melora-abc.json'
        marker.write_text('checkpoint')
        self.run_shell('uninstall_callback_service')
        self.assertTrue(marker.is_file())
        self.assertTrue(all(path.is_dir() for path in self.roots.values()))

    def test_wrong_private_root_is_rejected_without_deleting_sibling(self):
        sibling = self.volume / '@appdata' / 'other-app'
        sibling.mkdir(parents=True)
        sentinel = sibling / 'sentinel'
        sentinel.write_text('keep')
        result = self.run_shell('purge_managed_root TRIM_PKGVAR @appdata', expected=1,
                                TRIM_PKGVAR=str(sibling))
        self.assertIn('未指向本应用', result.stderr)
        self.assertEqual(sentinel.read_text(), 'keep')

    def test_symlinked_authorized_root_is_not_scanned(self):
        external = self.root / 'external'
        external.mkdir()
        marker = external / '.melora-abc.json'
        marker.write_text('keep')
        link = self.root / 'music-link'
        link.symlink_to(external, target_is_directory=True)
        self.run_shell('purge_external_recovery_files', TRIM_DATA_ACCESSIBLE_PATHS=str(link))
        self.assertEqual(marker.read_text(), 'keep')


class CodeReductionTests(unittest.TestCase):
    def test_server_has_no_private_uninstall_protocol(self):
        self.assertFalse((REPO / 'apps/server/internal/uninstall').exists())
        main = (REPO / 'apps/server/cmd/melora/main.go').read_text()
        for marker in ('internal/uninstall', '--uninstall-preflight', '--uninstall-cleanup'):
            self.assertNotIn(marker, main)


if __name__ == '__main__':
    unittest.main()
