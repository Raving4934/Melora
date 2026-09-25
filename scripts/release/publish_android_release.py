#!/usr/bin/env python3
"""Publish a signed Android release safely and idempotently.

The publisher reconciles an existing ``android-vX.Y.Z`` tag in the same
repository that produced it.  It never creates or moves tags: the push-tag and
workflow-dispatch paths both pass an existing tag, which makes rebuilding
``android-v0.1.0`` idempotent.  It uploads only the APK and checksum, verifies
the remote bytes, and publishes the draft.  Existing assets are never
overwritten: a same-name asset with different bytes is an error, not a clobber
opportunity.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

try:
    from github_api import GitHubApi, GitHubApiError, GitHubNotFound
except ImportError:  # pragma: no cover - permits direct module execution from another cwd
    from .github_api import GitHubApi, GitHubApiError, GitHubNotFound


TAG_RE = re.compile(r"^android-v([0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)$")
HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")


class ReleaseError(RuntimeError):
    """A safe release validation or reconciliation error."""


@dataclass(frozen=True)
class AssetFile:
    name: str
    path: Path
    content: bytes
    sha256: str
    content_type: str


@dataclass(frozen=True)
class ReleasePlan:
    repository: str
    tag: str
    version: str
    expected_commit: str
    apk: AssetFile
    checksum: AssetFile
    notes: str

    @property
    def prerelease(self) -> bool:
        return "-" in self.version.split("+", 1)[0]

    @property
    def assets(self) -> tuple[AssetFile, ...]:
        return (self.apk, self.checksum)


class ReleasePublisher:
    def __init__(self, api: Any, plan: ReleasePlan) -> None:
        self.api = api
        self.plan = plan
        self.repo_path = f"/repos/{plan.repository}"

    def run(self) -> dict[str, Any]:
        self._validate_repository()
        target_sha = self._prepare_tag()
        release = self._get_or_create_draft(target_sha)
        self._validate_release_metadata(release)
        self._reconcile_assets(release)
        release = self.api.get(f"{self.repo_path}/releases/{release['id']}")
        self._validate_release_metadata(release)
        self._validate_remote_assets(release)
        self._prepare_tag()  # Recheck after uploads, before making anything public.
        if release.get("draft") is True:
            # 只在首次公开时提升正式版；重跑旧版本不会抢回 Latest。
            release = self.api.patch(
                f"{self.repo_path}/releases/{release['id']}",
                {
                    "draft": False,
                    "prerelease": self.plan.prerelease,
                    "make_latest": "false" if self.plan.prerelease else "true",
                },
            )
        self._validate_release_metadata(release)
        self._validate_remote_assets(release)
        return release

    def _validate_repository(self) -> None:
        try:
            repository = self.api.get(self.repo_path)
        except GitHubApiError as error:
            raise ReleaseError("unable to inspect the target GitHub repository") from error
        if repository.get("private") is not False:
            raise ReleaseError("the build repository is not public; refusing to publish Android assets")

    def _prepare_tag(self) -> str:
        existing = self._read_tag_sha()
        if existing is None:
            raise ReleaseError("the Android tag does not exist; refusing to create a release")
        if existing != self.plan.expected_commit:
            raise ReleaseError("the Android tag no longer points to the locally built commit")
        return existing

    def _read_tag_sha(self) -> str | None:
        try:
            ref = self.api.get(f"{self.repo_path}/git/ref/tags/{self.plan.tag}")
        except GitHubNotFound:
            return None
        except GitHubApiError as error:
            raise ReleaseError("unable to inspect the Android tag") from error
        try:
            return _ref_commit_sha(self.api, self.repo_path, ref)
        except ReleaseError:
            raise
        except GitHubApiError as error:
            raise ReleaseError("unable to resolve the Android tag target") from error

    def _get_or_create_draft(self, target_sha: str) -> dict[str, Any]:
        try:
            return self.api.get(f"{self.repo_path}/releases/tags/{self.plan.tag}")
        except GitHubNotFound:
            payload: dict[str, Any] = {
                "tag_name": self.plan.tag,
                "name": f"Melora Android v{self.plan.version}",
                "body": self.plan.notes,
                "draft": True,
                "prerelease": self.plan.prerelease,
                "make_latest": "false",
                "target_commitish": target_sha,
            }
            try:
                return self.api.post(f"{self.repo_path}/releases", payload)
            except GitHubApiError as error:
                raise ReleaseError("unable to create the draft Android release") from error
        except GitHubApiError as error:
            raise ReleaseError("unable to inspect the existing Android release") from error

    def _validate_release_metadata(self, release: dict[str, Any]) -> None:
        if release.get("tag_name") != self.plan.tag:
            raise ReleaseError("the GitHub release tag does not match the requested Android tag")
        if release.get("prerelease") is not self.plan.prerelease:
            raise ReleaseError("the existing GitHub release has a different prerelease channel")
        if not isinstance(release.get("draft"), bool):
            raise ReleaseError("the GitHub release is missing its draft state")
        if release.get("body", "") != self.plan.notes:
            raise ReleaseError("the existing GitHub release notes differ; refusing to overwrite approved notes")
        if not release.get("id") or not release.get("upload_url"):
            raise ReleaseError("the GitHub release response is missing its id or upload URL")
        assets = release.get("assets")
        if not isinstance(assets, list):
            raise ReleaseError("the GitHub release response has an invalid asset list")
        expected = {asset.name for asset in self.plan.assets}
        actual = [asset.get("name") for asset in assets]
        if any(not isinstance(name, str) for name in actual):
            raise ReleaseError("the GitHub release contains an unnamed asset")
        unexpected = sorted(set(actual) - expected)
        if unexpected:
            raise ReleaseError(f"the GitHub release contains unapproved assets: {', '.join(unexpected)}")
        if len(actual) != len(set(actual)):
            raise ReleaseError("the GitHub release contains duplicate asset names")

    def _reconcile_assets(self, release: dict[str, Any]) -> None:
        existing = {asset["name"]: asset for asset in release["assets"]}
        # Validate every existing asset before changing the draft.  This makes a
        # rerun fail atomically when any same-name remote asset is different.
        self._validate_remote_assets(release, allow_missing=True)
        missing = [asset for asset in self.plan.assets if asset.name not in existing]
        if missing and release.get("draft") is not True:
            names = ", ".join(asset.name for asset in missing)
            raise ReleaseError(f"the published GitHub release is missing assets: {names}; refusing to modify it")
        for asset in missing:
            try:
                self.api.upload(
                    release["upload_url"],
                    name=asset.name,
                    content=asset.content,
                    content_type=asset.content_type,
                )
            except GitHubApiError as error:
                raise ReleaseError(f"unable to upload the draft release asset {asset.name}") from error

    def _validate_remote_assets(self, release: dict[str, Any], *, allow_missing: bool = False) -> None:
        remote = {asset["name"]: asset for asset in release.get("assets", [])}
        for expected in self.plan.assets:
            actual = remote.get(expected.name)
            if actual is None:
                if allow_missing:
                    continue
                raise ReleaseError(f"the GitHub release is missing asset {expected.name}")
            self._validate_remote_asset(expected, actual)

    def _validate_remote_asset(self, expected: AssetFile, actual: dict[str, Any]) -> None:
        if actual.get("state") not in (None, "uploaded"):
            raise ReleaseError(f"GitHub asset {expected.name} is not in the uploaded state")
        if actual.get("size") is not None and int(actual["size"]) != len(expected.content):
            raise ReleaseError(f"remote asset {expected.name} has a different size; refusing to clobber it")
        try:
            remote_content = self.api.get_bytes(f"{self.repo_path}/releases/assets/{actual['id']}")
        except GitHubApiError as error:
            raise ReleaseError(f"unable to verify remote asset {expected.name}") from error
        if expected.name == self.plan.checksum.name:
            _validate_checksum_file(remote_content, self.plan.apk.name, self.plan.apk.sha256)
        elif hashlib.sha256(remote_content).hexdigest() != expected.sha256:
            raise ReleaseError(f"remote asset {expected.name} differs from the locally verified asset")


def _ref_commit_sha(api: Any, repo_path: str, ref: dict[str, Any]) -> str:
    obj = ref.get("object")
    if not isinstance(obj, dict) or not obj.get("sha") or not obj.get("type"):
        raise ReleaseError("GitHub returned an invalid Git ref")
    if obj["type"] == "commit":
        return str(obj["sha"])
    if obj["type"] != "tag":
        raise ReleaseError("Android tag must resolve to a commit or annotated tag")
    try:
        tag = api.get(f"{repo_path}/git/tags/{obj['sha']}")
    except GitHubApiError as error:
        raise ReleaseError("unable to resolve annotated Android tag") from error
    target = tag.get("object")
    if not isinstance(target, dict) or target.get("type") != "commit" or not target.get("sha"):
        raise ReleaseError("annotated Android tag does not point directly to a commit")
    return str(target["sha"])


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _read_asset(path: Path, name: str, content_type: str) -> AssetFile:
    try:
        content = path.read_bytes()
    except OSError as error:
        raise ReleaseError(f"unable to read release asset {name}") from error
    if not content:
        raise ReleaseError(f"release asset {name} is empty")
    return AssetFile(name=name, path=path, content=content, sha256=_sha256(content), content_type=content_type)


def _validate_checksum_file(content: bytes, expected_name: str, expected_digest: str) -> None:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ReleaseError("APK checksum asset is not UTF-8") from error
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) != 1:
        raise ReleaseError("APK checksum asset must contain exactly one non-empty line")
    fields = lines[0].split()
    if len(fields) != 2 or not HEX64_RE.fullmatch(fields[0]) or fields[0].lower() != expected_digest.lower():
        raise ReleaseError("APK checksum asset does not match the verified APK")
    if fields[1].lstrip("*") != expected_name:
        raise ReleaseError("APK checksum asset names a different APK")


def build_plan(args: argparse.Namespace) -> ReleasePlan:
    match = TAG_RE.fullmatch(args.tag)
    if not match:
        raise ReleaseError("Android tag must match android-v<semver>")
    version = match.group(1)
    if version != args.version:
        raise ReleaseError("Android tag version and requested version differ")
    if not re.fullmatch(r"[0-9a-f]{40}", args.expected_commit):
        raise ReleaseError("expected commit must be the full SHA of the local build checkout")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ReleaseError("repository must be owner/name")
    apk_name = f"melora-android-v{version}-arm64-v8a.apk"
    checksum_name = f"{apk_name}.sha256"
    apk = _read_asset(Path(args.apk), apk_name, "application/vnd.android.package-archive")
    checksum = _read_asset(Path(args.checksum), checksum_name, "text/plain; charset=utf-8")
    _validate_checksum_file(checksum.content, apk.name, apk.sha256)
    try:
        notes = Path(args.notes).read_text(encoding="utf-8")
    except OSError as error:
        raise ReleaseError("unable to read approved Android release notes") from error
    if not notes.strip():
        raise ReleaseError("approved Android release notes are empty")

    return ReleasePlan(
        repository=args.repository,
        tag=args.tag,
        version=version,
        expected_commit=args.expected_commit,
        apk=apk,
        checksum=checksum,
        notes=notes,
    )


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="owner/name")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--checksum", required=True)
    parser.add_argument("--notes", required=True)
    parser.add_argument("--token-env", default="GITHUB_TOKEN")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        plan = build_plan(args)
        if os.environ.get("GITHUB_REPOSITORY") != plan.repository:
            raise ReleaseError("target repository must equal the current workflow GITHUB_REPOSITORY")
        token = os.environ.get(args.token_env, "")
        api = GitHubApi(token)
        release = ReleasePublisher(api, plan).run()
    except (ReleaseError, GitHubApiError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    print(
        f"Verified Android release {plan.tag} in {plan.repository}: "
        f"draft={str(bool(release.get('draft'))).lower()}, assets={len(plan.assets)}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
