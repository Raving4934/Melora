#!/usr/bin/env python3
"""校验现有构建输入并创建确定性的 FPK staging，不编译、不伪造 FPK。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import sys

HOOKS = ('main', 'install_init', 'install_callback', 'upgrade_init', 'upgrade_callback',
         'uninstall_init', 'uninstall_callback', 'config_init', 'config_callback')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_elf(binary, arch):
    require(binary.is_file() and not binary.is_symlink(), '缺少对应架构二进制，或输入是符号链接。')
    with binary.open('rb') as handle:
        head = handle.read(64)
        require(len(head) == 64 and head[:6] == b'\x7fELF\x02\x01', '需要 64 位小端 Linux ELF。')
        require(struct.unpack_from('<H', head, 16)[0] in (2, 3), 'ELF 不是可执行文件。')
        require(struct.unpack_from('<H', head, 18)[0] == {'amd64': 62, 'arm64': 183}[arch],
                '二进制实际架构与 --arch 不匹配。')
        offset = struct.unpack_from('<Q', head, 32)[0]
        size, count = struct.unpack_from('<HH', head, 54)
        require(size >= 56 and 0 < count < 65535, 'ELF 程序头无效。')
        require(offset + size * count <= binary.stat().st_size, 'ELF 程序头越界。')
        for i in range(count):
            handle.seek(offset + i * size)
            kind = struct.unpack('<I', handle.read(4))[0]
            require(kind not in (2, 3), '不接受动态链接二进制；请以 CGO_ENABLED=0 构建静态 Go 服务。')


def validate_tree(root, web=False):
    require(root.is_dir() and not root.is_symlink(), '构建目录不存在或为符号链接。')
    for path in root.rglob('*'):
        require(not path.is_symlink(), '输入树包含符号链接，拒绝打包。')
        require(path.is_file() or path.is_dir(), '输入树包含特殊文件，拒绝打包。')
        if web:
            require(not path.name.startswith('.') and path.name != 'node_modules',
                    '前端含隐藏文件或 node_modules；请先清理产物。')
            require(path.suffix.lower() not in {'.map', '.pem', '.key', '.db', '.sqlite', '.sqlite3', '.log', '.fpk'},
                    '前端含源映射、密钥、数据库、日志或 FPK；拒绝打包。')


def stage(repo, dest, arch, epoch):
    template = repo / 'packaging/fpk'
    binary = repo / f'dist/melora-linux-{arch}'
    web = repo / 'apps/web/dist'
    project = json.loads((repo / 'package.json').read_text())
    version = project.get('version')
    require(isinstance(version, str) and re.fullmatch(r'\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?', version),
            'package.json version 不是有效的语义化版本。')
    web_project = json.loads((repo / 'apps/web/package.json').read_text())
    require(web_project.get('version') == version, '前端 workspace 版本必须与仓库根 package.json 一致。')
    validate_elf(binary, arch)
    validate_tree(template)
    validate_tree(web, web=True)
    require((web / 'index.html').is_file() and (web / 'index.html').stat().st_size > 0, '前端 index.html 缺失或为空。')
    require(not dest.exists(), 'staging 目标已存在；只允许在新的临时目录中构造。')
    dest.mkdir(parents=True)
    for name in ('app', 'config', 'cmd'):
        shutil.copytree(template / name, dest / name)
    (dest / 'wizard').mkdir()
    # 向导是官方支持的 JSON 文件，不再只创建空目录；不打包 .gitkeep 或其它临时材料。
    for name in ('install', 'upgrade', 'uninstall', 'config'):
        source = template / 'wizard' / name
        if source.is_file():
            steps = json.loads(source.read_text())
            require(isinstance(steps, list) and bool(steps), '向导必须是非空 JSON 步骤数组。')
            for step in steps:
                require(isinstance(step, dict) and isinstance(step.get('items'), list)
                        and isinstance(step.get('stepTitle'), str), '向导步骤缺少标题或表单项。')
                for item in step['items']:
                    require(isinstance(item, dict) and not str(item.get('field', '')).startswith('TRIM_'),
                            '向导不得伪造系统 TRIM_ 环境变量。')
            shutil.copyfile(source, dest / 'wizard' / name)
    for name in ('ICON.PNG', 'ICON_256.PNG'):
        shutil.copyfile(template / name, dest / name)
    manifest = (template / 'manifest.in').read_text()
    require(manifest.count('@PLATFORM@') == 1, 'manifest 平台占位符异常。')
    require(manifest.count('@VERSION@') == 1, 'manifest 版本占位符异常。')
    rendered_manifest = manifest.replace('@PLATFORM@', {'amd64': 'x86', 'arm64': 'arm'}[arch])
    (dest / 'manifest').write_text(rendered_manifest.replace('@VERSION@', version))
    (dest / 'app/bin').mkdir()
    shutil.copyfile(binary, dest / 'app/bin/melora')
    shutil.copytree(web, dest / 'app/web')
    for name in ('config/privilege', 'config/resource', 'app/ui/config'):
        json.loads((dest / name).read_text())
    fields = dict(line.split('=', 1) for line in (dest / 'manifest').read_text().splitlines() if line)
    require(fields.get('appname') == 'melora' and fields.get('version') == version,
            '应用 ID/版本必须与仓库根 package.json 一致。')
    require(not {'service_port', 'checkport', 'arch'} & fields.keys(), '默认网关包不得声明旧独立端口或 arch。')
    require(fields.get('desktop_applaunchname') == 'melora.main' and fields.get('desktop_uidir') == 'ui',
            'Manifest 桌面入口与网关配置不一致。')
    entry = json.loads((dest / 'app/ui/config').read_text()).get('.url', {}).get('melora.main', {})
    expected = {'type': 'iframe', 'protocol': '', 'gatewayPrefix': '/app/melora',
                'gatewaySocket': 'app.sock', 'url': '/app/melora', 'allUsers': False}
    require(all(entry.get(key) == value for key, value in expected.items()) and 'port' not in entry,
            '桌面入口必须与生命周期的默认管理员网关模式一致。')
    require(json.loads((dest / 'config/privilege').read_text()).get('defaults', {}).get('run-as') == 'package',
            '服务必须使用 package 用户。')
    for name, size in [('ICON.PNG', 64), ('ICON_256.PNG', 256),
                       ('app/ui/images/icon_64.png', 64), ('app/ui/images/icon_256.png', 256)]:
        data = (dest / name).read_bytes()
        require(data[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('!II', data[16:24]) == (size, size),
                '图标必须为官方指定尺寸的 PNG。')
        require(len(data) <= 1024 * 1024, '图标超过官方大小限制。')
    executable = {'app/bin/melora', 'cmd/lib.sh'} | {f'cmd/{name}' for name in HOOKS}
    inventory = []
    for path in sorted(dest.rglob('*')):
        rel = path.relative_to(dest).as_posix()
        mode = 0o755 if path.is_dir() or rel in executable else 0o644
        path.chmod(mode)
        if path.is_file():
            inventory.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {mode:o}  {rel}\n')
    for path in sorted(dest.rglob('*'), reverse=True) + [dest]:
        os.utime(path, (epoch, epoch), follow_symlinks=False)
    dest.chmod(0o755)
    (dest.parent / 'staging.sha256').write_text(''.join(inventory))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--dest', type=Path, required=True)
    parser.add_argument('--arch', choices=('amd64', 'arm64'), required=True)
    parser.add_argument('--epoch', type=int, default=0)
    args = parser.parse_args()
    try:
        require(0 <= args.epoch <= 253402300799, 'SOURCE_DATE_EPOCH 超出可用范围。')
        stage(args.repo, args.dest, args.arch, args.epoch)
    except (OSError, ValueError, struct.error) as exc:
        print(f'Melora staging 失败：{exc}', file=sys.stderr)
        sys.exit(1)
