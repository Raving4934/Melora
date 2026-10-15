#!/usr/bin/env python3
"""Offline tests for Android release-run provenance verification."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from verify_android_release_run import (
    VerificationError,
    checkout_commit_from_logs,
    main,
    verify_release_run,
)


RUN_ID = 38142888512
ATTEMPT = 1
WORKFLOW_ID = 362333223
CHECKOUT_SHA = "13da23a78535dba213b9730297bdb568d85e7632"
WORKFLOW_SHA = "09e61cae109114886520949a686cc2cda3e97b84"
REPOSITORY = "Raving4934/Melora"
TAG = "android-v1.2.3"


CHECKOUT_START = "2026-10-11T13:22:17Z"
CHECKOUT_END = "2026-10-11T13:22:19Z"


def build_log(checkout_sha: str = CHECKOUT_SHA) -> bytes:
    # Mirrors source-build.log: the Run group closes after with/env, while checkout output continues.
    return (
        "2026-10-11T13:22:16.9000000Z [command]/usr/bin/git log -1 --format=%H\n"
        "2026-10-11T13:22:16.9010000Z " + WORKFLOW_SHA + "\n"
        "2026-10-11T13:22:17.8749560Z ##[group]Run actions/checkout@v4.2.2\n"
        "2026-10-11T13:22:17.8750713Z with:\n"
        "2026-10-11T13:22:17.8751309Z   ref: 13da23a78535dba213b9730297bdb568d85e7632\n"
        "2026-10-11T13:22:17.8868571Z ##[endgroup]\n"
        "2026-10-11T13:22:17.9879895Z Syncing repository: Raving4934/Melora\n"
        "2026-10-11T13:22:17.9883156Z ##[group]Getting Git version info\n"
        "2026-10-11T13:22:17.9944789Z git version 2.55.0\n"
        "2026-10-11T13:22:17.9970617Z ##[endgroup]\n"
        "2026-10-11T13:22:19.4033749Z HEAD is now at 13da23a chore(android): prepare 0.1.10 release\n"
        "2026-10-11T13:22:19.4123316Z [command]/usr/bin/git log -1 --format=%H\n"
        f"2026-10-11T13:22:19.4150733Z {checkout_sha}\n"
        "2026-10-11T13:22:19.4161168Z ##[group]Removing auth\n"
    ).encode()


class FakeApi:
    def __init__(self) -> None:
        self.workflow = {"id": WORKFLOW_ID, "path": ".github/workflows/android-ci.yml"}
        self.run = {
            "id": RUN_ID,
            "repository": {"full_name": REPOSITORY},
            "head_repository": {"full_name": REPOSITORY},
            "workflow_id": WORKFLOW_ID,
            "path": ".github/workflows/android-ci.yml",
            "event": "workflow_dispatch",
            "status": "completed",
            # A failed publisher may make the overall source run fail; gates below remain authoritative.
            "conclusion": "failure",
            "run_attempt": ATTEMPT,
            "head_sha": WORKFLOW_SHA,
        }
        self.jobs = [self.job(name, job_id, "success") for name, job_id in (
            ("prepare", 101),
            ("verify", 102),
            ("release-build", 103),
            ("android-ci-result", 104),
            ("device-regression (35, 0)", 105),
            ("device-regression (35, 1)", 106),
        )]
        self.artifacts = [{
            "id": 11703074332,
            "name": f"android-release-assets-{RUN_ID}-{ATTEMPT}",
            "expired": False,
            "size_in_bytes": 4358244,
            # API artifact metadata has no manifest/files list; provenance is the workflow_run object.
            "workflow_run": {"id": RUN_ID, "repository_id": 1373633612},
        }]
        self.logs = build_log()
        self.jobs_by_attempt: dict[int, list[dict[str, object]]] = {}
        self.calls: list[tuple[str, object]] = []

    @staticmethod
    def job(name: str, job_id: int, conclusion: str) -> dict[str, object]:
        return {
            "id": job_id,
            "name": name,
            "run_id": RUN_ID,
            "run_attempt": ATTEMPT,
            "status": "completed",
            "conclusion": conclusion,
            **({"steps": [{
                "name": "Run actions/checkout@v4.2.2",
                "status": "completed",
                "conclusion": "success",
                "started_at": CHECKOUT_START,
                "completed_at": CHECKOUT_END,
            }]} if name == "release-build" else {}),
        }

    def get(self, path: str) -> dict[str, object]:
        self.calls.append(("get", path))
        if path.endswith("/actions/workflows/android-ci.yml"):
            return self.workflow
        if path.endswith(f"/actions/runs/{RUN_ID}"):
            return self.run
        raise AssertionError(f"unexpected GET {path}")

    def get_paginated(self, path: str, *, result_key: str) -> list[dict[str, object]]:
        self.calls.append(("get_paginated", (path, result_key)))
        if "/attempts/" in path and path.endswith("/jobs?per_page=100") and result_key == "jobs":
            attempt = int(path.split("/attempts/")[1].split("/")[0])
            return self.jobs_by_attempt.get(attempt, self.jobs)
        if path.endswith("/artifacts?per_page=100") and result_key == "artifacts":
            return self.artifacts
        raise AssertionError(f"unexpected paginated GET {path} ({result_key})")

    def get_bytes(self, path: str, *, accept: str) -> bytes:
        self.calls.append(("get_bytes", (path, accept)))
        if path.endswith("/actions/jobs/103/logs") and accept == "application/vnd.github+json":
            return self.logs
        raise AssertionError(f"unexpected byte GET {path}")


class VerifyAndroidReleaseRunTest(unittest.TestCase):
    def setUp(self) -> None:
        self.api = FakeApi()

    def verify(self, **overrides: object) -> dict[str, object]:
        options: dict[str, object] = {
            "repository": REPOSITORY,
            "run_id": RUN_ID,
            "tag": TAG,
            "expected_commit": CHECKOUT_SHA,
            "github_repository": REPOSITORY,
        }
        options.update(overrides)
        return verify_release_run(self.api, **options)  # type: ignore[arg-type]

    def test_success_uses_build_checkout_sha_not_workflow_head_sha(self) -> None:
        result = self.verify()
        self.assertEqual(result, {"artifact_id": 11703074332, "run_id": RUN_ID, "version": "1.2.3"})
        self.assertNotEqual(self.api.run["head_sha"], CHECKOUT_SHA)
        self.assertIn((
            "get_bytes",
            ("/repos/Raving4934/Melora/actions/jobs/103/logs", "application/vnd.github+json"),
        ), self.api.calls)
        paginated = [call for call in self.api.calls if call[0] == "get_paginated"]
        self.assertEqual(len(paginated), 2)

    def test_repository_workflow_event_and_completion_are_enforced(self) -> None:
        with self.subTest("invocation repository"):
            with self.assertRaisesRegex(VerificationError, "GITHUB_REPOSITORY"):
                self.verify(github_repository="someone/else")
        with self.subTest("source repository"):
            self.api.run["repository"] = {"full_name": "someone/else"}
            with self.assertRaisesRegex(VerificationError, "different repository"):
                self.verify()
        self.setUp()
        with self.subTest("workflow id"):
            self.api.run["workflow_id"] = WORKFLOW_ID + 1
            with self.assertRaisesRegex(VerificationError, "android-ci.yml"):
                self.verify()
        self.setUp()
        with self.subTest("event"):
            self.api.run["event"] = "pull_request"
            with self.assertRaisesRegex(VerificationError, "event"):
                self.verify()
        self.setUp()
        with self.subTest("incomplete"):
            self.api.run["status"] = "in_progress"
            with self.assertRaisesRegex(VerificationError, "not completed"):
                self.verify()

    def test_each_required_gate_must_succeed_and_be_unique(self) -> None:
        for conclusion in ("failure", "cancelled", "skipped"):
            with self.subTest(conclusion=conclusion):
                self.setUp()
                self.api.jobs[0]["conclusion"] = conclusion
                with self.assertRaisesRegex(VerificationError, "prepare.*successfully"):
                    self.verify()
        self.setUp()
        self.api.jobs.append(FakeApi.job("prepare", 107, "success"))
        with self.assertRaisesRegex(VerificationError, "exactly one prepare"):
            self.verify()

    def test_all_device_shards_must_exist_and_succeed(self) -> None:
        self.api.jobs.pop()
        with self.assertRaisesRegex(VerificationError, "shards"):
            self.verify()
        self.setUp()
        self.api.jobs[-1]["conclusion"] = "cancelled"
        with self.assertRaisesRegex(VerificationError, "device-regression shard.*successfully"):
            self.verify()

    def test_build_checkout_commit_must_match_expected_sha(self) -> None:
        self.api.logs = build_log("b" * 40)
        with self.assertRaisesRegex(VerificationError, "checkout commit"):
            self.verify()

    def test_first_checkout_git_log_must_be_followed_immediately_by_full_sha(self) -> None:
        self.api.logs = build_log().replace(
            f"2026-10-11T13:22:19.4150733Z {CHECKOUT_SHA}".encode(),
            b"2026-10-11T13:22:19.4150733Z not-a-sha",
        )
        with self.assertRaisesRegex(VerificationError, "immediately after git log"):
            checkout_commit_from_logs(
                self.api.logs,
                step_started_at=CHECKOUT_START,
                step_completed_at=CHECKOUT_END,
            )

    def test_completion_second_is_included_but_next_second_is_exclusive(self) -> None:
        # The real source API reports completed_at=13:22:19Z while its SHA line is .415Z.
        self.assertEqual(
            checkout_commit_from_logs(
                self.api.logs,
                step_started_at=CHECKOUT_START,
                step_completed_at="2026-10-11T13:22:19Z",
            ),
            CHECKOUT_SHA,
        )
        next_second = (
            "2026-10-11T13:22:20Z [command]/usr/bin/git log -1 --format=%H\n"
            f"2026-10-11T13:22:20Z {CHECKOUT_SHA}\n"
        ).encode()
        with self.assertRaisesRegex(VerificationError, "immediately after git log"):
            checkout_commit_from_logs(
                next_second,
                step_started_at=CHECKOUT_START,
                step_completed_at="2026-10-11T13:22:19Z",
            )

    def test_current_attempt_artifact_must_be_unique_live_nonempty_and_same_run(self) -> None:
        self.api.artifacts[0]["expired"] = True
        with self.assertRaisesRegex(VerificationError, "expired"):
            self.verify()
        self.setUp()
        self.api.artifacts[0]["size_in_bytes"] = 0
        with self.assertRaisesRegex(VerificationError, "empty"):
            self.verify()
        self.setUp()
        self.api.artifacts[0]["workflow_run"] = {"id": RUN_ID + 1}
        with self.assertRaisesRegex(VerificationError, "different workflow run"):
            self.verify()
        self.setUp()
        self.api.artifacts.append(dict(self.api.artifacts[0]))
        with self.assertRaisesRegex(VerificationError, "found 2"):
            self.verify()

    def test_publisher_only_rerun_uses_the_original_complete_build_attempt(self) -> None:
        self.api.jobs_by_attempt[1] = self.api.jobs
        release = FakeApi.job("release", 107, "failure")
        release["run_attempt"] = 2
        self.api.jobs_by_attempt[2] = [release]
        self.api.run["run_attempt"] = 2
        result = self.verify()
        self.assertEqual(11703074332, result["artifact_id"])
        self.assertIn(("get_paginated", (
            f"/repos/{REPOSITORY}/actions/runs/{RUN_ID}/attempts/1/jobs?per_page=100", "jobs",
        )), self.api.calls)

    def test_newer_failed_build_cannot_fall_back_to_older_green_artifact(self) -> None:
        self.api.run["run_attempt"] = 2
        self.api.jobs_by_attempt[1] = self.api.jobs
        second = [dict(job, run_attempt=2) for job in self.api.jobs]
        second[1]["conclusion"] = "failure"
        self.api.jobs_by_attempt[2] = second
        self.api.artifacts.append(dict(self.api.artifacts[0],
            id=11703074333, name=f"android-release-assets-{RUN_ID}-2"))
        with self.assertRaisesRegex(VerificationError, "verify.*successfully"):
            self.verify()

    def test_original_build_gates_are_not_replaced_with_newer_job_results(self) -> None:
        self.api.run["run_attempt"] = 2
        self.api.jobs_by_attempt[2] = [dict(job, run_attempt=2) for job in self.api.jobs]
        self.api.jobs[1]["conclusion"] = "failure"
        self.api.jobs_by_attempt[1] = self.api.jobs
        with self.assertRaisesRegex(VerificationError, "verify.*successfully"):
            self.verify()

    def test_artifact_attempt_outside_the_run_is_rejected(self) -> None:
        for suffix in ("0", "2", "01", "not-a-number"):
            with self.subTest(suffix=suffix):
                self.api.artifacts[0]["name"] = f"android-release-assets-{RUN_ID}-{suffix}"
                with self.assertRaises(VerificationError):
                    self.verify()

    def test_cli_writes_only_verified_numeric_ids_and_tag_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token", "GITHUB_REPOSITORY": REPOSITORY}):
                with patch("verify_android_release_run.GitHubApi", return_value=self.api):
                    status = main([
                        "--repository", REPOSITORY,
                        "--run-id", str(RUN_ID),
                        "--tag", TAG,
                        "--expected-commit", CHECKOUT_SHA,
                        "--output", str(output),
                    ])
            self.assertEqual(status, 0)
            self.assertEqual(
                output.read_text(),
                f"artifact_id=11703074332\nrun_id={RUN_ID}\nversion=1.2.3\n",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
