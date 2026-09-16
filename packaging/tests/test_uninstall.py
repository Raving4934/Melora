#!/usr/bin/env python3
"""卸载数据红绿：真实后端/worker、真实 flock，只操作新建临时目录。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import sys
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[2]
FPK = REPO / 'packaging/fpk'


class ResourceWizardTests(unittest.TestCase):
    def test_independent_empty_exports_share(self):
        self.assertEqual(json.loads((FPK / 'config/resource').read_text()),
                         {'data-share': {'shares': [{'name': 'melora/exports'}]}})

    def test_default_keep_and_unchecked_confirmation(self):
        steps = json.loads((FPK / 'wizard/uninstall').read_text())
        fields = {item['field']: item for step in steps for item in step['items'] if 'field' in item}
        self.assertEqual(set(fields), {'wizard_uninstall_data', 'wizard_uninstall_confirm'})
        choice = fields['wizard_uninstall_data']
        self.assertEqual(choice['type'], 'radio')
        self.assertEqual(choice['initValue'], 'keep')
        self.assertEqual({o['value'] for o in choice['options']}, {'keep', 'purge'})
        confirm = fields['wizard_uninstall_confirm']
        self.assertEqual(confirm['type'], 'checkbox')
        # 官方 fnpack 1.2.3 拒绝数组 initValue；空字符串表示无预设确认，仍需用户勾选。
        self.assertEqual(confirm['initValue'], '')
        self.assertEqual([o['value'] for o in confirm['options']], ['purge_confirmed'])
        self.assertFalse(any(r.get('required') for r in confirm.get('rules', [])),
                         '默认保留不应被强制要求确认删除')


class LifecycleRoutingTests(unittest.TestCase):
    def invoke(self, choice):
        with tempfile.TemporaryDirectory(prefix='melora-lifecycle-routing-') as tmp:
            trace = Path(tmp) / 'trace'
            env = dict(os.environ, TRACE=str(trace), wizard_uninstall_data=choice,
                       wizard_uninstall_confirm='purge_confirmed')
            script = r'''
source "$REVIEW_LIB"
check_paths() { printf '%s\n' check_paths >> "$TRACE"; SERVER=/fixture/melora; }
lock_runtime() { printf '%s\n' lock_runtime >> "$TRACE"; }
stop_service() { printf '%s\n' stop_service >> "$TRACE"; }
stage_uninstall_helper() { printf '%s\n' stage_uninstall_helper >> "$TRACE"; }
run_uninstall_helper() { printf 'helper:%s\n' "$2" >> "$TRACE"; }
uninstall_init_service
'''
            result = subprocess.run(['bash', '-c', script], env=dict(env, REVIEW_LIB=str(FPK / 'cmd/lib.sh')),
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return trace.read_text().splitlines()

    def test_keep_and_missing_choice_skip_private_cleanup_validation(self):
        for choice in ('', 'keep'):
            with self.subTest(choice=choice):
                self.assertEqual(self.invoke(choice), [
                    'check_paths', 'lock_runtime', 'stop_service', 'stage_uninstall_helper'])

    def test_purge_keeps_full_preflight_stop_check_and_cleanup(self):
        self.assertEqual(self.invoke('purge'), [
            'check_paths', 'lock_runtime', 'helper:--uninstall-preflight', 'stop_service',
            'helper:--uninstall-stop-check', 'stage_uninstall_helper', 'helper:--uninstall-cleanup'])


class LockSafetyTests(unittest.TestCase):
    """Dirac 的确定性时序：真正替换最后检查后的锁路径，不模拟 open/flock 结果。"""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='melora-lock-safety-')
        self.root = Path(self.tmp.name)
        for name in ('target', 'etc', 'var', 'var/run'):
            (self.root / name).mkdir(mode=0o700)
        self.lock = self.root / 'var/run/control.lock'
        self.lock.write_bytes(b'LOCK_CONTENT_MUST_NOT_BE_TRUNCATED')
        self.lock.chmod(0o600)
        self.music = self.root / 'music-sentinel'
        self.music.write_bytes(b'MUSIC_SENTINEL_MUST_SURVIVE')
        self.music.chmod(0o600)
        self.external = self.root / 'must-not-be-created'
        self.env = dict(os.environ, TRIM_UID=str(os.geteuid()), TRIM_APPDEST=str(self.root / 'target'),
                        TRIM_PKGETC=str(self.root / 'etc'), TRIM_PKGVAR=str(self.root / 'var'),
                        TRIM_TEMP_LOGFILE=str(self.root / 'error'), REVIEW_ROOT=str(self.root),
                        REVIEW_LIB=str(FPK / 'cmd/lib.sh'))

    def tearDown(self):
        self.tmp.cleanup()

    def run_race(self, kind, invocation='lock_runtime'):
        env = dict(self.env, REVIEW_KIND=kind, wizard_uninstall_data='purge' if kind.endswith('-purge') else 'keep',
                   wizard_uninstall_confirm='purge_confirmed')
        script = r'''source "$REVIEW_LIB"
RUN_DIR="$TRIM_PKGVAR/run"
stat() {
    command stat "$@"
    local result=$?
    if [[ ${1:-} == -c && ${2:-} == %h && ${4:-} == "$RUN_DIR/control.lock" && ! -e "$REVIEW_ROOT/swapped" ]]; then
        : > "$REVIEW_ROOT/swapped"
        command rm -- "$RUN_DIR/control.lock"
        case "$REVIEW_KIND" in
            symlink*) command ln -s "$REVIEW_ROOT/music-sentinel" "$RUN_DIR/control.lock" ;;
            dangling*) command ln -s "$REVIEW_ROOT/must-not-be-created" "$RUN_DIR/control.lock" ;;
            fifo*) command mkfifo "$RUN_DIR/control.lock" ;;
        esac
    fi
    return "$result"
}
''' + invocation
        return subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True, timeout=12)

    def test_existing_lock_contents_are_never_truncated(self):
        result = subprocess.run(['bash', '-c', 'source "$REVIEW_LIB"; RUN_DIR="$TRIM_PKGVAR/run"; lock_runtime'],
                                env=self.env, capture_output=True, text=True, timeout=12)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lock.read_bytes(), b'LOCK_CONTENT_MUST_NOT_BE_TRUNCATED')

    def test_symlink_swap_cannot_truncate_music_on_default_uninstall(self):
        result = self.run_race('symlink-keep', 'hook uninstall_init')
        self.assertEqual(self.music.read_bytes(), b'MUSIC_SENTINEL_MUST_SURVIVE', result.stderr)
        self.assertNotEqual(result.returncode, 0)

    def test_symlink_swap_cannot_truncate_music_on_purge_uninstall(self):
        result = self.run_race('symlink-purge', 'hook uninstall_init')
        self.assertEqual(self.music.read_bytes(), b'MUSIC_SENTINEL_MUST_SURVIVE', result.stderr)
        self.assertNotEqual(result.returncode, 0)

    def test_dangling_symlink_swap_cannot_create_external_file(self):
        result = self.run_race('dangling')
        self.assertFalse(self.external.exists(), result.stderr)
        self.assertNotEqual(result.returncode, 0)

    def test_atomic_creation_does_not_follow_new_dangling_symlink(self):
        self.lock.unlink()
        script = r'''source "$REVIEW_LIB"
RUN_DIR="$TRIM_PKGVAR/run"
creation_race() {
    if [[ $BASH_COMMAND == 'set -o noclobber' && ! -e "$REVIEW_ROOT/swapped" ]]; then
        : > "$REVIEW_ROOT/swapped"
        command ln -s "$REVIEW_ROOT/must-not-be-created" "$RUN_DIR/control.lock"
    fi
    return 0
}
set -T
trap creation_race DEBUG
lock_runtime
'''
        result = subprocess.run(['bash', '-c', script], env=self.env, capture_output=True, text=True, timeout=12)
        self.assertTrue((self.root / 'swapped').exists(), 'creation scheduling point was not reached')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.external.exists(), result.stderr)

    def test_atomic_creation_fifo_race_is_bounded(self):
        self.lock.unlink()
        script = r'''source "$REVIEW_LIB"
RUN_DIR="$TRIM_PKGVAR/run"
creation_race() {
    if [[ $BASH_COMMAND == 'set -o noclobber' && ! -e "$REVIEW_ROOT/swapped" ]]; then
        : > "$REVIEW_ROOT/swapped"
        command mkfifo "$RUN_DIR/control.lock"
    fi
    return 0
}
set -T
trap creation_race DEBUG
lock_runtime
'''
        before = time.monotonic()
        result = subprocess.run(['bash', '-c', script], env=self.env, capture_output=True, text=True, timeout=12)
        self.assertTrue((self.root / 'swapped').exists())
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - before, 10)

    def test_lock_path_replacement_during_flock_is_rejected(self):
        script = r'''source "$REVIEW_LIB"
RUN_DIR="$TRIM_PKGVAR/run"
flock() {
    command flock "$@" || return
    command mv "$RUN_DIR/control.lock" "$RUN_DIR/original.lock"
    printf 'NEW_UNKNOWN_LOCK' > "$RUN_DIR/control.lock"
}
lock_runtime
'''
        result = subprocess.run(['bash', '-c', script], env=self.env, capture_output=True, text=True, timeout=12)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.lock.read_bytes(), b'NEW_UNKNOWN_LOCK')
        self.assertEqual((self.root / 'var/run/original.lock').read_bytes(), b'LOCK_CONTENT_MUST_NOT_BE_TRUNCATED')

    def test_fifo_swap_open_is_bounded(self):
        start = time.monotonic()
        result = self.run_race('fifo')
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - start, 10)
        self.assertEqual(self.music.read_bytes(), b'MUSIC_SENTINEL_MUST_SURVIVE')


@unittest.skipIf(sys.platform != 'linux' or os.geteuid() == 0 or not shutil.which('go'), '需要非 root Linux 包用户与 Go')
class UninstallTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix='melora-uninstall-build-')
        cls.binary = Path(cls.build.name) / 'melora'
        result = subprocess.run(['go', 'build', '-o', str(cls.binary), './cmd/melora'],
                                cwd=REPO / 'apps/server', capture_output=True, text=True, timeout=180)
        if result.returncode:
            cls.build.cleanup()
            raise RuntimeError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='melora-uninstall-')
        self.root = Path(self.tmp.name)
        for name in ('target/bin', 'target/web', 'target/config', 'etc', 'var/data/lx-sources',
                     'var/run', 'var/log', 'home', 'meta', 'music', 'appshare/melora/exports'):
            (self.root / name).mkdir(parents=True, exist_ok=True, mode=0o700)
        # pathlib 的 parents=True 不继承叶节点 mode；显式模拟安装后的私有目录。
        for name in ('etc', 'var', 'var/data', 'var/data/lx-sources', 'var/run', 'var/log',
                     'home', 'meta', 'appshare/melora', 'appshare/melora/exports'):
            (self.root / name).chmod(0o700)
        shutil.copytree(FPK / 'cmd', self.root / 'cmd')
        self.exe = self.root / 'target/bin/melora'
        shutil.copyfile(self.binary, self.exe)
        self.exe.chmod(0o755)
        (self.root / 'target/web/index.html').write_text('<!doctype html><title>ISOLATED TEST ONLY</title>')
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(('TRIM_', 'MELORA_', 'wizard_'))}
        self.env.update(TRIM_UID=str(os.geteuid()), TRIM_APPDEST=str(self.root / 'target'),
                        TRIM_PKGETC=str(self.root / 'etc'), TRIM_PKGVAR=str(self.root / 'var'),
                        TRIM_PKGHOME=str(self.root / 'home'), TRIM_PKGMETA=str(self.root / 'meta'),
                        TRIM_TEMP_LOGFILE=str(self.root / 'error'), MELORA_DEMO_MODE='1',
                        TRIM_DATA_ACCESSIBLE_PATHS=str(self.root / 'music'),
                        TRIM_DATA_SHARE_PATHS=str(self.root / 'appshare/melora/exports'))
        self.confirm = dict(self.env, wizard_uninstall_data='purge',
                            wizard_uninstall_confirm='purge_confirmed')
        self.children = []
        self.pid = self.root / 'var/run/melora.pid'
        self.lock = self.root / 'var/run/control.lock'
        self.lock.write_bytes(b'')
        self.lock.chmod(0o600)
        self.known = {}
        for name in ('etc/melora.env', 'var/data/melora.db', 'var/data/melora.db-wal',
                     'var/data/melora.db-shm', 'var/data/melora.db-journal',
                     'var/log/server.log', 'var/log/server.log.1', 'var/log/server.log.2',
                     'var/log/server.log.3', 'var/log/lifecycle.log', 'var/log/lifecycle.log.1'):
            self.put(name, ('PRIVATE ' + name).encode())
        code = b'throw new Error("UNINSTALL MUST NEVER EXECUTE THIS SCRIPT");'
        sha = hashlib.sha256(code).hexdigest()
        self.source_name = f'var/data/lx-sources/{sha[:24]}.js'
        self.put(self.source_name, code)
        self.registry_name = 'var/data/lx-sources/registry.json'
        self.put(self.registry_name, json.dumps({'items': [{'id': sha[:24], 'sha256': sha}],
                                               'activeSourceId': sha[:24]}).encode())
        self.unknown = {}
        for name in ('music/song.flac', 'music/song.part', 'appshare/melora/exports/keep.txt', 'var/data/backup.db',
                     'var/data/lx-sources/manual.js', 'var/data/lx-sources/' + 'a' * 24 + '.js',
                     'var/data/lx-sources/.registry-unfinished', 'var/log/server.log.4',
                     'var/run/manual.pid', 'var/data/unknown/note.txt', 'etc/custom.env'):
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'UNKNOWN MUST SURVIVE')
            self.unknown[name] = p.read_bytes()

    def put(self, name, data):
        p = self.root / name
        p.write_bytes(data)
        p.chmod(0o600)
        self.known[name] = data

    def tearDown(self):
        # 只回收本测试自己创建的进程，不根据目录内 PID 杀进程。
        for child in self.children:
            if child.poll() is None:
                child.kill()
            child.communicate(timeout=10)
        self.tmp.cleanup()

    def hook(self, env=None, expected=0, hook='uninstall_init'):
        result = subprocess.run(['bash', str(self.root / 'cmd' / hook)], env=env or self.env,
                                capture_output=True, text=True, timeout=40)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def helper(self, locked=True, expected=0, command='--uninstall-cleanup', env=None):
        if locked:
            args = ['bash', '-c', 'exec 9>"$TRIM_PKGVAR/run/control.lock"; flock -x 9; '
                    'exec "$TRIM_APPDEST/bin/melora" "$1"', '_', command]
        else:
            args = [str(self.exe), command]
        result = subprocess.run(args, env=env or self.confirm, capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def assert_preserved(self):
        for name, data in {**self.known, **self.unknown}.items():
            self.assertEqual((self.root / name).read_bytes(), data, name)

    def assert_cleaned(self, expect_lock=True):
        for name in self.known:
            self.assertFalse((self.root / name).exists(), name)
        for name, data in self.unknown.items():
            self.assertEqual((self.root / name).read_bytes(), data, name)
        self.assertEqual(self.lock.is_file(), expect_lock)
        self.assertTrue((self.root / 'var/data/lx-sources').is_dir())

    def worker(self):
        child = subprocess.Popen([str(self.exe), '--lx-worker'], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env)
        self.children.append(child)
        # worker 不输入脚本：只阻塞读取协议，确实运行同一正式 exe。
        for _ in range(100):
            self.assertIsNone(child.poll())
            try:
                if os.readlink(f'/proc/{child.pid}/exe') == str(self.exe):
                    return child
            except FileNotFoundError:
                pass
            time.sleep(.01)
        self.fail('worker did not exec')

    def start_server(self):
        # 不探测现有应用端口：真实服务只绑定测试自己的 Unix socket。
        for name in ('melora.db', 'melora.db-wal', 'melora.db-shm', 'melora.db-journal'):
            (self.root / 'var/data' / name).unlink(missing_ok=True)
        env = dict(self.env, MELORA_ACCESS_MODE='gateway', MELORA_GATEWAY_AUTH='fnos-admin',
                   MELORA_BASE_PATH='/app/melora', MELORA_SOCKET=str(self.root / 'target/app.sock'),
                   MELORA_DATA_DIR=str(self.root / 'var/data'), WEB_DIR=str(self.root / 'target/web'))
        child = subprocess.Popen([str(self.exe)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(child)
        for _ in range(200):
            self.assertIsNone(child.poll(), 'isolated server exited before readiness')
            try:
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                    client.settimeout(.1)
                    client.connect(env['MELORA_SOCKET'])
                    client.sendall(b'GET /app/melora/health HTTP/1.0\r\n\r\n')
                    if b'200 OK' in client.recv(2048):
                        start = Path(f'/proc/{child.pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]
                        self.pid.write_text(f'{child.pid} {start}\n')
                        self.pid.chmod(0o600)
                        return child
            except OSError:
                pass
            time.sleep(.025)
        self.fail('isolated server readiness timed out')

    def snapshot(self):
        return {name: (self.root / name).read_bytes() for name in (*self.known, *self.unknown)
                if (self.root / name).is_file()}

    def test_healthy_server_is_stopped_before_cleanup(self):
        child = self.start_server()
        self.hook(self.confirm)
        child.communicate(timeout=5)
        self.assertEqual(child.returncode, 0)
        self.assert_cleaned()
        self.assertFalse(self.pid.exists())

    def test_missing_pid_does_not_make_live_server_stopped(self):
        child = self.start_server()
        self.pid.unlink()
        result = self.hook(self.confirm, expected=1)
        self.assertIn('仍存活', result.stderr)
        self.assertIsNone(child.poll())
        self.assertTrue((self.root / 'var/data/melora.db').exists())

    def test_stop_timeout_refuses_cleanup_and_holds_lock_against_start(self):
        child = self.start_server()
        child.send_signal(signal.SIGSTOP)
        # 只缩短测试临时副本的停服等待；真实 PID/TERM/flock/辅助都不替换。
        lib = self.root / 'cmd/lib.sh'
        code = lib.read_text()
        self.assertIn('for i in {1..20}; do', code)
        lib.write_text(code.replace('for i in {1..20}; do', 'for i in {1..2}; do'))
        before = self.snapshot()
        uninstall = subprocess.Popen(['bash', str(self.root / 'cmd/uninstall_init')],
                                     env=self.confirm, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(uninstall)
        # SIGTERM 已排队说明 preflight 成功且 stop_service 正在同一持锁区间等待。
        for _ in range(100):
            self.assertIsNone(uninstall.poll())
            status = Path(f'/proc/{child.pid}/status').read_text()
            pending = sum(int(line.split()[1], 16) for line in status.splitlines()
                          if line.startswith(('SigPnd:', 'ShdPnd:')))
            if pending & (1 << (signal.SIGTERM - 1)):
                break
            time.sleep(.01)
        else:
            self.fail('stop hook did not queue SIGTERM')
        start = subprocess.Popen(['bash', str(self.root / 'cmd/main'), 'start'], env=self.env,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(start)
        time.sleep(.25)
        self.assertIsNone(start.poll(), 'start bypassed the lock held throughout stop/cleanup')
        out, error = uninstall.communicate(timeout=10)
        self.assertEqual(uninstall.returncode, 1, out + error)
        self.assertIn('未强杀', error.decode())
        self.assertEqual(self.snapshot(), before)
        self.assertTrue(self.pid.exists())
        self.assertIsNone(child.poll())
        # 锁释放后 start 因合成配置不可用而失败，不会启动第二个服务。
        start.communicate(timeout=10)
        self.assertEqual(start.returncode, 1)

    def test_fd9_unlocked_or_wrong_open_description_is_rejected(self):
        for setup in ('exec 9>"$TRIM_PKGVAR/run/control.lock"',
                      'exec 9>"$TRIM_PKGVAR/run/wrong.lock"; flock -x 9',
                      'exec 8>"$TRIM_PKGVAR/run/control.lock"; flock -x 8; '
                      'exec 9>"$TRIM_PKGVAR/run/control.lock"',
                      'exec 9>"$TRIM_PKGVAR/run/control.lock"; flock -s 9'):
            result = subprocess.run(['bash', '-c', setup +
                                     '; exec "$TRIM_APPDEST/bin/melora" --uninstall-cleanup'],
                                    env=self.confirm, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('FD9', result.stderr)
            self.assert_preserved()

    def test_helper_from_another_path_is_rejected_even_with_same_lock(self):
        copied = self.root / 'another-melora'
        shutil.copyfile(self.binary, copied)
        copied.chmod(0o755)
        result = subprocess.run(['bash', '-c', 'exec 9>"$TRIM_PKGVAR/run/control.lock"; '
                                 'flock -x 9; exec "$1" --uninstall-cleanup', '_', str(copied)],
                                env=self.confirm, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('精确可执行实例', result.stderr)
        self.assert_preserved()

    def test_wrong_package_uid_is_rejected(self):
        self.hook(dict(self.confirm, TRIM_UID=str(os.geteuid() + 1)), expected=1)
        self.assert_preserved()

    def test_fifo_in_fixed_target_is_rejected_without_blocking(self):
        target = self.root / 'var/log/server.log'
        target.unlink()
        os.mkfifo(target, 0o600)
        del self.known['var/log/server.log']
        self.hook(self.confirm, expected=1)
        self.assert_preserved()

    def test_untrusted_private_directory_permissions_are_not_silently_changed(self):
        target = self.root / 'var/data'
        target.chmod(0o777)
        self.hook(self.confirm, expected=1)
        self.assertEqual(target.stat().st_mode & 0o777, 0o777)
        self.assert_preserved()

    def test_orphan_worker_after_service_stop_still_blocks_cleanup(self):
        server = self.start_server()
        worker = self.worker()
        result = self.hook(self.confirm, expected=1)
        self.assertIn('仍存活', result.stderr)
        server.communicate(timeout=5)
        self.assertEqual(server.returncode, 0)
        self.assertIsNone(worker.poll())
        self.assertTrue((self.root / 'var/data/melora.db').is_file())
        self.assertTrue((self.root / 'etc/melora.env').is_file())
        for name, data in self.unknown.items():
            self.assertEqual((self.root / name).read_bytes(), data)

    def test_worker_pid_is_not_accepted_as_managed_server_pid(self):
        worker = self.worker()
        start = Path(f'/proc/{worker.pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]
        self.pid.write_text(f'{worker.pid} {start}\n')
        self.pid.chmod(0o600)
        result = self.hook(self.confirm, expected=1)
        self.assertIn('不是受管主服务实例', result.stderr)
        self.assertIsNone(worker.poll())
        self.assertTrue(self.pid.exists())
        self.assert_preserved()

    def test_deleted_managed_server_can_stop_then_cleanup(self):
        server = self.start_server()
        self.exe.unlink()
        shutil.copyfile(self.binary, self.exe)
        self.exe.chmod(0o755)
        self.assertEqual(os.readlink(f'/proc/{server.pid}/exe'), str(self.exe) + ' (deleted)')
        self.hook(self.confirm)
        server.communicate(timeout=5)
        self.assertEqual(server.returncode, 0)
        self.assert_cleaned()

    def test_wizard_values_only_in_callback_clean_after_removed_target(self):
        # fnOS 可在 init 只要求停服，而把最终向导值提交给 callback。
        self.hook(self.env)
        self.assertTrue((self.root / 'var/run/uninstall-helper').is_file())
        self.assertTrue((self.root / 'var/run/uninstall-server.path').is_file())
        self.assert_preserved()
        shutil.rmtree(self.root / 'target')  # 模拟平台在 callback 前移除安装文件。
        self.hook(self.confirm, hook='uninstall_callback')
        self.assert_cleaned(expect_lock=False)
        self.assertFalse((self.root / 'var/run/uninstall-helper').exists())
        self.assertFalse((self.root / 'var/run/uninstall-server.path').exists())
        self.assertFalse((self.root / 'home').exists())
        self.assertFalse((self.root / 'meta').exists())

    def test_callback_without_init_proof_refuses_purge(self):
        shutil.rmtree(self.root / 'target')
        result = self.hook(self.confirm, expected=1, hook='uninstall_callback')
        self.assertIn('辅助文件不可信', result.stderr)
        self.assert_preserved()

    def test_callback_keep_discards_only_internal_snapshot(self):
        self.hook(self.env)
        shutil.rmtree(self.root / 'target')
        self.hook(self.env, hook='uninstall_callback')
        self.assert_preserved()
        self.assertTrue(self.lock.is_file())
        self.assertFalse((self.root / 'var/run/uninstall-helper').exists())
        self.assertFalse((self.root / 'var/run/uninstall-server.path').exists())

    def test_callback_prunes_only_declared_empty_private_directories(self):
        for name in list(self.unknown):
            if name.startswith(('etc/', 'var/', 'appshare/')):
                (self.root / name).unlink()
                del self.unknown[name]
        # 未知空目录也按“未知内容”保留；本例显式移除测试自己创建的未知目录，
        # 只验证声明目录在确实为空时会被收尾。
        (self.root / 'var/data/unknown').rmdir()
        self.hook(self.env)
        shutil.rmtree(self.root / 'target')
        self.hook(self.confirm, hook='uninstall_callback')
        for name in ('etc', 'var', 'home', 'meta', 'appshare/melora/exports', 'appshare/melora'):
            self.assertFalse((self.root / name).exists(), name)
        for name, data in self.unknown.items():
            self.assertEqual((self.root / name).read_bytes(), data, name)

    def test_install_does_not_copy_private_data_to_exports_or_change_grants(self):
        self.put('etc/melora.env', b'MELORA_ACCESS_MODE=gateway\n')
        (self.root / 'appshare/melora/exports/keep.txt').unlink()
        del self.unknown['appshare/melora/exports/keep.txt']
        before = self.snapshot()
        self.hook(hook='install_callback')
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(list((self.root / 'appshare/melora/exports').iterdir()), [])
        self.assertEqual(self.env['TRIM_DATA_ACCESSIBLE_PATHS'], str(self.root / 'music'))
        self.assertEqual((self.root / 'etc/melora.env').read_bytes(), b'MELORA_ACCESS_MODE=gateway\n')

    def test_readonly_directory_is_rejected_in_preflight_not_half_cleaned(self):
        target = self.root / 'var/data'
        target.chmod(0o500)
        try:
            result = self.hook(self.confirm, expected=1)
            self.assertIn('不可写', result.stderr)
            self.assert_preserved()
        finally:
            target.chmod(0o700)

    def test_invalid_confirmation_or_registry_does_not_stop_running_service(self):
        server = self.start_server()
        self.hook(dict(self.confirm, wizard_uninstall_confirm='false'), expected=1)
        self.assertIsNone(server.poll())
        self.assertTrue(self.pid.exists())
        self.put(self.registry_name, b'broken registry')
        result = self.hook(self.confirm, expected=1)
        self.assertIn('音源登记损坏', result.stderr)
        self.assertIsNone(server.poll())
        self.assertTrue(self.pid.exists())
        self.assertTrue((self.root / 'etc/melora.env').exists())
        self.assertTrue((self.root / 'var/data/melora.db').exists())

    def test_default_and_explicit_keep_preserve_everything(self):
        self.hook()
        self.hook(dict(self.env, wizard_uninstall_data='keep'))
        self.hook(dict(self.env, wizard_uninstall_data='keep', wizard_uninstall_confirm='nonsense'))
        self.assert_preserved()

    def test_keep_does_not_enter_private_cleanup_validation(self):
        # fnOS 管理的 PkgVar 根可能不满足清理器对“待删除私有根”的 owner-only 约束；
        # 保留数据只需安全停服和建立 callback 快照，不应因此阻断更新。
        variable = self.root / 'var'
        variable.chmod(0o755)
        try:
            self.hook(dict(self.env, wizard_uninstall_data='keep'))
            self.assertEqual(variable.stat().st_mode & 0o777, 0o755)
            self.assert_preserved()
        finally:
            variable.chmod(0o700)

    def test_external_clear_request_without_custom_confirmation_is_not_silent_keep(self):
        for choice in (None, '', 'keep'):
            with self.subTest(choice=choice):
                env = dict(self.env, wizard_delete_data='true')
                if choice is not None:
                    env['wizard_uninstall_data'] = choice
                result = self.hook(env, expected=1)
                self.assertIn('清理', result.stderr)
                self.assertIn('向导', result.stderr)
                self.assert_preserved()

    def test_missing_selection_reports_preservation_and_missing_fields(self):
        result = self.hook()
        self.assertIn('未收到', result.stdout)
        self.assertIn('保留', result.stdout)
        self.assert_preserved()

    def test_external_default_false_does_not_override_confirmed_custom_purge(self):
        self.hook(dict(self.confirm, wizard_delete_data='false'))
        self.assert_cleaned()

    def test_purge_without_confirmation_refused(self):
        self.hook(dict(self.env, wizard_uninstall_data='purge'), expected=1)
        self.assert_preserved()

    def test_unknown_inputs_refused_without_deleting(self):
        for value in ('false', 'true', '1', '[]', '["purge_confirmed","other"]', 'purge_confirmed,other'):
            with self.subTest(value=value):
                self.hook(dict(self.confirm, wizard_uninstall_confirm=value), expected=1)
                self.assert_preserved()
        self.hook(dict(self.confirm, wizard_uninstall_data='unknown'), expected=1)
        self.assert_preserved()

    def test_confirmed_cleanup_is_exact_and_idempotent(self):
        before = self.lock.stat().st_ino
        self.hook(self.confirm)
        self.assert_cleaned()
        self.assertEqual(self.lock.stat().st_ino, before)
        self.hook(self.confirm)
        self.hook(self.confirm, hook='uninstall_callback')
        self.assert_cleaned(expect_lock=False)

    def test_json_single_checkbox_encoding(self):
        self.hook(dict(self.confirm, wizard_uninstall_confirm='["purge_confirmed"]'))
        self.assert_cleaned()

    def test_corrupt_registry_preserves_all_other_data(self):
        for data in (b'broken', b'{}', b'null', b'{"items":null}',
                     b'{"items":[],"items":[]}',
                     b'{"items":[{"id":"../outside","sha256":"' + b'a' * 64 + b'"}]}'):
            with self.subTest(data=data):
                self.put(self.registry_name, data)
                result = self.hook(self.confirm, expected=1)
                self.assertIn('音源登记损坏', result.stderr)
                self.assert_preserved()

    def test_case_fold_registry_does_not_clean_different_source(self):
        state = json.loads((self.root / self.registry_name).read_bytes())
        other_code = b'UNREGISTERED_SCRIPT_MUST_SURVIVE'
        other_hash = hashlib.sha256(other_code).hexdigest()
        other_name = 'var/data/lx-sources/' + other_hash[:24] + '.js'
        (self.root / other_name).write_bytes(other_code)
        (self.root / other_name).chmod(0o600)
        self.unknown[other_name] = other_code
        for alias in ('SHA256', 'Sha256', 'ſha256'):
            item = dict(state['items'][0], ID=other_hash[:24])
            item[alias] = other_hash
            self.put(self.registry_name, json.dumps({'items': [item], 'activeSourceId': ''}).encode())
            result = self.hook(self.confirm, expected=1)
            self.assertIn('音源登记损坏', result.stderr)
            self.assert_preserved()

    def test_script_hash_mismatch_preserves_all_other_data(self):
        self.put(self.source_name, b'changed script')
        result = self.hook(self.confirm, expected=1)
        self.assertIn('完整性校验失败', result.stderr)
        self.assert_preserved()

    def test_missing_registry_does_not_scan_js_filenames(self):
        (self.root / self.registry_name).unlink()
        del self.known[self.registry_name]
        self.unknown[self.source_name] = self.known.pop(self.source_name)
        self.hook(self.confirm)
        self.assert_cleaned()

    def test_unlocked_cli_refused(self):
        self.helper(locked=False, expected=1)
        self.assert_preserved()

    def test_locked_cli_requires_explicit_confirmation(self):
        self.helper(env=self.env, expected=1)
        self.assert_preserved()

    def test_same_executable_worker_without_pid_refuses_cleanup(self):
        child = self.worker()
        self.hook(self.confirm, expected=1)
        self.assertIsNone(child.poll())
        self.assert_preserved()

    def test_deleted_same_path_worker_without_pid_refuses_cleanup(self):
        child = self.worker()
        self.exe.unlink()
        shutil.copyfile(self.binary, self.exe)
        self.exe.chmod(0o755)
        self.assertEqual(os.readlink(f'/proc/{child.pid}/exe'), str(self.exe) + ' (deleted)')
        self.hook(self.confirm, expected=1)
        self.assertIsNone(child.poll())
        self.assert_preserved()

    def test_same_uid_unrelated_executable_is_not_killed(self):
        child = subprocess.Popen(['sleep', '30'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(child)
        self.hook(self.confirm)
        self.assertIsNone(child.poll())
        self.assert_cleaned()

    def test_foreign_pid_refused_and_not_removed(self):
        child = subprocess.Popen(['sleep', '30'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(child)
        start = Path(f'/proc/{child.pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]
        self.pid.write_text(f'{child.pid} {start}\n')
        self.pid.chmod(0o600)
        self.hook(self.confirm, expected=1)
        self.assertTrue(self.pid.exists())
        self.assertIsNone(child.poll())
        self.assert_preserved()

    def test_target_symlink_refused_and_external_target_untouched(self):
        p = self.root / 'var/data/melora.db'
        original = p.read_bytes()
        p.unlink()
        target = self.root / 'outside.db'
        target.write_bytes(original)
        p.symlink_to(target)
        self.hook(self.confirm, expected=1)
        self.assertTrue(p.is_symlink())
        self.assert_preserved()
        self.assertEqual(target.read_bytes(), original)

    def test_target_hardlink_refused(self):
        os.link(self.root / 'var/data/melora.db', self.root / 'outside.db')
        self.hook(self.confirm, expected=1)
        self.assert_preserved()

    def test_internal_directory_symlink_refused(self):
        p = self.root / 'var/data/lx-sources'
        moved = self.root / 'moved-sources'
        p.rename(moved)
        p.symlink_to(moved, target_is_directory=True)
        self.hook(self.confirm, expected=1)
        self.assert_preserved()

    def search_only_ancestor_layout(self):
        """v15：仅移动本测试私有目录，模拟可搜索不可列举的卷/应用祖先。"""
        self.assertNotEqual(os.geteuid(), 0)
        volume = self.root / 'volX'
        volume.mkdir(mode=0o700)
        ancestors = [volume]
        for variable, original, bucket in (('TRIM_APPDEST', 'target', '@appcenter'),
                                           ('TRIM_PKGETC', 'etc', '@appconf'),
                                           ('TRIM_PKGVAR', 'var', '@appdata')):
            ancestor = volume / bucket
            ancestor.mkdir(mode=0o700)
            package = ancestor / 'melora'
            (self.root / original).rename(package)
            package.chmod(0o700)
            # 保留原有断言路径，正式 helper/hook 的 TRIM_* 则使用真实物理路径。
            (self.root / original).symlink_to(package, target_is_directory=True)
            self.env[variable] = str(package)
            self.confirm[variable] = str(package)
            ancestors.append(ancestor)
        self.exe = Path(self.env['TRIM_APPDEST']) / 'bin/melora'
        for path in ancestors:
            path.chmod(0o111)
        return ancestors

    def restore_search_ancestor_fixture(self, ancestors):
        # 只在断言结束后恢复测试 fixture，生产代码不得 chmod 这些祖先。
        for path in ancestors:
            path.chmod(0o700)

    def assert_search_only_layout(self, ancestors):
        for path in ancestors:
            self.assertEqual(path.stat().st_mode & 0o777, 0o111)
            with self.assertRaises(PermissionError):
                list(path.iterdir())
        for variable in ('TRIM_APPDEST', 'TRIM_PKGETC', 'TRIM_PKGVAR'):
            self.assertEqual(Path(self.env[variable]).stat().st_mode & 0o777, 0o700)

    def test_search_only_ancestors_allow_formal_helper_preflight(self):
        ancestors = self.search_only_ancestor_layout()
        try:
            self.assert_search_only_layout(ancestors)
            # 标准 open/read 可沿已知名称访问；不是修改权限后才让 helper 成功。
            self.assert_preserved()
            self.helper(command='--uninstall-preflight')
            self.assert_preserved()
            self.assert_search_only_layout(ancestors)
        finally:
            self.restore_search_ancestor_fixture(ancestors)

    def test_search_only_ancestors_allow_actual_uninstall_hook(self):
        ancestors = self.search_only_ancestor_layout()
        try:
            self.assert_search_only_layout(ancestors)
            self.assert_preserved()
            self.hook(self.confirm)
            self.assert_cleaned()
            self.assert_search_only_layout(ancestors)
        finally:
            self.restore_search_ancestor_fixture(ancestors)

    def test_search_only_layout_still_refuses_unsearchable_ancestor(self):
        ancestors = self.search_only_ancestor_layout()
        inaccessible = self.root / 'volX/@appconf'
        try:
            inaccessible.chmod(0o000)
            with self.assertRaises(PermissionError):
                (Path(self.env['TRIM_PKGETC']) / 'melora.env').read_bytes()
            self.helper(command='--uninstall-preflight', expected=1)
            self.hook(self.confirm, expected=1)
            self.assertEqual(inaccessible.stat().st_mode & 0o777, 0o000)
            # 断言生产未修改权限后，测试自身恢复 +x 才检查所有文件均保留。
            inaccessible.chmod(0o111)
            self.assert_preserved()
            self.assert_search_only_layout(ancestors)
        finally:
            self.restore_search_ancestor_fixture(ancestors)

    def test_search_only_layout_still_refuses_private_symlink(self):
        ancestors = self.search_only_ancestor_layout()
        try:
            data = Path(self.env['TRIM_PKGVAR']) / 'data'
            moved = data.with_name('data-preserved')
            data.rename(moved)
            data.symlink_to(moved, target_is_directory=True)
            self.helper(command='--uninstall-preflight', expected=1)
            self.hook(self.confirm, expected=1)
            self.assertTrue(data.is_symlink())
            self.assert_preserved()
            self.assert_search_only_layout(ancestors)
        finally:
            self.restore_search_ancestor_fixture(ancestors)

    def test_platform_root_aliases_allowed(self):
        env = self.confirm.copy()
        for var, folder in (('TRIM_APPDEST', 'target'), ('TRIM_PKGETC', 'etc'), ('TRIM_PKGVAR', 'var')):
            alias = self.root / ('alias-' + folder)
            alias.symlink_to(self.root / folder, target_is_directory=True)
            env[var] = str(alias)
        self.hook(env)
        self.assert_cleaned()


class IsolatedUninstallSuite(unittest.TestCase):
    """兼容 README 的 unittest discover：整个生命周期套件在同一个隔离 namespace 中运行。"""
    def runTest(self):
        self.assertIsNotNone(shutil.which('unshare'), '需要 unshare，不能跳过停服验证')
        result = subprocess.run(['unshare', '--user', '--map-current-user', '--pid', '--fork', '--mount-proc',
                                 sys.executable, __file__, 'UninstallTests', '-v'],
                                env=dict(os.environ, MELORA_UNINSTALL_TEST_NAMESPACE='1'),
                                capture_output=True, text=True, timeout=240)
        # 输出子套件逐例证据；外围一个 TestCase 不冒充只运行了一例集成验证。
        sys.stderr.write(result.stderr)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


def load_tests(loader, tests, pattern):
    suite = loader.loadTestsFromTestCase(ResourceWizardTests)
    suite.addTests(loader.loadTestsFromTestCase(LockSafetyTests))
    if (sys.platform != 'linux' or os.geteuid() == 0 or
            os.environ.get('MELORA_UNINSTALL_TEST_NAMESPACE') == '1'):
        suite.addTests(loader.loadTestsFromTestCase(UninstallTests))
    else:
        suite.addTest(IsolatedUninstallSuite())
    return suite


if __name__ == '__main__':
    # 宿主桌面用户可能有不可 ptrace 的同 UID 程序；生产必须拒绝这种不确定状态。
    # 测试隔离 PID/user namespace（保持当前非 root UID），不降低 /proc 校验或接触宿主服务。
    if sys.platform == 'linux' and os.geteuid() != 0 and os.environ.get('MELORA_UNINSTALL_TEST_NAMESPACE') != '1':
        if not shutil.which('unshare'):
            raise SystemExit('卸载集成测试需要 unshare PID namespace；不能跳过真实停服检查。')
        result = subprocess.run(['unshare', '--user', '--map-current-user', '--pid', '--fork', '--mount-proc',
                                 sys.executable, __file__, *sys.argv[1:]],
                                env=dict(os.environ, MELORA_UNINSTALL_TEST_NAMESPACE='1'))
        raise SystemExit(result.returncode)
    unittest.main()
