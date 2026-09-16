#!/usr/bin/env python3
"""离线回归：合成 ELF 只用于 staging 单测，绝不据此生成 .fpk。"""
import json
import hashlib
import importlib.util
import os
from pathlib import Path
import pwd
import shutil
import struct
import subprocess
import socket
import sqlite3
import zlib
import time
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
PACKAGING = REPO / 'packaging'
ICON_GENERATOR = PACKAGING / 'tools/generate-icons.py'
BUILD = PACKAGING / '.build'
BUILD.mkdir(exist_ok=True)


def fake_elf(arch='amd64', dynamic=False):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', data, 16, 2, {'amd64': 62, 'arm64': 183}[arch])
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<I', data, 64, 3 if dynamic else 1)
    return data


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='p-', dir=BUILD)
        self.root = Path(self.tmp.name)
        for name in ['scripts', 'dist', 'apps/web/dist', 'packaging']:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / 'scripts/package-fpk.sh', self.root / 'scripts/package-fpk.sh')
        shutil.copy2(REPO / 'package.json', self.root / 'package.json')
        shutil.copy2(REPO / 'apps/web/package.json', self.root / 'apps/web/package.json')
        self.version = json.loads((self.root / 'package.json').read_text())['version']
        shutil.copytree(PACKAGING / 'fpk', self.root / 'packaging/fpk')
        shutil.copytree(PACKAGING / 'tools', self.root / 'packaging/tools', ignore=shutil.ignore_patterns('__pycache__'))
        (self.root / 'apps/web/dist/index.html').write_text('<!doctype html><title>TEST FIXTURE ONLY</title>')
        for arch in ['amd64', 'arm64']:
            (self.root / f'dist/melora-linux-{arch}').write_bytes(fake_elf(arch))

    def tearDown(self):
        self.tmp.cleanup()

    def run_package(self, *args, expected=0, extra_env=None):
        env = os.environ.copy()
        env.pop('FNPACK', None)
        env['SOURCE_DATE_EPOCH'] = '1234567890'
        env.update(extra_env or {})
        result = subprocess.run(['bash', str(self.root / 'scripts/package-fpk.sh'), *args],
                                env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def assert_no_fpk(self):
        self.assertEqual(list(self.root.rglob('*.fpk')), [])

    def test_missing_fnpack_fails_without_package(self):
        self.run_package('--arch', 'amd64', '--fnpack', str(self.root / 'not-fnpack'), expected=127)
        self.assert_no_fpk()
        self.assertFalse((self.root / 'packaging/.build').exists())

    def test_arch_is_required(self):
        self.run_package('--stage-only', expected=1)
        self.assert_no_fpk()

    def test_both_architectures_and_exact_structure(self):
        for arch, platform in [('amd64', 'x86'), ('arm64', 'arm')]:
            self.run_package('--arch', arch, '--stage-only')
            stage = self.root / f'packaging/.build/{arch}/stage'
            manifest = (stage / 'manifest').read_text()
            self.assertIn(f'platform={platform}\n', manifest)
            self.assertNotIn('arch=', manifest)
            self.assertIn(f'version={self.version}\n', manifest)
            self.assertNotIn('service_port=', manifest)
            self.assertNotIn('checkport=', manifest)
            entry = json.loads((stage / 'app/ui/config').read_text())['.url']['melora.main']
            self.assertEqual(entry['type'], 'iframe')
            self.assertEqual(entry['protocol'], '')
            self.assertEqual(entry['gatewayPrefix'], '/app/melora')
            self.assertEqual(entry['gatewaySocket'], 'app.sock')
            self.assertEqual(entry['url'], '/app/melora')
            self.assertFalse(entry['allUsers'])
            self.assertNotIn('port', entry)
            self.assertIn('MELORA_ACCESS_MODE=gateway', (stage / 'app/config/melora.env.example').read_text())
            self.assertEqual((stage / 'wizard/config').read_bytes(),
                             (self.root / 'packaging/fpk/wizard/config').read_bytes())
            wizard = json.loads((stage / 'wizard/config').read_text())
            self.assertTrue(wizard[0]['items'])
            self.assertTrue(all(item['type'] == 'tips' and 'field' not in item
                                for step in wizard for item in step['items']))
            self.assertFalse((stage / 'wizard/.gitkeep').exists())
            self.assertTrue((stage / 'app/web/index.html').is_file())
            self.assertFalse((stage / 'app/web/ui').exists())
            self.assertEqual((stage / 'app/bin/melora').stat().st_mode & 0o777, 0o755)
            self.assertEqual((stage / 'app/web/index.html').stat().st_mtime, 1234567890)
            self.assertEqual(json.loads((stage / 'config/privilege').read_text())['defaults']['run-as'], 'package')
            self.assertEqual(json.loads((stage / 'config/resource').read_text()),
                             {'data-share': {'shares': [{'name': 'melora/exports'}]}})
            self.assertEqual((stage / 'wizard/uninstall').read_bytes(),
                             (self.root / 'packaging/fpk/wizard/uninstall').read_bytes())
        self.assert_no_fpk()

    def test_invalid_or_system_variable_wizard_is_not_staged(self):
        wizard = self.root / 'packaging/fpk/wizard/config'
        for body in ['not-json', '{}', '[]', '[{"stepTitle":"bad","items":[{"type":"text","field":"TRIM_DATA_ACCESSIBLE_PATHS"}]}]']:
            with self.subTest(body=body):
                wizard.write_text(body)
                self.run_package('--arch', 'amd64', '--stage-only', expected=1)
                self.assert_no_fpk()

    def test_gateway_framework_mismatch_is_rejected_before_fnpack(self):
        path = self.root / 'packaging/fpk/app/ui/config'
        data = json.loads(path.read_text())
        data['.url']['melora.main']['port'] = '3780'
        path.write_text(json.dumps(data))
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)
        self.assert_no_fpk()

    def test_package_filename_uses_new_version(self):
        result = self.run_package('--help')
        self.assertIn('melora-<version>-linux-', result.stdout)
        self.assertNotIn('melora-0.1.0-linux-', result.stdout)

    def test_staging_is_repeatable_and_removes_stale_assets(self):
        self.run_package('--arch', 'amd64', '--stage-only')
        inventory = self.root / 'packaging/.build/amd64/staging.sha256'
        before = inventory.read_bytes()
        (inventory.parent / 'stage/app/web/stale.js').write_text('obsolete')
        self.run_package('--arch', 'amd64', '--stage-only')
        self.assertEqual(before, inventory.read_bytes())
        self.assertFalse((inventory.parent / 'stage/app/web/stale.js').exists())
        self.assert_no_fpk()

    def test_missing_binary(self):
        (self.root / 'dist/melora-linux-amd64').unlink()
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)
        self.assert_no_fpk()

    def test_wrong_binary_architecture(self):
        (self.root / 'dist/melora-linux-amd64').write_bytes(fake_elf('arm64'))
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)
        self.assert_no_fpk()

    def test_dynamic_binary_rejected(self):
        (self.root / 'dist/melora-linux-amd64').write_bytes(fake_elf(dynamic=True))
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)

    def test_frontend_symlinks_rejected(self):
        (self.root / 'apps/web/dist/escape').symlink_to(self.root / 'dist', target_is_directory=True)
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)
        self.assert_no_fpk()

    def test_frontend_secret_files_rejected(self):
        (self.root / 'apps/web/dist/.env').write_text('TEST_ONLY=secret')
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)

    def test_fnpack_success_without_output_is_not_success(self):
        # 只模拟退出码，不创建任何伪 FPK。
        stub = self.root / 'fnpack-no-output'
        stub.write_text('#!/bin/sh\nexit 0\n')
        stub.chmod(0o755)
        self.run_package('--arch', 'amd64', '--fnpack', str(stub), expected=1)
        self.assert_no_fpk()

    def test_fnpack_lock_is_shared_between_architectures_and_owned_cleanup(self):
        lock = self.root / 'packaging/.build/fnpack.lock'
        lock.mkdir(parents=True)
        sentinel = lock / 'existing-owner'
        sentinel.write_text('KEEP')
        stub = self.root / 'fnpack-no-output'
        stub.write_text('#!/bin/sh\nexit 0\n')
        stub.chmod(0o755)
        for arch in ('amd64', 'arm64'):
            self.run_package('--arch', arch, '--fnpack', str(stub), expected=1)
            self.assertEqual(sentinel.read_text(), 'KEEP')
            self.assertFalse((lock.parent / (arch + '.lock')).exists())
        self.run_package('--arch', 'amd64', '--stage-only')
        self.assertEqual(sentinel.read_text(), 'KEEP')
        sentinel.unlink()
        lock.rmdir()
        # 成功取得的共享锁即使工具未输出包也应清理。
        self.run_package('--arch', 'amd64', '--fnpack', str(stub), expected=1)
        self.assertFalse(lock.exists())
        self.assert_no_fpk()

    def test_unowned_staging_is_not_deleted(self):
        stage = self.root / 'packaging/.build/amd64'
        stage.mkdir(parents=True)
        sentinel = stage / 'user-file'
        sentinel.write_text('KEEP')
        self.run_package('--arch', 'amd64', '--stage-only', expected=1)
        self.assertEqual(sentinel.read_text(), 'KEEP')
        self.assert_no_fpk()

    def test_bad_epoch(self):
        self.run_package('--arch', 'amd64', '--stage-only', expected=2,
                         extra_env={'SOURCE_DATE_EPOCH': 'invalid'})
        self.assert_no_fpk()


