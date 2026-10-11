#!/usr/bin/env python3
"""Offline tests for the GitHub API client's redirect credential boundary."""

from __future__ import annotations

import io
import json
import unittest
import urllib.request
import urllib.error
from email.message import Message
from unittest.mock import Mock, patch

from github_api import (
    GitHubApi,
    GitHubApiError,
    GitHubNotFound,
    GitHubResponse,
    SafeRedirectHandler,
)


class _BoundedReader:
    def __init__(self, body: bytes) -> None:
        self._body = io.BytesIO(body)
        self.read_sizes: list[int] = []

    def read(self, size: int = -1) -> bytes:
        self.read_sizes.append(size)
        return self._body.read(size)

    def close(self) -> None:
        self._body.close()


def _http_error(
    code: int = 403,
    *,
    body: bytes = b"",
    headers: dict[str, str] | None = None,
    reader: _BoundedReader | None = None,
) -> urllib.error.HTTPError:
    response_headers = Message()
    for key, value in (headers or {}).items():
        response_headers[key] = value
    return urllib.error.HTTPError(
        "https://api.github.com/repos/owner/Melora/releases",
        code,
        "Forbidden",
        response_headers,
        reader or io.BytesIO(body),
    )


class HttpErrorDiagnosticsTest(unittest.TestCase):
    def request_error(self, error: urllib.error.HTTPError, token: str = "synthetic-token") -> GitHubApiError:
        api = GitHubApi(token, opener=Mock(side_effect=error))
        with self.assertRaises(GitHubApiError) as raised:
            api.post("/repos/owner/Melora/releases", {"tag_name": "v1"})
        return raised.exception

    def test_error_responses_are_closed_including_not_found(self) -> None:
        for status in (403, 404, 500):
            with self.subTest(status=status):
                response = _http_error(code=status, body=b'{"message":"failure"}')
                stream = response.fp
                self.request_error(response)
                self.assertTrue(stream.closed)

    def test_forbidden_error_includes_limited_api_message_and_status(self) -> None:
        body = json.dumps({
            "message": "Resource not accessible by integration",
            "private_detail": "must-not-be-logged",
        }).encode()
        error = self.request_error(_http_error(body=body))

        self.assertEqual(error.status, 403)
        self.assertIn("HTTP 403", str(error))
        self.assertIn("message: Resource not accessible by integration", str(error))
        self.assertNotIn("private_detail", str(error))
        self.assertNotIn("must-not-be-logged", str(error))

    def test_valid_github_request_id_is_included_but_other_headers_are_not(self) -> None:
        error = self.request_error(_http_error(headers={
            "X-GitHub-Request-Id": "A1B2C3D4:01234567:89ABCDEF",
            "Authorization": "Bearer header-secret-token",
            "X-Internal-Debug": "private-header-value",
        }))

        self.assertIn("request_id=A1B2C3D4:01234567:89ABCDEF", str(error))
        self.assertNotIn("Authorization", str(error))
        self.assertNotIn("header-secret-token", str(error))
        self.assertNotIn("private-header-value", str(error))

    def test_rate_limit_and_accepted_permission_fields_are_reported(self) -> None:
        error = self.request_error(_http_error(headers={
            "X-RateLimit-Limit": "5000",
            "X-RateLimit-Remaining": "0",
            "X-RateLimit-Used": "5000",
            "X-RateLimit-Reset": "1791234567",
            "X-RateLimit-Resource": "core",
            "Retry-After": "60",
            "X-Accepted-GitHub-Permissions": "contents=write, issues=read; contents=read",
            "X-Private-Diagnostic": "must-not-be-logged",
        }))

        for detail in (
            "rate_limit_limit=5000",
            "rate_limit_remaining=0",
            "rate_limit_used=5000",
            "rate_limit_reset=1791234567",
            "rate_limit_resource=core",
            "retry_after=60",
            "accepted_permissions=contents=write, issues=read; contents=read",
        ):
            self.assertIn(detail, str(error))
        self.assertNotIn("must-not-be-logged", str(error))

    def test_html_error_body_is_never_echoed(self) -> None:
        body = b"<html><body>proxy-secret-page</body></html>"
        error = self.request_error(_http_error(body=body))

        self.assertIn("HTTP 403", str(error))
        self.assertNotIn("<html>", str(error))
        self.assertNotIn("proxy-secret-page", str(error))

    def test_oversized_error_body_is_read_only_to_the_fixed_limit(self) -> None:
        reader = _BoundedReader(b'{"message":"' + b"x" * 20_000 + b'"}')
        error = self.request_error(_http_error(reader=reader))

        self.assertEqual(reader.read_sizes, [8193])
        self.assertNotIn("message:", str(error))
        self.assertNotIn("x" * 50, str(error))

    def test_api_message_length_is_capped(self) -> None:
        message = "permission-denied-" + "x" * 500
        error = self.request_error(
            _http_error(body=json.dumps({"message": message}).encode()),
        )

        rendered_message = str(error).split("; message: ", 1)[1]
        self.assertEqual(rendered_message, message[:240])

    def test_non_json_and_unexpected_json_shapes_are_not_echoed(self) -> None:
        bodies = (
            b"<!doctype html><title>private html</title>",
            b'["unexpected-array-secret"]',
            b'{"message":{"private":"object-secret"}}',
            b'{"message":403}',
            b'{"message":"string-secret"',
        )
        for body in bodies:
            with self.subTest(body=body[:30]):
                error = self.request_error(_http_error(body=body))
                self.assertNotIn("private html", str(error))
                self.assertNotIn("unexpected-array-secret", str(error))
                self.assertNotIn("object-secret", str(error))
                self.assertNotIn("string-secret", str(error))

    def test_token_patterns_and_workflow_commands_are_redacted(self) -> None:
        current_token = "current-token-secret-123"
        message = (
            f"denied {current_token} ghp_syntheticPATsecret123 "
            "prefix_ghp_embeddedsecret123_suffix "
            "github_pat_synthetic_fine_grainedPATsecret123 "
            "Bearer arbitrary-secret-token-123 token=plain-secret-value-123 "
            "\n::error::workflow-injection\r\x1b[31m"
        )
        error = self.request_error(
            _http_error(body=json.dumps({"message": message}).encode()),
            token=current_token,
        )
        rendered = str(error)

        for secret in (
            current_token,
            "ghp_syntheticPATsecret123",
            "ghp_embeddedsecret123_suffix",
            "github_pat_synthetic_fine_grainedPATsecret123",
            "arbitrary-secret-token-123",
            "plain-secret-value-123",
        ):
            self.assertNotIn(secret, rendered)
        self.assertNotIn("\n", rendered)
        self.assertNotIn("\r", rendered)
        self.assertNotIn("\x1b", rendered)
        self.assertNotIn("::", rendered)

    def test_404_remains_github_not_found_without_body_diagnostics(self) -> None:
        error = _http_error(404, body=b'{"message":"must-not-change-404-semantics"}')
        api = GitHubApi("synthetic-token", opener=Mock(side_effect=error))

        with self.assertRaises(GitHubNotFound) as raised:
            api.get("/repos/owner/Melora/releases")

        self.assertEqual(raised.exception.status, 404)
        self.assertIn("resource was not found", str(raised.exception))
        self.assertNotIn("must-not-change-404-semantics", str(raised.exception))


