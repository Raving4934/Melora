#!/usr/bin/env python3
"""Verify an existing Android build run and expose its immutable artifact ID."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
import os
import re
import sys
from typing import Any

try:
    from github_api import GitHubApi, GitHubApiError
except ImportError:  # pragma: no cover - package import
    from .github_api import GitHubApi, GitHubApiError


WORKFLOW_PATH = ".github/workflows/android-ci.yml"
TAG_RE = re.compile(r"^android-v([0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?)$")
REPOSITORY_RE = re.compile(r"^[^/\s]+/[^/\s]+$")
SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
RUN_ID_RE = re.compile(r"^[0-9]+$")
LOG_LINE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)\s*(.*)$")
CHECKOUT_COMMAND_RE = re.compile(r"(?:##)?\[command\](?:\S*/)?git log -1 --format=%H")
DEVICE_JOB_RE = re.compile(r"^device-regression \((26|35|37), ([01])\)$")
REQUIRED_JOBS = ("prepare", "verify", "release-build", "android-ci-result")


class VerificationError(RuntimeError):
    """A source run or artifact failed provenance validation."""


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise VerificationError(f"{label} must be a positive integer")
    if isinstance(value, int):
        number = value
    elif isinstance(value, str) and RUN_ID_RE.fullmatch(value):
        number = int(value)
    else:
        raise VerificationError(f"{label} must be a positive integer")
    if number <= 0:
        raise VerificationError(f"{label} must be a positive integer")
    return number


def _log_record(line: str) -> tuple[datetime | None, str]:
    """Parse a raw runner log line, accepting gh's two-column display prefix too."""
    line = line.rstrip("\r\n")
    fields = line.split("\t")
    if len(fields) >= 3:
        line = fields[-1]
    line = line.lstrip("\ufeff")
    match = LOG_LINE_RE.fullmatch(line)
    if match is None:
        return None, line
    timestamp = datetime.fromisoformat(match.group(1).replace("Z", "+00:00"))
    return timestamp, match.group(2)


def _parse_api_time(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise VerificationError(f"checkout step has no {label} timestamp")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise VerificationError(f"checkout step has an invalid {label} timestamp") from error


def checkout_commit_from_logs(log_bytes: bytes, *, step_started_at: str, step_completed_at: str) -> str:
    """Find checkout's first git-log output inside that API-reported step window."""
    try:
        log_text = log_bytes.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise VerificationError("release-build logs are not valid UTF-8") from error

    started = _parse_api_time(step_started_at, "start")
    completed = _parse_api_time(step_completed_at, "completion")
    if completed < started:
        raise VerificationError("checkout step timestamps are out of order")
    # The Actions jobs API rounds completed_at down to whole seconds; runner log
    # timestamps retain fractions. Include that reported second, but not the next.
    completed_exclusive = completed.replace(microsecond=0) + timedelta(seconds=1)
    records = [_log_record(line) for line in log_text.splitlines()]
    for index, (timestamp, message) in enumerate(records):
        if timestamp is None or timestamp < started or timestamp >= completed_exclusive:
            continue
        if CHECKOUT_COMMAND_RE.fullmatch(message):
            if index + 1 >= len(records):
                break
            next_timestamp, commit = records[index + 1]
            if (
                next_timestamp is not None
                and started <= next_timestamp < completed_exclusive
                and SHA_RE.fullmatch(commit)
            ):
                return commit.lower()
            break
    raise VerificationError("actions/checkout did not log a full commit immediately after git log")


def _release_checkout_window(release_job: dict[str, Any]) -> tuple[str, str]:
    steps = release_job.get("steps")
    if not isinstance(steps, list):
        raise VerificationError("release-build job has no step metadata")
    checkouts = [
        step for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("name"), str)
        and step["name"].startswith("Run actions/checkout@")
    ]
    if len(checkouts) != 1:
        raise VerificationError("release-build must contain one actions/checkout step")
    step = checkouts[0]
    started_at = step.get("started_at")
    completed_at = step.get("completed_at")
    _parse_api_time(started_at, "start")
    _parse_api_time(completed_at, "completion")
    return started_at, completed_at


