#!/usr/bin/env python3
"""Offline boundary tests for same-repository Android release reconciliation."""

from __future__ import annotations

import hashlib
from contextlib import redirect_stderr, redirect_stdout
import io
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import patch
import tempfile
import unittest
from pathlib import Path
from typing import Any

from github_api import GitHubApiError
from publish_android_release import ReleaseError, ReleasePlan, ReleasePublisher, build_plan, main


class FakeApi:
    def __init__(
        self,
        *,
        public: bool = True,
        tag_sha: str | None = "a" * 40,
        release: dict[str, Any] | None = None,
        post_error: GitHubApiError | None = None,
    ) -> None:
        self.repository = {"private": not public}
        self.tag_sha = tag_sha
        self.release = release
        self.post_error = post_error
        self.uploaded: list[str] = []
        self.deleted: list[str] = []
        self.remote_bytes: dict[int, bytes] = {}
        self.next_asset_id = 10
        self.patches: list[dict[str, Any]] = []
        self.posts: list[dict[str, Any]] = []

    def get(self, path: str) -> Any:
        if "/git/ref/tags/android-v" in path:
            if self.tag_sha is None:
                from github_api import GitHubNotFound

                raise GitHubNotFound("missing")
            return {"object": {"type": "commit", "sha": self.tag_sha}}
        if path == "/repos/owner/Melora":
            return self.repository
        if "/releases/" in path and path.rsplit("/", 1)[-1].isdigit():
            return self.release
        raise AssertionError(f"unexpected GET {path}")

    def get_paginated(self, path: str) -> list[dict[str, Any]]:
        if path == "/repos/owner/Melora/releases?per_page=100":
            return [self.release] if self.release is not None else []
        raise AssertionError(f"unexpected LIST {path}")

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        if path.endswith("/releases"):
            if payload.get("make_latest") != "false":
                raise AssertionError("GitHub make_latest must use the string enum false")
            self.posts.append(payload)
            if self.post_error is not None:
                raise self.post_error
            self.release = {
                "id": 1,
                "tag_name": payload["tag_name"],
                "body": payload["body"],
                "draft": True,
                "prerelease": payload["prerelease"],
                "upload_url": "https://uploads.example/releases/1/assets{?name,label}",
                "assets": [],
            }
            return self.release
        raise AssertionError(f"unexpected POST {path}")

    def patch(self, path: str, payload: dict[str, Any]) -> Any:
        if "make_latest" in payload:
            if payload["make_latest"] not in ("true", "false", "legacy"):
                raise AssertionError("GitHub make_latest must use a string enum")
            if payload["make_latest"] == "true" and (payload.get("draft") or payload.get("prerelease")):
                raise AssertionError("Drafts and prereleases cannot become latest")
        self.patches.append(payload)
        self.release.update(payload)
        return self.release

    def upload(self, upload_url: str, *, name: str, content: bytes, content_type: str) -> Any:
        asset = {"id": self.next_asset_id, "name": name, "size": len(content), "state": "uploaded"}
        self.next_asset_id += 1
        self.release["assets"].append(asset)
        self.remote_bytes[asset["id"]] = content
        self.uploaded.append(name)
        return asset

    def get_bytes(self, path: str) -> bytes:
        return self.remote_bytes[int(path.rsplit("/", 1)[-1])]


class PublishAndroidReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.apk_path = root / "input.apk"
        self.apk_path.write_bytes(b"signed-release-apk")
        self.apk_digest = hashlib.sha256(self.apk_path.read_bytes()).hexdigest()
        self.checksum_path = root / "input.sha256"
        self.checksum_path.write_text(f"{self.apk_digest}  melora-android-v0.1.0-arm64-v8a.apk\n", encoding="utf-8")
        self.notes_path = root / "notes.md"
        self.notes_path.write_text("# approved Android notes\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def args(self) -> SimpleNamespace:
        return SimpleNamespace(
            tag="android-v0.1.0", version="0.1.0", expected_commit="a" * 40,
            apk=str(self.apk_path), checksum=str(self.checksum_path), notes=str(self.notes_path),
            repository="owner/Melora",
        )

    def plan(self) -> ReleasePlan:
        return build_plan(self.args())

    def test_same_repository_release_uploads_only_apk_and_checksum(self) -> None:
        api = FakeApi()
        release = ReleasePublisher(
            api,
            self.plan(),
        ).run()
        self.assertFalse(release["draft"])
        self.assertEqual(
            api.uploaded,
            ["melora-android-v0.1.0-arm64-v8a.apk", "melora-android-v0.1.0-arm64-v8a.apk.sha256"],
        )

    def test_missing_android_tag_never_creates_a_release(self) -> None:
        api = FakeApi(tag_sha=None)
        with self.assertRaisesRegex(ReleaseError, "tag does not exist"):
            ReleasePublisher(api, self.plan()).run()
        self.assertIsNone(api.release)

    def test_same_repository_publish_requires_a_public_repository(self) -> None:
        api = FakeApi(public=False)
        with self.assertRaisesRegex(ReleaseError, "not public"):
            ReleasePublisher(api, self.plan()).run()
        self.assertIsNone(api.release)

    def test_existing_different_asset_is_never_overwritten(self) -> None:
        api = FakeApi(
            release={
                "id": 1,
                "tag_name": "android-v0.1.0",
                "body": "# approved Android notes\n",
                "draft": True,
                "prerelease": False,
                "upload_url": "https://uploads.example/releases/1/assets{?name,label}",
                "assets": [{"id": 9, "name": "melora-android-v0.1.0-arm64-v8a.apk", "size": 1, "state": "uploaded"}],
            }
        )
        api.remote_bytes[9] = b"different-apk"
        with self.assertRaisesRegex(ReleaseError, "different"):
            ReleasePublisher(api, self.plan()).run()
        self.assertEqual(api.uploaded, [])
        self.assertEqual(api.patches, [])

    def test_unapproved_public_asset_is_rejected(self) -> None:
        api = FakeApi(
            release={
                "id": 1,
                "tag_name": "android-v0.1.0",
                "body": "# approved Android notes\n",
                "draft": True,
                "prerelease": False,
                "upload_url": "https://uploads.example/releases/1/assets{?name,label}",
                "assets": [{"id": 9, "name": "mapping.txt", "size": 1, "state": "uploaded"}],
            }
        )
        api.remote_bytes[9] = b"x"
        with self.assertRaisesRegex(ReleaseError, "unapproved"):
            ReleasePublisher(api, self.plan()).run()
        self.assertEqual(api.uploaded, [])

    def test_stable_publish_promotes_latest_once_and_rerun_does_not_reclaim_it(self) -> None:
        api = FakeApi()
        plan = self.plan()
        ReleasePublisher(api, plan).run()
        ReleasePublisher(api, plan).run()
        self.assertEqual(len(api.uploaded), 2)
        self.assertEqual(len(api.posts), 1)
        self.assertEqual(api.posts[0]["target_commitish"], plan.expected_commit)
        self.assertEqual(api.posts[0]["make_latest"], "false")
        self.assertEqual(api.patches, [{"draft": False, "prerelease": False, "make_latest": "true"}])

    def test_interrupted_draft_resumes_without_duplicate_release_or_upload(self) -> None:
        api = FakeApi()
        plan = self.plan()
        draft = ReleasePublisher(api, plan)._get_or_create_draft(plan.expected_commit)
        self.assertTrue(draft["draft"])
        api.upload(draft["upload_url"], name=plan.apk.name, content=plan.apk.content,
                   content_type=plan.apk.content_type)
        published = ReleasePublisher(api, plan).run()
        self.assertFalse(published["draft"])
        ReleasePublisher(api, plan).run()
        self.assertEqual(len(api.posts), 1)
        self.assertEqual(api.uploaded, [plan.apk.name, plan.checksum.name])
        self.assertEqual(len(api.patches), 1)

    def test_release_list_failure_is_not_treated_as_missing_release(self) -> None:
        api = FakeApi()
        with patch.object(api, "get_paginated", side_effect=GitHubApiError("HTTP 403", status=403)):
            with self.assertRaisesRegex(ReleaseError, "existing Android releases: HTTP 403"):
                ReleasePublisher(api, self.plan()).run()
        self.assertEqual(api.posts, [])

    def test_duplicate_drafts_fail_but_published_release_takes_precedence(self) -> None:
        api = FakeApi()
        plan = self.plan()
        draft = ReleasePublisher(api, plan)._get_or_create_draft(plan.expected_commit)
        duplicate = {**draft, "id": 2}
        with patch.object(api, "get_paginated", return_value=[draft, duplicate]):
            with self.assertRaisesRegex(ReleaseError, "multiple Android release drafts"):
                ReleasePublisher(api, plan).run()
        self.assertEqual(len(api.posts), 1)
        self.assertEqual(api.uploaded, [])
        ReleasePublisher(api, plan).run()
        with patch.object(api, "get_paginated", return_value=[duplicate, api.release]):
            self.assertFalse(ReleasePublisher(api, plan).run()["draft"])
        self.assertEqual(len(api.posts), 1)
        self.assertEqual(len(api.patches), 1)

    def test_tag_mismatch_fails_before_any_release_mutation(self) -> None:
        api = FakeApi(tag_sha="b" * 40)
        with self.assertRaisesRegex(ReleaseError, "locally built commit"):
            ReleasePublisher(api, self.plan()).run()
        self.assertEqual(api.posts, [])
        self.assertEqual(api.uploaded, [])

    def test_tag_moved_during_upload_leaves_release_unpublished(self) -> None:
        api = FakeApi()
        upload = api.upload
        def move_tag(*args: Any, **kwargs: Any) -> Any:
            result = upload(*args, **kwargs)
            api.tag_sha = "b" * 40
            return result
        api.upload = move_tag
        with self.assertRaisesRegex(ReleaseError, "locally built commit"):
            ReleasePublisher(api, self.plan()).run()
        self.assertTrue(api.release["draft"])
        self.assertEqual(api.patches, [])

    def test_missing_asset_on_published_release_is_not_silently_repaired(self) -> None:
        api = FakeApi()
        ReleasePublisher(api, self.plan()).run()
        api.release["assets"].pop()
        with self.assertRaisesRegex(ReleaseError, "published.*missing assets"):
            ReleasePublisher(api, self.plan()).run()
        self.assertEqual(len(api.uploaded), 2)

    def test_existing_approved_notes_and_channel_are_not_overwritten(self) -> None:
        for key, value, message in (("body", "old notes", "notes differ"),
                                    ("prerelease", True, "prerelease channel")):
            api = FakeApi()
            ReleasePublisher(api, self.plan()).run()
            api.release[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ReleaseError, message):
                ReleasePublisher(api, self.plan()).run()
            self.assertEqual(len(api.patches), 1)

    def test_prerelease_tags_do_not_publish_as_stable(self) -> None:
        api = FakeApi()
        plan = replace(self.plan(), version="0.1.0-rc.1", tag="android-v0.1.0-rc.1")
        ReleasePublisher(api, plan).run()
        self.assertTrue(api.release["prerelease"])
        self.assertEqual(api.patches[0]["make_latest"], "false")

    def test_web_tag_and_wrong_version_or_commit_are_rejected_locally(self) -> None:
        for key, value in (("tag", "v0.1.0"), ("version", "0.2.0"), ("expected_commit", "main")):
            args = self.args()
            setattr(args, key, value)
            with self.subTest(key=key), self.assertRaises(ReleaseError):
                build_plan(args)

    def test_checksum_must_name_the_exact_arm64_asset(self) -> None:
        for name in ("melora-android-v0.1.0.apk", "melora-android-v0.1.0-x86_64.apk",
                     "../melora-android-v0.1.0-arm64-v8a.apk"):
            self.checksum_path.write_text(f"{self.apk_digest}  {name}\n", encoding="utf-8")
            with self.subTest(name=name), self.assertRaisesRegex(ReleaseError, "different APK"):
                self.plan()

    def test_main_reports_safe_github_context_when_draft_creation_fails(self) -> None:
        token = "sensitive-test-token"
        response_body = "sensitive-test-response-body"
        cases = (
            (
                "HTTP 403",
                GitHubApiError(
                    "GitHub API request failed with HTTP 403: POST "
                    "https://api.github.com/repos/owner/Melora/releases",
                    status=403,
                ),
            ),
            (
                "HTTP 422",
                GitHubApiError(
                    "GitHub API request failed with HTTP 422: POST "
                    "https://api.github.com/repos/owner/Melora/releases",
                    status=422,
                ),
            ),
            (
                "HTTP 503",
                GitHubApiError(
                    "GitHub API request failed with HTTP 503: POST "
                    "https://api.github.com/repos/owner/Melora/releases",
                    status=503,
                ),
            ),
            (
                "network error",
                GitHubApiError(
                    "GitHub API request could not be completed: POST "
                    "https://api.github.com/repos/owner/Melora/releases"
                ),
            ),
        )
        args = self.args()
        argv = [item for key, value in vars(args).items()
                for item in ("--" + key.replace("_", "-"), str(value))]

        for label, error in cases:
            with self.subTest(failure=label):
                error.__cause__ = RuntimeError(
                    f"{response_body}; Authorization: Bearer {token}"
                )
                api = FakeApi(post_error=error)
                stdout = io.StringIO()
                stderr = io.StringIO()
                with patch.dict(
                    "os.environ",
                    {"GITHUB_REPOSITORY": "owner/Melora", "GITHUB_TOKEN": token},
                    clear=True,
                ), patch("publish_android_release.GitHubApi", return_value=api) as github_api, \
                        redirect_stdout(stdout), redirect_stderr(stderr):
                    self.assertEqual(main(argv), 1)

                github_api.assert_called_once_with(token)
                output = stdout.getvalue() + stderr.getvalue()
                self.assertEqual(
                    stderr.getvalue(),
                    f"::error::unable to create the draft Android release: {error}\n",
                )
                self.assertEqual(stdout.getvalue(), "")
                self.assertNotIn(token, output)
                self.assertNotIn(response_body, output)
                self.assertNotIn("Traceback", output)
                self.assertEqual(api.posts[0]["draft"], True)

    def test_cli_cannot_publish_to_another_repository(self) -> None:
        args = self.args()
        argv = [item for key, value in vars(args).items()
                for item in ("--" + key.replace("_", "-"), str(value))]
        with patch.dict("os.environ", {"GITHUB_REPOSITORY": "other/Melora"}, clear=True), \
                patch("publish_android_release.GitHubApi") as api:
            with redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(main(argv), 1)
            self.assertIn("current workflow GITHUB_REPOSITORY", errors.getvalue())
            api.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
