#!/usr/bin/env python3
"""Offline tests for the GitHub API client's redirect credential boundary."""

from __future__ import annotations

import unittest
import urllib.request
import urllib.error
from unittest.mock import Mock

from github_api import GitHubApi, GitHubApiError, SafeRedirectHandler


class RedirectSecurityTest(unittest.TestCase):
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