def _validate_jobs(api: Any, repository_path: str, run_id: int, attempt: int) -> dict[str, Any]:
    jobs_path = f"{repository_path}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100"
    jobs = api.get_paginated(jobs_path, result_key="jobs")
    if not isinstance(jobs, list):
        raise VerificationError("GitHub returned an invalid job list")

    named_jobs: dict[str, list[dict[str, Any]]] = {name: [] for name in REQUIRED_JOBS}
    device_jobs: dict[tuple[int, int], dict[str, Any]] = {}
    for job in jobs:
        if not isinstance(job, dict):
            raise VerificationError("GitHub returned an invalid job entry")
        job_run_id = job.get("run_id")
        job_attempt = job.get("run_attempt")
        if job_run_id is not None and _positive_int(job_run_id, "job run_id") != run_id:
            raise VerificationError("a job belongs to a different workflow run")
        if job_attempt is not None and _positive_int(job_attempt, "job run_attempt") != attempt:
            raise VerificationError("a job belongs to a different run attempt")

        name = job.get("name")
        if name in named_jobs:
            named_jobs[name].append(job)
        elif isinstance(name, str) and name.startswith("device-regression"):
            match = DEVICE_JOB_RE.fullmatch(name)
            if match is None:
                raise VerificationError(f"unrecognized device-regression shard: {name}")
            shard = (int(match.group(1)), int(match.group(2)))
            if shard in device_jobs:
                raise VerificationError(f"duplicate device-regression shard: {name}")
            device_jobs[shard] = job

    required: dict[str, dict[str, Any]] = {}
    for name, matches in named_jobs.items():
        if len(matches) != 1:
            raise VerificationError(f"expected exactly one {name} job; found {len(matches)}")
        job = matches[0]
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            raise VerificationError(f"required job {name} did not complete successfully")
        required[name] = job

    if (26, 0) in device_jobs or (37, 0) in device_jobs:
        expected_shards = {(api_level, shard) for api_level in (26, 35, 37) for shard in (0, 1)}
    else:
        expected_shards = {(35, 0), (35, 1)}
    if set(device_jobs) != expected_shards:
        raise VerificationError("device-regression shards are missing or unexpected")
    for name, job in device_jobs.items():
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            raise VerificationError(f"device-regression shard {name} did not complete successfully")

    _positive_int(required["release-build"].get("id"), "release-build job id")
    return required["release-build"]


