#!/usr/bin/env python3
"""Fail closed when private Android source or credentials enter the public Git tree."""
from pathlib import Path, PurePosixPath
import re
import subprocess

TOKEN = re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")
PRIVATE_SUFFIXES = {'.kt', '.kts', '.java', '.jks', '.keystore', '.p12', '.apk', '.aab'}


def violations(name: str, content: bytes) -> list[str]:
    path = PurePosixPath(name)
    issues = []
    if (path.parts[0] == 'android' or path.suffix in PRIVATE_SUFFIXES
            or path.name in {'local.properties', 'mapping.txt', 'gradlew', 'gradlew.bat'}
            or name.startswith('.github/workflows/android-')):
        issues.append('private Android source/build/signing material')
    if TOKEN.search(content):
        issues.append('credential-like material')
    return issues


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    bad = []
    for name in filter(None, names):
        # Scan the staged version: an unstaged safe edit must not hide an unsafe staged blob.
        data = subprocess.check_output(['git', 'show', ':' + name], cwd=root)
        bad.extend(f'{name}: {reason}' for reason in violations(name, data))
    if bad:
        print('Public repository boundary failed (values omitted):\n' + '\n'.join(bad))
        return 1
    print(f'Public repository boundary PASS ({sum(bool(n) for n in names)} tracked files).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
