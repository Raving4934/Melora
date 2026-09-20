#!/usr/bin/env python3
"""Small dependency-free GitHub REST client used by release workflows.

The client deliberately never includes authorization headers or response bodies in
raised errors.  It is intended for CI scripts where a useful failure must not
accidentally disclose a token or a provider response containing sensitive data.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Never forward a bearer token to a different redirect host."""

    @staticmethod
    def _drop_authorization(request: urllib.request.Request) -> None:
        for header_map in (request.headers, request.unredirected_hdrs):
            for key in list(header_map):
                if key.lower() == "authorization":
                    del header_map[key]

    def redirect_request(self, req: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> urllib.request.Request | None:
        target = urllib.parse.urlsplit(newurl)
        if target.scheme != "https" or target.username is not None or target.password is not None:
            raise urllib.error.URLError("GitHub redirects must remain credential-free HTTPS URLs")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is None:
            return None
        old_host = urllib.parse.urlsplit(req.full_url).netloc.lower()
        new_host = urllib.parse.urlsplit(newurl).netloc.lower()
        if old_host != new_host:
            self._drop_authorization(redirected)
        return redirected


_SAFE_OPENER = urllib.request.build_opener(SafeRedirectHandler())


class GitHubApiError(RuntimeError):
    """A safe, non-secret GitHub API error."""

    def __init__(self, message: str, *, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


class GitHubNotFound(GitHubApiError):
    """The requested GitHub resource does not exist."""


@dataclass(frozen=True)
class GitHubResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


class GitHubApi:
    """Minimal REST client with safe JSON, binary download and upload helpers."""

    def __init__(
        self,
        token: str,
        *,
        api_base_url: str | None = None,
        opener: Any | None = None,
        timeout: int = 60,
    ) -> None:
        if not token:
            raise GitHubApiError("GitHub API token is missing.")
        self._token = token
        self._base = (api_base_url or os.environ.get("GITHUB_API_URL") or "https://api.github.com").rstrip("/")
        self._opener = opener or _SAFE_OPENER.open
        self._timeout = timeout

    @staticmethod
    def _path_url(base: str, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        if not path.startswith("/"):
            path = "/" + path
        return base + path

    def _request(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        content_type: str | None = None,
        accept: str = "application/vnd.github+json",
    ) -> GitHubResponse:
        target = urllib.parse.urlsplit(url)
        if target.scheme != "https" or target.username is not None or target.password is not None:
            raise GitHubApiError("GitHub requests require credential-free HTTPS URLs")
        headers = {
            "Accept": accept,
            "Authorization": f"Bearer {self._token}",
            "User-Agent": "Melora-Android-Release/1",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with self._opener(request, timeout=self._timeout) as response:
                return GitHubResponse(
                    status=int(response.status),
                    headers=dict(response.headers.items()),
                    body=response.read(),
                )
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise GitHubNotFound(f"GitHub API resource was not found: {method} {url.split('?', 1)[0]}", status=404) from None
            raise GitHubApiError(
                f"GitHub API request failed with HTTP {error.code}: {method} {url.split('?', 1)[0]}",
                status=error.code,
            ) from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise GitHubApiError(f"GitHub API request could not be completed: {method} {url.split('?', 1)[0]}") from error

    def _json_request(self, method: str, path: str, payload: Mapping[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        response = self._request(method, self._path_url(self._base, path), body=body, content_type="application/json")
        if not response.body:
            return {}
        try:
            return json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise GitHubApiError(f"GitHub API returned invalid JSON for {method} {path}") from error

    def get(self, path: str) -> Any:
        return self._json_request("GET", path)

    def post(self, path: str, payload: Mapping[str, Any]) -> Any:
        return self._json_request("POST", path, payload)

    def patch(self, path: str, payload: Mapping[str, Any]) -> Any:
        return self._json_request("PATCH", path, payload)

    def delete(self, path: str) -> None:
        self._request("DELETE", self._path_url(self._base, path))

    def get_bytes(self, path: str) -> bytes:
        response = self._request(
            "GET",
            self._path_url(self._base, path),
            accept="application/octet-stream",
        )
        return response.body

    def upload(self, upload_url: str, *, name: str, content: bytes, content_type: str) -> Any:
        clean_url = upload_url.split("{", 1)[0]
        separator = "&" if "?" in clean_url else "?"
        url = clean_url + separator + urllib.parse.urlencode({"name": name})
        response = self._request(
            "POST",
            url,
            body=content,
            content_type=content_type,
        )
        try:
            return json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise GitHubApiError("GitHub upload endpoint returned invalid JSON.") from error

    @staticmethod
    def _next_link(link_header: str | None) -> str | None:
        if not link_header:
            return None
        for item in link_header.split(","):
            url_part, *attributes = item.split(";", 1)
            if any('rel="next"' in attribute for attribute in attributes):
                return url_part.strip().strip("<>")
        return None

    def get_paginated(self, path: str, *, result_key: str | None = None) -> list[Any]:
        url = self._path_url(self._base, path)
        values: list[Any] = []
        while url:
            response = self._request("GET", url)
            try:
                payload = json.loads(response.body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise GitHubApiError("GitHub pagination endpoint returned invalid JSON.") from error
            page = payload.get(result_key, []) if result_key else payload
            if not isinstance(page, list):
                raise GitHubApiError("GitHub pagination endpoint returned an unexpected shape.")
            values.extend(page)
            url = self._next_link(response.headers.get("Link") or response.headers.get("link"))
        return values