@unittest.skipUnless(shutil.which('go') and (REPO / 'apps/server/internal/config/config.go').is_file(),
                     '与真实后端的一致性校验需要 Go 和实际 config.go')
class BackendContractTests(unittest.TestCase):
    def test_actual_go_config_accepts_fpk_environment(self):
        with tempfile.TemporaryDirectory(prefix='g-', dir=BUILD) as folder:
            root = Path(folder)
            server = REPO / 'apps/server'
            # 保留真实 module 与 internal 包布局，不能再把依赖 storage 的 config 当孤立文件编译。
            # 只复制生产源码和 FPK 契约，不修改后端，也不引入整个服务的测试/构建副作用。
            shutil.copy2(server / 'go.mod', root / 'go.mod')
            if (server / 'go.sum').is_file():
                shutil.copy2(server / 'go.sum', root / 'go.sum')
            for package in ('config', 'storage'):
                destination = root / 'internal' / package
                destination.mkdir(parents=True)
                sources = [source for source in sorted((server / 'internal' / package).glob('*.go'))
                           if not source.name.endswith('_test.go')]
                self.assertTrue(sources, f'缺少实际 {package} 包源码')
                for source in sources:
                    shutil.copy2(source, destination / source.name)
            contract = (PACKAGING / 'tests/go_config_contract_test.go').read_text()
            marker = '\t\t\tcfg, err := FromEnv(func(key string) string { return env[key] })'
            replacement = ('\t\t\tif addr == "0.0.0.0:3780" || addr == "[::]:3780" {\n'
                           '\t\t\t\tenv["MELORA_ALLOW_INSECURE_HTTP"] = "1"\n'
                           '\t\t\t}\n' + marker)
            self.assertIn(marker, contract, 'FPK 后端契约夹具结构发生变化')
            (root / 'internal/config/fpk_test.go').write_text(contract.replace(marker, replacement, 1))
            (root / 'tmp').mkdir()
            cache = PACKAGING / '.build/test-go-cache'
            cache.mkdir(parents=True, exist_ok=True)
            env = dict(os.environ, GO111MODULE='on', GOENV='off', GOWORK='off', GOTOOLCHAIN='local',
                       GOFLAGS='', GOPROXY='off', GOSUMDB='off', GOCACHE=str(cache), TMPDIR=str(root / 'tmp'))
            result = subprocess.run(['go', 'test', '-mod=readonly', '-count=1', '-v', './internal/config'],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=180)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


