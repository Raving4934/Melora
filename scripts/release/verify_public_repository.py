#!/usr/bin/env python3
"""Fail closed when private material enters the public Git tree.

The public repository contains the Android project, so Android/Kotlin/Java/
Gradle files and workflows are valid public inputs.  This guard only blocks
private implementation markers, credentials, signing material, and generated
runtime files.  Test fixtures and the reviewed ``musicSdk`` metadata/source
surface are intentionally not treated as private direct-audio implementations.
"""

from __future__ import annotations

from pathlib import Path, PurePosixPath
import re
import subprocess


CREDENTIAL_PATTERNS = (
    re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}"),
    re.compile(rb"github_pat_[A-Za-z0-9_]{40,}"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(rb"\bxox[baprs]-[0-9A-Za-z-]{20,}\b"),
)

PRIVATE_AUDIO_PROTOCOL_PATTERNS = (
    re.compile(rb"nmobi[.]kuwo[.]cn/mobi[.]s", re.IGNORECASE),
    re.compile(rb"convert_url_with_sign", re.IGNORECASE),
    re.compile(rb"p2p_audiosourceid", re.IGNORECASE),
    re.compile(rb"kwplayerhd_ar_4[.]3[.]0[.]8_tianbao_T1A_qirui[.]apk", re.IGNORECASE),
    re.compile(rb"kw-mobi:", re.IGNORECASE),
)

# Keep the generic insecure-HTTP check narrow enough to avoid flagging local
# test servers, license metadata, and the reviewed musicSdk source surface.
HTTP_URL = re.compile(
    rb"\bhttp" + rb"""://[A-Za-z0-9][A-Za-z0-9._:-]*(?:/|\?|#)[^\s"'<>\\]*""",
    re.IGNORECASE,
)
# XML parser feature/property names are identifiers, not fetched endpoints.
# Match whole identifiers only: never exempt a parser file or an entire domain.
XML_CONFIGURATION_URIS = {
    b"http://apache.org/xml/features/disallow-doctype-decl",
    b"http://xml.org/sax/features/external-general-entities",
    b"http://xml.org/sax/features/external-parameter-entities",
    b"http://apache.org/xml/features/nonvalidating/load-external-dtd",
    b"http://javax.xml.XMLConstants/property/accessExternalDTD",
    b"http://javax.xml.XMLConstants/property/accessExternalSchema",
}

SOURCE_SUFFIXES = {".go", ".java", ".js", ".jsx", ".kt", ".kts", ".mjs", ".py", ".sh", ".ts", ".tsx"}
PRIVATE_BINARY_SUFFIXES = {".aab", ".apk", ".der", ".jks", ".key", ".keystore", ".p12", ".pem", ".pfx"}
RUNTIME_PATH_PARTS = {
    ".gradle",
    ".idea",
    ".superpowers",
    ".worktrees",
    "__pycache__",
    "build",
    "cache",
    "coverage",
    "data",
    "dist",
    "logs",
    "out",
    "playwright-report",
    "runtime",
    "test-results",
    "temp",
    "tmp",
}
FORBIDDEN_FILENAMES = {
    ".env",
    ".env.local",
    ".env.production",
    "credentials.json",
    "id_ed25519",
    "id_rsa",
    "local.properties",
    "mapping.txt",
    "secrets.json",
    "service-account.json",
}


def _is_test_path(path: PurePosixPath) -> bool:
    parts = {part.lower() for part in path.parts}
    name = path.name.lower()
    return bool(
        parts & {"androidtest", "test", "tests", "__tests__"}
        or name.startswith("test_")
        or name.endswith(("_test.go", "_test.py", "_test.kt", "_test.java"))
        or ".test." in name
        or ".spec." in name
    )


def _is_music_sdk_path(path: PurePosixPath) -> bool:
    parts = {part.lower() for part in path.parts}
    return "musicsdk" in parts or path.name.lower() == "music-sdk.js"


def _is_source_path(path: PurePosixPath) -> bool:
    return path.suffix.lower() in SOURCE_SUFFIXES and not _is_test_path(path)


def _is_android_app_production_source(path: PurePosixPath) -> bool:
    parts = tuple(part.lower() for part in path.parts)
    return (
        len(parts) >= 5
        and parts[:4] == ("android", "app", "src", "main")
        and path.suffix.lower() in SOURCE_SUFFIXES
        and not _is_test_path(path)
        and not _is_music_sdk_path(path)
    )


def _is_boundary_checker(path: PurePosixPath) -> bool:
    return path.parts[-2:] == ("release", "verify_public_repository.py")


def _has_private_audio_protocol(content: bytes) -> bool:
    return any(pattern.search(content) for pattern in PRIVATE_AUDIO_PROTOCOL_PATTERNS)


def violations(name: str, content: bytes) -> list[str]:
    path = PurePosixPath(name.replace("\\", "/"))
    issues: list[str] = []
    lower_name = path.name.lower()
    lower_parts = {part.lower() for part in path.parts}

    if path.name == "酷我直连音源.js":
        issues.append("private source script")
    if path.name == "KwMediaApi.kt":
        issues.append("private audio source implementation")
    if path.suffix.lower() in PRIVATE_BINARY_SUFFIXES or lower_name in FORBIDDEN_FILENAMES:
        issues.append("private signing/build/runtime material")
    if lower_parts & RUNTIME_PATH_PARTS or lower_name.endswith(
        (".db", ".db-shm", ".db-wal", ".log", ".part", ".pyc", ".sqlite", ".sqlite3", ".tmp")
    ):
        issues.append("internal runtime or temporary data")

    if any(pattern.search(content) for pattern in CREDENTIAL_PATTERNS):
        issues.append("credential-like material")

    # A test may contain a mocked protocol response, but the real implementation
    # and its unique source file must never enter the public production tree.
    if (
        _is_source_path(path)
        and not _is_boundary_checker(path)
        and _has_private_audio_protocol(content)
    ):
        issues.append("private audio protocol material")

    # Keep the public Android production surface HTTPS-only for literal external
    # endpoints.  Test servers, metadata, and the reviewed musicSdk surface are
    # intentionally outside this rule.
    if _is_android_app_production_source(path):
        if any(match.group() not in XML_CONFIGURATION_URIS for match in HTTP_URL.finditer(content)):
            issues.append("insecure HTTP endpoint in production source")

    return list(dict.fromkeys(issues))


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    bad = []
    for name in filter(None, names):
        # Scan the staged version: an unstaged safe edit must not hide an unsafe staged blob.
        data = subprocess.check_output(["git", "show", ":" + name], cwd=root)
        bad.extend(f"{name}: {reason}" for reason in violations(name, data))
    if bad:
        print("Public repository boundary failed (values omitted):\n" + "\n".join(bad))
        return 1
    print(f"Public repository boundary PASS ({sum(bool(n) for n in names)} tracked files).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