class RedirectSecurityTest(unittest.TestCase):
    def test_raw_logs_can_request_json_media_type_without_changing_asset_default(self) -> None:
        api = GitHubApi("synthetic-token")
        for accept in (None, "application/vnd.github+json"):
            with self.subTest(accept=accept), patch.object(api, "_request", return_value=GitHubResponse(200, {}, b"raw log")) as request:
                result = api.get_bytes("/actions/jobs/1/logs", **({"accept": accept} if accept else {}))
                self.assertEqual(result, b"raw log")
                self.assertEqual(request.call_args.kwargs["accept"], accept or "application/octet-stream")

    def test_release_pagination_keeps_drafts_on_later_pages(self) -> None:
        api = GitHubApi("synthetic-token")
        next_url = "https://api.github.com/repos/owner/Melora/releases?per_page=100&page=2"
        published = {"id": 1, "tag_name": "android-v0.1.8", "draft": False}
        draft = {"id": 2, "tag_name": "android-v0.1.9", "draft": True}
        responses = [
            GitHubResponse(200, {"Link": f'<{next_url}>; rel="next"'}, json.dumps([published]).encode()),
            GitHubResponse(200, {}, json.dumps([draft]).encode()),
        ]
        with patch.object(api, "_request", side_effect=responses) as request:
            self.assertEqual(api.get_paginated("/repos/owner/Melora/releases?per_page=100"),
                             [published, draft])
        self.assertEqual(request.call_count, 2)
        self.assertEqual(request.call_args.args, ("GET", next_url))

    def test_cross_host_redirect_drops_authorization(self) -> None:
        request = urllib.request.Request("https://api.github.com/repos/example/project/releases/assets/1")
        request.add_header("Authorization", "Bearer never-log-this")
        redirected = SafeRedirectHandler().redirect_request(
            request,
            None,
            302,
            "Found",
            {"Location": "https://objects.example.invalid/download"},
            "https://objects.example.invalid/download",
        )
        self.assertIsNotNone(redirected)
        assert redirected is not None
        self.assertNotIn("authorization", {key.lower() for key in redirected.headers})
        self.assertNotIn("authorization", {key.lower() for key in redirected.unredirected_hdrs})

    def test_https_to_http_redirect_is_rejected_even_on_the_same_host(self) -> None:
        request = urllib.request.Request("https://api.github.com/assets/1")
        request.add_header("Authorization", "Bearer synthetic-token")
        with self.assertRaises(urllib.error.URLError):
            SafeRedirectHandler().redirect_request(
                request, None, 302, "Found", {}, "http://api.github.com/assets/2",
            )

    def test_insecure_initial_endpoint_is_rejected_before_opening_a_connection(self) -> None:
        opener = Mock()
        api = GitHubApi("synthetic-token", api_base_url="http://api.github.com", opener=opener)
        with self.assertRaises(GitHubApiError):
            api.get("/repos/owner/Melora")
        opener.assert_not_called()

    def test_same_host_redirect_keeps_authorization(self) -> None:
        request = urllib.request.Request("https://api.github.com/repos/example/project/releases/assets/1")
        request.add_header("Authorization", "Bearer keep-on-api-host")
        redirected = SafeRedirectHandler().redirect_request(
            request,
            None,
            302,
            "Found",
            {"Location": "https://api.github.com/repos/example/project/releases/assets/2"},
            "https://api.github.com/repos/example/project/releases/assets/2",
        )
        self.assertIsNotNone(redirected)
        assert redirected is not None
        values = [value for key, value in redirected.header_items() if key.lower() == "authorization"]
        self.assertEqual(values, ["Bearer keep-on-api-host"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