@unittest.skipIf(os.geteuid() == 0 or not shutil.which('go'), '生命周期测试需要非 root 用户和本机 Go')
class LifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compilation = tempfile.TemporaryDirectory(prefix='b-', dir=BUILD)
        root = Path(cls.compilation.name)
        (root / 'tmp').mkdir()
        shutil.copyfile(PACKAGING / 'tests/fixtures/lifecycle_server.go', root / 'fixture.go')
        transport_files = sorted((REPO / 'apps/server/internal/transport').glob('*.go'))
        transport_files = [p for p in transport_files if not p.name.endswith('_test.go')]
        if not transport_files:
            cls.compilation.cleanup()
            raise unittest.SkipTest('等待主代理提供实际 transport/Healthcheck 源码')
        sources = ['fixture.go']
        for i, source in enumerate(transport_files):
            name = f'transport_{i}.go'
            (root / name).write_text(source.read_text().replace('package transport', 'package main', 1))
            sources.append(name)
        cls.binary = root / 'melora-fixture'
        cache = BUILD / 'test-go-cache'
        cache.mkdir(exist_ok=True)
        env = dict(os.environ, GO111MODULE='off', GOENV='off', GOWORK='off', GOTOOLCHAIN='local',
                   GOPROXY='off', GOSUMDB='off', GOCACHE=str(cache), TMPDIR=str(root / 'tmp'))
        result = subprocess.run(['go', 'build', '-o', str(cls.binary), *sources], cwd=root, env=env,
                                capture_output=True, text=True, timeout=180)
        if result.returncode != 0:
            cls.compilation.cleanup()
            raise RuntimeError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.compilation.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='r-', dir=BUILD)
        self.root = Path(self.tmp.name)
        for name in ['target/bin', 'target/web', 'target/config', 'etc', 'var', 'music', 'tmp']:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        shutil.copytree(PACKAGING / 'fpk/cmd', self.root / 'cmd')
        shutil.copyfile(PACKAGING / 'fpk/app/config/melora.env.example', self.root / 'target/config/melora.env.example')
        (self.root / 'target/web/index.html').write_text('TEST FIXTURE ONLY')
        shutil.copyfile(self.binary, self.root / 'target/bin/melora')
        (self.root / 'target/bin/melora').chmod(0o755)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(('TRIM_', 'MELORA_'))}
        self.env.update(TRIM_UID=str(os.geteuid()), TRIM_USERNAME=pwd.getpwuid(os.geteuid()).pw_name,
                        TRIM_APPDEST=str(self.root / 'target'), TRIM_PKGETC=str(self.root / 'etc'),
                        TRIM_PKGVAR=str(self.root / 'var'), TRIM_DATA_ACCESSIBLE_PATHS=str(self.root / 'music'),
                        TRIM_TEMP_LOGFILE=str(self.root / 'tmp/error'))
        self.pid_file = self.root / 'var/run/melora.pid'
        self.config = self.root / 'etc/melora.env'
        self.saved_pid = None
        self.run_hook('install_init')
        self.run_hook('install_callback')

    def tearDown(self):
        if self.saved_pid:
            self.pid_file.write_text(self.saved_pid)
        try:
            self.run_hook('main', 'stop')
        finally:
            self.tmp.cleanup()

    def run_hook(self, hook, *args, expected=0, env=None):
        result = subprocess.run(['bash', str(self.root / 'cmd' / hook), *args],
                                env=env or self.env, capture_output=True, text=True, timeout=40)
        log = self.root / 'var/log/server.log'
        detail = log.read_text(errors='replace')[-4096:] if log.is_file() and not log.is_symlink() else ''
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr + detail)
        if expected == 0 and ((hook == 'main' and args == ('start',)) or
                              (hook == 'config_callback' and self.pid_file.is_file())):
            self.saved_pid = self.pid_file.read_text()
        return result

    def captured_env(self):
        return json.loads((self.root / 'var/data/fixture-env.json').read_text())

    def fast_failure_budget(self):
        # 只缩短测试副本的等待预算，探测仍调用真实 Go Healthcheck、真实 HTTP/Unix Socket。
        # 不替换 healthcheck 命令，不把未就绪进程判定为成功。
        path = self.root / 'cmd/lib.sh'
        text = path.read_text()
        self.assertIn('START_TIMEOUT_SECONDS=30', text)
        path.write_text(text.replace('START_TIMEOUT_SECONDS=30', 'START_TIMEOUT_SECONDS=2')
                           .replace('CLEANUP_TIMEOUT_SECONDS=5', 'CLEANUP_TIMEOUT_SECONDS=1')
                           .replace('UNINSTALL_HELPER_TIMEOUT_SECONDS=15',
                                    'UNINSTALL_HELPER_TIMEOUT_SECONDS=1'))

    def assert_server_log_writer_cleaned(self):
        deadline = time.monotonic() + 2
        pattern = self.root / 'var/run'
        while time.monotonic() < deadline and list(pattern.glob('.server-log-*')):
            time.sleep(.05)
        self.assertEqual(list(pattern.glob('.server-log-*')), [])

    def use_standalone(self, token='', addr='127.0.0.1:3780', allow_insecure=False):
        opt_in = 'MELORA_ALLOW_INSECURE_HTTP=1\n' if allow_insecure else ''
        self.config.write_text(f'MELORA_ACCESS_MODE=standalone\nMELORA_ADDR={addr}\n'
                               f'MELORA_AUTH_TOKEN={token}\n{opt_in}')

    def available_debug_loopback(self):
        # 不关闭主代理服务；只选正式脚本已经支持的空闲回环地址，探测仍走真实 TCP。
        for family, host, address in [(socket.AF_INET, '127.0.0.1', '127.0.0.1:3780'),
                                      (socket.AF_INET6, '::1', '[::1]:3780')]:
            try:
                with socket.socket(family) as probe:
                    probe.bind((host, 3780))
                return address
            except OSError:
                continue
        self.skipTest('两个固定 standalone 回环地址均被占用，不干扰现有服务')

    def test_gateway_start_status_stop_and_env_separation(self):
        self.run_hook('main', 'status', expected=3)
        self.run_hook('main', 'start')
        first = self.pid_file.read_text()
        self.run_hook('main', 'start')
        self.assertEqual(first, self.pid_file.read_text())
        self.run_hook('main', 'status')
        env = self.captured_env()
        self.assertEqual(env['MELORA_ACCESS_MODE'], 'gateway')
        self.assertEqual(env['MELORA_SOCKET'], str(self.root / 'target/app.sock'))
        self.assertEqual(env['MELORA_BASE_PATH'], '/app/melora')
        self.assertEqual(env['MELORA_GATEWAY_AUTH'], 'fnos-admin')
        self.assertEqual(env['MELORA_ADDR'], '')
        self.assertEqual(env['MELORA_AUTH_TOKEN'], '')
        self.assertEqual(env['WEB_DIR'], str(self.root / 'target/web'))
        self.assertEqual(env['MELORA_DATA_DIR'], str(self.root / 'var/data'))
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / 'target/app.sock').stat().st_mode & 0o777, 0o660)
        self.run_hook('main', 'stop')
        self.run_hook('main', 'status', expected=3)
        log = self.root / 'var/log/server.log'
        self.assertIn('fixture_starting', log.read_text())
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)

    def test_standalone_clears_inherited_gateway_variables(self):
        address = self.available_debug_loopback()
        self.use_standalone(addr=address)
        env = dict(self.env, MELORA_SOCKET='/unexpected.sock', MELORA_BASE_PATH='/wrong', MELORA_GATEWAY_AUTH='wrong')
        self.run_hook('main', 'start', env=env)
        captured = self.captured_env()
        self.assertEqual(captured['MELORA_ADDR'], address)
        self.assertEqual(captured['TRIM_DATA_ACCESSIBLE_PATHS'], '')
        for key in ['MELORA_SOCKET', 'MELORA_BASE_PATH', 'MELORA_GATEWAY_AUTH']:
            self.assertEqual(captured[key], '')
        self.assertFalse((self.root / 'target/app.sock').exists())
        self.run_hook('main', 'status', env=env)

    def test_anonymous_external_standalone_rejected(self):
        self.use_standalone(addr='0.0.0.0:3780')
        self.run_hook('main', 'start', expected=1)
        self.assertFalse(self.pid_file.exists())

    def test_external_standalone_requires_explicit_insecure_http_opt_in(self):
        token = 'x' * 32
        self.use_standalone(token=token, addr='0.0.0.0:3780')
        self.run_hook('config_init', expected=1)
        self.use_standalone(token=token, addr='0.0.0.0:3780', allow_insecure=True)
        self.run_hook('config_init')

    def test_insecure_http_opt_in_rejects_noncanonical_values(self):
        for value in ['', 'true', 'yes', '2', '-1']:
            with self.subTest(value=value):
                self.config.write_text('MELORA_ACCESS_MODE=standalone\nMELORA_ADDR=127.0.0.1:3780\n'
                                       f'MELORA_ALLOW_INSECURE_HTTP={value}\n')
                self.run_hook('config_init', expected=0 if value == '' else 1)

    def test_token_not_logged_and_config_not_executed(self):
        token = 'test-only-token-not-a-real-secret-' + 'x' * 32
        self.use_standalone(token=token, addr=self.available_debug_loopback())
        self.run_hook('main', 'start')
        self.assertEqual(token, self.captured_env()['MELORA_AUTH_TOKEN'])
        self.assertNotIn(token, (self.root / 'var/log/server.log').read_text())
        self.assertNotIn(token, (self.root / 'var/log/lifecycle.log').read_text())
        self.run_hook('main', 'stop')
        self.use_standalone(token='$(touch SHOULD_NOT_EXIST)')
        self.run_hook('main', 'start', expected=1)
        self.assertFalse((self.root / 'target/SHOULD_NOT_EXIST').exists())

    def test_invalid_mode_and_permission_and_uid_checks(self):
        self.config.write_text('MELORA_ACCESS_MODE=not-a-mode\n')
        self.run_hook('main', 'start', expected=1)
        self.config.write_text('MELORA_ACCESS_MODE=gateway\n')
        env = dict(self.env, TRIM_UID=str(os.geteuid() + 1))
        self.run_hook('main', 'start', expected=1, env=env)

    def test_legacy_config_permissions_are_repaired_without_rewriting(self):
        original = b'MELORA_ADDR=127.0.0.1:3780\nMELORA_AUTH_TOKEN=old-private-token\nMELORA_DOWNLOAD_ROOT=\n'
        self.config.write_bytes(original)
        inode = self.config.stat().st_ino
        for mode, expected_mode in [(0o644, 0o600), (0o640, 0o600), (0o660, 0o600),
                                    (0o666, 0o600), (0o444, 0o400), (0o440, 0o400),
                                    (0o400, 0o400), (0o600, 0o600)]:
            with self.subTest(mode=oct(mode)):
                self.config.chmod(mode)
                self.run_hook('upgrade_init')
                self.run_hook('upgrade_callback')
                self.assertEqual(self.config.stat().st_mode & 0o7777, expected_mode)
                self.assertEqual(self.config.stat().st_ino, inode)
                self.assertEqual(self.config.read_bytes(), original)
        self.run_hook('main', 'start')
        self.run_hook('main', 'status')
        self.assertNotIn('old-private-token', (self.root / 'var/log/server.log').read_text())

    @unittest.skipUnless(shutil.which('setfacl'), '默认ACL回归需要开发机setfacl')
    def test_new_install_with_inherited_default_acl_has_private_config(self):
        self.config.unlink()
        # 默认ACL会使新文件忽略umask 077；复现NAS类目录继承场景，而非假定用户实际ACL。
        subprocess.run(['setfacl', '-m', 'd:u::rwx,d:g::r-x,d:o::r-x', str(self.config.parent)], check=True)
        self.run_hook('install_callback')
        self.assertEqual(self.config.stat().st_mode & 0o7777, 0o600)
        self.assertEqual(self.config.read_bytes(), (self.root / 'target/config/melora.env.example').read_bytes())
        self.run_hook('main', 'start')
        self.run_hook('main', 'status')

    @unittest.skipUnless(shutil.which('setfacl') and shutil.which('getfacl'), '具名ACL回归需要开发机ACL工具')
    def test_legacy_named_acl_loses_effective_access(self):
        original = self.config.read_bytes()
        subprocess.run(['setfacl', '-m', f'u:{os.geteuid() + 1}:rw', str(self.config)], check=True)
        self.run_hook('upgrade_callback')
        self.assertEqual(self.config.stat().st_mode & 0o7777, 0o600)
        acl = subprocess.run(['getfacl', '-cpn', str(self.config)], check=True, capture_output=True, text=True).stdout
        self.assertIn('mask::---', acl)
        self.assertEqual(self.config.read_bytes(), original)

    def test_config_snapshot_rechecks_link_count_before_chmod(self):
        self.config.chmod(0o644)
        original = self.config.read_bytes()
        tools = self.root / 'test-tools'
        tools.mkdir()
        link = self.root / 'raced-hardlink'
        real_stat = shutil.which('stat')
        wrapper = tools / 'stat'
        wrapper.write_text('#!/bin/bash\n'
                           'if [[ ${1:-} == -c && ${2:-} == "%d:%i:%u:%h" && ! -e $TEST_RACE_LINK ]]; then\n'
                           '  ln -- "$TEST_CONFIG" "$TEST_RACE_LINK" || exit 1\n'
                           'fi\n'
                           'exec "$TEST_REAL_STAT" "$@"\n')
        wrapper.chmod(0o755)
        env = dict(self.env, PATH=str(tools) + os.pathsep + self.env['PATH'],
                   TEST_CONFIG=str(self.config), TEST_RACE_LINK=str(link), TEST_REAL_STAT=real_stat)
        self.run_hook('upgrade_callback', expected=1, env=env)
        self.assertTrue(link.exists())
        self.assertEqual(self.config.stat().st_mode & 0o7777, 0o644)
        self.assertEqual(self.config.read_bytes(), original)

    def test_config_links_and_special_files_are_rejected_without_chmod(self):
        original = self.config.read_bytes()
        target = self.root / 'untouched-config'
        target.write_bytes(original)
        target.chmod(0o644)
        for kind in ('symlink', 'hardlink', 'fifo'):
            with self.subTest(kind=kind):
                self.config.unlink()
                if kind == 'symlink': self.config.symlink_to(target)
                elif kind == 'hardlink': os.link(target, self.config)
                else: os.mkfifo(self.config)
                self.run_hook('upgrade_callback', expected=1)
                self.assertEqual(target.stat().st_mode & 0o7777, 0o644)
                self.assertEqual(target.read_bytes(), original)
        self.config.unlink()
        self.config.write_bytes(original)
        self.config.chmod(0o600)

    def test_old_config_upgrade_defaults_gateway_preserves_data_and_token(self):
        music = self.root / 'music/song.flac'
        part = self.root / 'music/song.part'
        db = self.root / 'var/data/melora.db'
        music.write_bytes(b'USER MUSIC'); part.write_bytes(b'USER PART')
        with sqlite3.connect(db) as connection:
            connection.execute('CREATE TABLE preserved (value TEXT NOT NULL)')
            connection.execute("INSERT INTO preserved VALUES ('user metadata')")
        database_before = db.read_bytes()
        # 包不消费旧 TCP 地址/token：即便其不满足新 standalone 校验，也不阻止网关升级。
        self.config.write_text(f'MELORA_ADDR=old-invalid-tcp\nMELORA_AUTH_TOKEN=old-private-token\nMELORA_DOWNLOAD_ROOT={music.parent}\n')
        before = self.config.read_bytes()
        env = dict(self.env, MELORA_ACCESS_MODE='standalone', MELORA_AUTH_TOKEN='inherited-secret')
        self.run_hook('upgrade_init', env=env)
        self.run_hook('upgrade_callback', env=env)
        self.assertEqual(self.config.read_bytes(), before)
        self.run_hook('main', 'start', env=env)
        captured = self.captured_env()
        self.assertEqual(captured['MELORA_ACCESS_MODE'], 'gateway')
        self.assertEqual(captured['MELORA_AUTH_TOKEN'], '')
        self.assertEqual(captured['MELORA_ADDR'], '')
        self.run_hook('uninstall_init')
        self.run_hook('uninstall_callback')
        self.assertEqual(music.read_bytes(), b'USER MUSIC')
        self.assertEqual(part.read_bytes(), b'USER PART')
        self.assertEqual(db.read_bytes(), database_before)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertNotIn('old-private-token', (self.root / 'var/log/server.log').read_text())

    def test_gateway_stale_download_restriction_does_not_block_application(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.root / 'music', target_is_directory=True)
        for directory in [self.root / 'tmp', self.root / 'missing', alias]:
            with self.subTest(directory=directory):
                self.config.write_text(f'MELORA_DOWNLOAD_ROOT={directory}\n')
                before = self.config.read_bytes()
                self.run_hook('main', 'start')
                # 不清空管理员限制或解引用软链以暗中扩大授权；Go 契约测试校验有效授权为零。
                self.assertEqual(self.captured_env()['MELORA_DOWNLOAD_ROOT'], str(directory))
                self.assertEqual(self.config.read_bytes(), before)
                self.run_hook('main', 'status')
                self.run_hook('main', 'stop')
        self.assertFalse((self.root / 'missing').exists())

    def test_standalone_download_still_requires_explicit_valid_root(self):
        self.use_standalone(addr=self.available_debug_loopback())
        self.run_hook('main', 'start')
        self.assertEqual(self.captured_env()['MELORA_DOWNLOAD_ROOT'], '')
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], '')
        self.run_hook('main', 'stop')
        (self.root / 'alias').symlink_to(self.root / 'music', target_is_directory=True)
        for directory in [self.root / 'missing', self.root / 'alias', Path('/')]:
            self.config.write_text(f'MELORA_ACCESS_MODE=standalone\nMELORA_DOWNLOAD_ROOT={directory}\n')
            self.run_hook('main', 'start', expected=1)

    def test_gateway_forwards_only_system_authorization_and_preserves_config(self):
        music = self.root / 'music'
        extra = self.root / '音乐 资料'
        extra.mkdir(mode=0o750)
        grants = f'{music}:{extra}'
        before, mode = self.config.read_bytes(), extra.stat().st_mode
        env = dict(self.env, TRIM_DATA_ACCESSIBLE_PATHS=grants,
                   wizard_download_root=str(self.root / 'tmp'), TRIM_API_TOKEN='secret-not-forwarded',
                   MELORA_DEMO_MODE='true', MELORA_DEPLOY_MODE='cloud',
                   MELORA_ADMIN_USER='leaked-admin', MELORA_ADMIN_PASSWORD='leaked-password',
                   MELORA_TRUSTED_PROXIES='0.0.0.0/0')
        self.run_hook('main', 'start', env=env)
        captured = self.captured_env()
        self.assertEqual(captured['TRIM_DATA_ACCESSIBLE_PATHS'], grants)
        self.assertEqual(captured['MELORA_DOWNLOAD_ROOT'], '')
        self.assertEqual(captured['TRIM_API_TOKEN'], '')
        self.assertEqual(captured['MELORA_DEMO_MODE'], '')
        self.assertEqual(captured['MELORA_DEPLOY_MODE'], 'nas')
        self.assertEqual(captured['MELORA_ADMIN_USER'], '')
        self.assertEqual(captured['MELORA_ADMIN_PASSWORD'], '')
        self.assertEqual(captured['MELORA_TRUSTED_PROXIES'], '')
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(extra.stat().st_mode, mode)
        self.assertEqual(list(extra.iterdir()), [])
        self.run_hook('main', 'stop')
        env.pop('TRIM_DATA_ACCESSIBLE_PATHS')
        self.run_hook('main', 'start', env=env)
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], '')

    def test_config_file_cannot_forge_system_authorization(self):
        self.config.write_text(f'TRIM_DATA_ACCESSIBLE_PATHS={self.root}/tmp\n')
        self.run_hook('config_init', expected=1)
        self.run_hook('main', 'start', expected=1)
        self.assertFalse(self.pid_file.exists())

    def test_config_callback_restarts_only_running_service_with_new_grants(self):
        extra = self.root / 'new music'
        extra.mkdir()
        before = self.config.read_bytes()
        self.run_hook('main', 'start')
        first = self.pid_file.read_text()
        env = dict(self.env, TRIM_DATA_ACCESSIBLE_PATHS=str(extra))
        self.run_hook('config_init', env=env)
        self.assertEqual(self.pid_file.read_text(), first)
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], str(self.root / 'music'))
        self.run_hook('config_callback', env=env)
        second = self.pid_file.read_text()
        self.assertNotEqual(first, second)
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], str(extra))
        self.assertEqual(self.config.read_bytes(), before)
        self.run_hook('main', 'status', env=env)
        self.run_hook('main', 'stop')
        self.run_hook('config_init', env=env)
        self.run_hook('config_callback', env=env)
        self.assertFalse(self.pid_file.exists())
        self.run_hook('main', 'status', expected=3, env=env)

    def test_config_callback_failure_does_not_restore_stale_authorization(self):
        self.fast_failure_budget()
        self.run_hook('main', 'start')
        before = self.config.read_bytes()
        env = dict(self.env, TRIM_DATA_ACCESSIBLE_PATHS='', MELORA_TEST_SCENARIO='exit')
        self.run_hook('config_callback', env=env, expected=1)
        self.assertFalse(self.pid_file.exists())
        self.run_hook('main', 'status', expected=3)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], '')
        self.assertIn(' startup_failed\n', (self.root / 'var/log/lifecycle.log').read_text())

    def test_config_validation_failure_does_not_stop_existing_service(self):
        self.run_hook('main', 'start')
        before, first = self.config.read_bytes(), self.pid_file.read_text()
        self.config.write_text('MELORA_ACCESS_MODE=invalid\n')
        self.run_hook('config_init', expected=1)
        self.run_hook('config_callback', expected=1)
        self.assertEqual(self.pid_file.read_text(), first)
        self.config.write_bytes(before)
        self.run_hook('main', 'status')

    def test_revoking_grants_restarts_without_dropping_existing_restriction(self):
        music = self.root / 'music'
        music.chmod(0o750)
        song = music / 'preserved.flac'
        song.write_bytes(b'USER MUSIC')
        self.config.write_text(f'MELORA_DOWNLOAD_ROOT={music}\n')
        before = self.config.read_bytes()
        mode = music.stat().st_mode
        self.run_hook('main', 'start')
        first = self.pid_file.read_text()
        env = dict(self.env, TRIM_DATA_ACCESSIBLE_PATHS='')
        self.run_hook('config_init', env=env)
        self.run_hook('config_callback', env=env)
        self.assertNotEqual(first, self.pid_file.read_text())
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], '')
        self.assertEqual(self.captured_env()['MELORA_DOWNLOAD_ROOT'], str(music))
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(song.read_bytes(), b'USER MUSIC')
        self.assertEqual(music.stat().st_mode, mode)
        self.run_hook('main', 'status', env=env)
        self.run_hook('upgrade_init', env=env)
        self.run_hook('upgrade_callback', env=env)
        self.run_hook('main', 'start', env=env)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(self.captured_env()['TRIM_DATA_ACCESSIBLE_PATHS'], '')
        self.assertEqual(self.captured_env()['MELORA_DOWNLOAD_ROOT'], str(music))

    def test_standalone_token_length_boundaries(self):
        for size, expected in [(24, 1), (31, 1), (32, 0), (4096, 0), (4097, 1)]:
            self.use_standalone(token='x' * size)
            self.run_hook('config_callback', expected=expected)

    def test_fnos_managed_symlinks_are_resolved_for_go(self):
        links = self.root / 'apps/melora'
        links.mkdir(parents=True)
        env = dict(self.env)
        for var, target in [('TRIM_APPDEST', 'target'), ('TRIM_PKGETC', 'etc'), ('TRIM_PKGVAR', 'var')]:
            (links / target).symlink_to(self.root / target, target_is_directory=True)
            env[var] = str(links / target)
        self.run_hook('main', 'start', env=env)
        captured = self.captured_env()
        self.assertEqual(captured['WEB_DIR'], str(self.root / 'target/web'))
        self.assertEqual(captured['MELORA_SOCKET'], str(self.root / 'target/app.sock'))
        self.assertEqual(captured['MELORA_DATA_DIR'], str(self.root / 'var/data'))

    def test_download_parent_symlink_is_not_canonicalized_away(self):
        (self.root / 'music/child').mkdir()
        (self.root / 'alias').symlink_to(self.root / 'music', target_is_directory=True)
        restriction = str(self.root / 'alias/child')
        self.config.write_text(f'MELORA_DOWNLOAD_ROOT={restriction}\n')
        self.run_hook('main', 'start')
        self.assertEqual(self.captured_env()['MELORA_DOWNLOAD_ROOT'], restriction)
        self.run_hook('main', 'stop')
        self.config.write_text(f'MELORA_ACCESS_MODE=standalone\nMELORA_DOWNLOAD_ROOT={restriction}\n')
        self.run_hook('main', 'start', expected=1)

    def test_forged_pid_does_not_kill_unrelated_process(self):
        other = subprocess.Popen(['sleep', '30'])
        try:
            self.pid_file.write_text(f'{other.pid} 1\n')
            self.run_hook('main', 'status', expected=3)
            self.run_hook('main', 'stop')
            self.assertIsNone(other.poll())
        finally:
            other.terminate(); other.wait(timeout=5)

    def test_unready_http_or_missing_listener_never_reports_started(self):
        self.fast_failure_budget()
        for scenario in ['no_listener', 'unhealthy', 'invalid_json', 'wrong_identity', 'redirect']:
            with self.subTest(scenario=scenario):
                started = time.monotonic()
                self.run_hook('main', 'start', expected=1, env=dict(self.env, MELORA_TEST_SCENARIO=scenario))
                self.assertLess(time.monotonic() - started, 9)
                self.assertFalse(self.pid_file.exists())
                self.run_hook('main', 'status', expected=3)
        self.assertNotIn(' started\n', (self.root / 'var/log/lifecycle.log').read_text())

    def test_failed_start_cleanup_is_bounded_even_when_sigterm_ignored(self):
        self.fast_failure_budget()
        started = time.monotonic()
        self.run_hook('main', 'start', expected=1,
                      env=dict(self.env, MELORA_TEST_SCENARIO='no_listener', MELORA_TEST_IGNORE_TERM='1'))
        self.assertLess(time.monotonic() - started, 9)
        self.assertFalse(self.pid_file.exists())

    def test_fast_exit_reason_is_preserved(self):
        self.run_hook('main', 'start', expected=1, env=dict(self.env, MELORA_TEST_SCENARIO='exit'))
        self.assertIn('fixture_startup_failed', (self.root / 'var/log/server.log').read_text())
        self.assertFalse(self.pid_file.exists())
        self.assert_server_log_writer_cleaned()

    def test_status_and_repeated_start_require_healthy_response(self):
        self.run_hook('main', 'start')
        (self.root / 'var/data/fixture-scenario').write_text('invalid_json')
        self.run_hook('main', 'status', expected=3)
        self.run_hook('main', 'start', expected=1)
        self.assertEqual(self.pid_file.read_text(), self.saved_pid)

    def test_hung_healthcheck_is_bounded(self):
        self.run_hook('main', 'start')
        started = time.monotonic()
        self.run_hook('main', 'status', expected=3, env=dict(self.env, MELORA_TEST_HANG_PROBE='1'))
        self.assertLess(time.monotonic() - started, 5)

    def test_hung_uninstall_stop_check_is_bounded(self):
        self.fast_failure_budget()
        started = time.monotonic()
        result = self.run_hook(
            'uninstall_init',
            expected=1,
            env=dict(
                self.env,
                MELORA_TEST_HANG_UNINSTALL_STOP_CHECK='1',
                wizard_uninstall_data='purge',
                wizard_uninstall_confirm='purge_confirmed',
            ),
        )
        self.assertLess(time.monotonic() - started, 5)
        self.assertIn('无法证明服务与音源 worker 已全部停止', result.stderr)
        self.assertFalse((self.root / 'var/run/uninstall-helper').exists())
        self.assertFalse((self.root / 'var/run/uninstall-server.path').exists())

    def test_log_symlink_and_hardlink_targets_are_rejected(self):
        log = self.root / 'var/log/server.log'
        for filename in [log, Path(str(log) + '.1')]:
            filename.symlink_to(self.config)
            before = self.config.read_bytes()
            self.run_hook('main', 'start', expected=1)
            self.assertEqual(self.config.read_bytes(), before)
            filename.unlink()
        os.link(self.config, log)
        self.run_hook('main', 'start', expected=1)
        log.unlink()

    def test_pid_and_lock_special_files_do_not_block_or_overwrite_config(self):
        before = self.config.read_bytes()
        os.mkfifo(self.pid_file)
        self.run_hook('main', 'start', expected=1)
        self.pid_file.unlink()
        os.link(self.config, self.pid_file)
        self.run_hook('main', 'start', expected=1)
        self.assertEqual(self.config.read_bytes(), before)
        self.pid_file.unlink()
        lock = self.root / 'var/run/control.lock'
        lock.unlink()
        os.mkfifo(lock)
        self.run_hook('main', 'start', expected=1)
        lock.unlink()

    def test_log_rotation_is_private_and_bounded_on_restart(self):
        self.run_hook('main', 'start')
        self.run_hook('main', 'stop')
        log = self.root / 'var/log/server.log'
        log.write_bytes(b'x' * (6 * 1024 * 1024))
        self.run_hook('main', 'start')
        archive = Path(str(log) + '.1')
        self.assertEqual(archive.stat().st_size, 5 * 1024 * 1024)
        self.assertEqual(archive.stat().st_mode & 0o777, 0o600)
        self.assertIn('fixture_starting', log.read_text())
        self.assertFalse(Path(str(log) + '.4').exists())

    def test_server_log_stays_bounded_during_single_run(self):
        self.run_hook('main', 'start')
        pid = int(self.pid_file.read_text().split()[0])
        marker = b'LOG_BOUNDARY_DONE\n'
        with open(f'/proc/{pid}/fd/2', 'wb', buffering=0) as stream:
            chunk = b'x' * (64 * 1024)
            for _ in range(96):
                stream.write(chunk)
            stream.write(marker)
        log = self.root / 'var/log/server.log'
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            data = log.read_bytes()
            if marker in data:
                break
            time.sleep(.05)
        else:
            self.fail('bounded log writer did not flush marker')
        self.assertLessEqual(log.stat().st_size, 5 * 1024 * 1024)
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        self.run_hook('main', 'status')
        self.run_hook('main', 'stop')
        self.assert_server_log_writer_cleaned()

    def test_healthcheck_is_read_only(self):
        self.run_hook('main', 'start')
        data = self.root / 'var/data'
        before = {p.name: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
                  for p in data.iterdir() if p.is_file()}
        for _ in range(3):
            self.run_hook('main', 'status')
        after = {p.name: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
                 for p in data.iterdir() if p.is_file()}
        self.assertEqual(before, after)


class IconGeneratorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('melora_generate_icons', ICON_GENERATOR)
        cls.generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.generator)

    @staticmethod
    def decode_rgba_png(data):
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise AssertionError('不是 PNG 文件')
        offset = 8
        idat = bytearray()
        width = height = None
        while offset < len(data):
            length = struct.unpack_from('!I', data, offset)[0]
            kind = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + length]
            offset += length + 12
            if kind == b'IHDR':
                width, height, depth, color_type, compression, filtering, interlace = struct.unpack(
                    '!IIBBBBB', payload)
                if (depth, color_type, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                    raise AssertionError('PNG 不是 8-bit RGBA 非交错格式')
            elif kind == b'IDAT':
                idat.extend(payload)
            elif kind == b'IEND':
                break
        if width is None or height is None:
            raise AssertionError('PNG 缺少 IHDR')
        row_bytes = width * 4
        raw = zlib.decompress(bytes(idat))
        if len(raw) != height * (row_bytes + 1):
            raise AssertionError('PNG 解码长度错误')
        rows = []
        for row in range(height):
            start = row * (row_bytes + 1)
            if raw[start] != 0:
                raise AssertionError('测试只接受生成器的 filter 0 PNG')
            rows.append(raw[start + 1:start + row_bytes + 1])
        return width, height, [tuple(row[index:index + 4])
                              for row in rows for index in range(0, row_bytes, 4)]

    def test_committed_icons_match_renderer_and_pair_copies(self):
        assets = {
            64: [PACKAGING / 'fpk/ICON.PNG', PACKAGING / 'fpk/app/ui/images/icon_64.png'],
            256: [PACKAGING / 'fpk/ICON_256.PNG', PACKAGING / 'fpk/app/ui/images/icon_256.png'],
        }
        for size, paths in assets.items():
            generated = self.generator.render(size)
            for path in paths:
                self.assertEqual(path.read_bytes(), generated, path)
            self.assertEqual(paths[0].read_bytes(), paths[1].read_bytes())

    def test_icon_generation_is_idempotent_and_semantically_blue_white(self):
        with tempfile.TemporaryDirectory(prefix='icons-', dir=BUILD) as folder:
            root = Path(folder) / 'fpk'
            root.mkdir()
            self.generator.generate(root)
            paths = [root / 'ICON.PNG', root / 'ICON_256.PNG',
                     root / 'app/ui/images/icon_64.png', root / 'app/ui/images/icon_256.png']
            first = {path.relative_to(root): path.read_bytes() for path in paths}
            self.generator.generate(root)
            second = {path.relative_to(root): path.read_bytes() for path in paths}
            self.assertEqual(first, second)

            blue = (53, 103, 232, 255)
            white = (255, 255, 255, 255)
            for size in (64, 256):
                icon = root / ('ICON.PNG' if size == 64 else 'ICON_256.PNG')
                width, height, pixels = self.decode_rgba_png(icon.read_bytes())
                scale = size // 64
                self.assertEqual((width, height), (size, size))
                self.assertIn(blue, pixels)
                self.assertIn(white, pixels)
                self.assertEqual(pixels[(32 * scale) * size], blue)
                self.assertEqual(pixels[(32 * scale) * size + 33 * scale], white)
                self.assertNotIn((28, 83, 68, 255), pixels)
                self.assertNotIn((241, 248, 231, 255), pixels)
                self.assertTrue(
                    any(0 < alpha < 255 for _, _, _, alpha in pixels),
                    f'{size}px 图标必须保留抗锯齿透明边缘',
                )
                for red, green, blue_channel, alpha in pixels:
                    if alpha == 0:
                        self.assertEqual((red, green, blue_channel), (0, 0, 0))
                    elif alpha < 255:
                        self.assertEqual((red, green, blue_channel), (53, 103, 232))


if __name__ == '__main__':
    unittest.main(verbosity=2)