def verify_release_run(
    api: Any,
    *,
    repository: str,
    run_id: int,
    tag: str,
    expected_commit: str,
    github_repository: str,
) -> dict[str, Any]:
    """Validate the current successful build attempt without mutating GitHub."""
    if not REPOSITORY_RE.fullmatch(repository):
        raise VerificationError("repository must be owner/repo")
    if github_repository.casefold() != repository.casefold():
        raise VerificationError("--repository does not match GITHUB_REPOSITORY")
    run_id = _positive_int(run_id, "run id")
    tag_match = TAG_RE.fullmatch(tag)
    if tag_match is None:
        raise VerificationError("tag must match android-v<semver>")
    if not SHA_RE.fullmatch(expected_commit):
        raise VerificationError("expected commit must be a full 40-character SHA")
    expected_commit = expected_commit.lower()
    version = tag_match.group(1)
    repository_path = f"/repos/{repository}"

    workflow = api.get(f"{repository_path}/actions/workflows/android-ci.yml")
    if not isinstance(workflow, dict) or workflow.get("path") != WORKFLOW_PATH:
        raise VerificationError("could not confirm the Android CI workflow")
    workflow_id = _positive_int(workflow.get("id"), "Android CI workflow id")

    source = api.get(f"{repository_path}/actions/runs/{run_id}")
    if not isinstance(source, dict) or _positive_int(source.get("id"), "source run id") != run_id:
        raise VerificationError("GitHub returned a different source workflow run")
    source_repository = source.get("repository")
    if not isinstance(source_repository, dict) or not isinstance(source_repository.get("full_name"), str):
        raise VerificationError("source run has no repository identity")
    if source_repository["full_name"].casefold() != repository.casefold():
        raise VerificationError("source run belongs to a different repository")
    if _positive_int(source.get("workflow_id"), "source workflow id") != workflow_id or source.get("path") != WORKFLOW_PATH:
        raise VerificationError("source run is not from .github/workflows/android-ci.yml")
    if source.get("event") not in ("push", "workflow_dispatch"):
        raise VerificationError("source run event must be push or workflow_dispatch")
    if source.get("status") != "completed":
        raise VerificationError("source run is not completed")
    attempt = _positive_int(source.get("run_attempt"), "source run attempt")

    release_job = _validate_jobs(api, repository_path, run_id, attempt)
    release_job_id = _positive_int(release_job.get("id"), "release-build job id")
    step_started_at, step_completed_at = _release_checkout_window(release_job)
    build_log = api.get_bytes(
        f"{repository_path}/actions/jobs/{release_job_id}/logs",
        accept="application/vnd.github+json",
    )
    checkout_commit = checkout_commit_from_logs(
        build_log,
        step_started_at=step_started_at,
        step_completed_at=step_completed_at,
    )
    if checkout_commit != expected_commit:
        raise VerificationError("release-build checkout commit does not match --expected-commit")

    artifact_name = f"android-release-assets-{run_id}-{attempt}"
    artifacts = api.get_paginated(
        f"{repository_path}/actions/runs/{run_id}/artifacts?per_page=100",
        result_key="artifacts",
    )
    if not isinstance(artifacts, list):
        raise VerificationError("GitHub returned an invalid artifact list")
    matches = [item for item in artifacts if isinstance(item, dict) and item.get("name") == artifact_name]
    if len(matches) != 1:
        raise VerificationError(f"expected one current-attempt artifact {artifact_name}; found {len(matches)}")

    artifact = matches[0]
    artifact_id = _positive_int(artifact.get("id"), "artifact id")
    if artifact.get("expired") is not False:
        raise VerificationError("release artifact is expired or has no expiry status")
    size = artifact.get("size_in_bytes")
    if isinstance(size, bool) or not isinstance(size, (int, str)) or (isinstance(size, str) and not RUN_ID_RE.fullmatch(size)):
        raise VerificationError("release artifact has an invalid size")
    if int(size) <= 0:
        raise VerificationError("release artifact is empty")
    artifact_run = artifact.get("workflow_run")
    if not isinstance(artifact_run, dict) or _positive_int(artifact_run.get("id"), "artifact workflow run id") != run_id:
        raise VerificationError("release artifact belongs to a different workflow run")

    return {"artifact_id": artifact_id, "run_id": run_id, "version": version}


def _positive_int_argument(value: str) -> int:
    if not RUN_ID_RE.fullmatch(value) or int(value) <= 0:
        raise argparse.ArgumentTypeError("must be a positive decimal integer")
    return int(value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True, type=_positive_int_argument)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--output", required=True, help="GitHub Actions output file")
    args = parser.parse_args(argv)

    try:
        api = GitHubApi(os.environ.get("GITHUB_TOKEN", ""))
        result = verify_release_run(
            api,
            repository=args.repository,
            run_id=args.run_id,
            tag=args.tag,
            expected_commit=args.expected_commit,
            github_repository=os.environ.get("GITHUB_REPOSITORY", ""),
        )
        with open(args.output, "a", encoding="utf-8") as output:
            output.write(
                f"artifact_id={result['artifact_id']}\n"
                f"run_id={result['run_id']}\n"
                f"version={result['version']}\n"
            )
    except (VerificationError, GitHubApiError, OSError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
