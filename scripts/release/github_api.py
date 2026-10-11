#!/usr/bin/env python3
"""Small dependency-free GitHub REST client used by release workflows.

The client deliberately never includes authorization headers or response bodies in
raised errors.  It is intended for CI scripts where a useful failure must not
accidentally disclose a token or a provider response containing sensitive data.
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping


_MAX_ERROR_BODY_BYTES = 8192
_MAX_API_MESSAGE_CHARS = 240
_MAX_ERROR_TEXT_CHARS = 1000
_GITHUB_TOKEN_RE = re.compile(r"(?i)(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+)")
_BEARER_TOKEN_RE = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_NAMED_TOKEN_RE = re.compile(
    r"(?i)\b(?:access[_-]?token|refresh[_-]?token|token)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{8,}"
)
_REQUEST_ID_RE = re.compile(r"[A-Za-z0-9-]{1,64}(?::[A-Za-z0-9-]{1,64}){0,3}\Z")
_NUMERIC_HEADER_RE = re.compile(r"[0-9]{1,16}\Z")
_PERMISSION_ENTRY = r"[A-Za-z][A-Za-z0-9_-]{0,39}=(?:read|write|admin|none|read/write|\*)"
_PERMISSION_GROUP = rf"{_PERMISSION_ENTRY}(?:,\s*{_PERMISSION_ENTRY})*"
_PERMISSION_HEADER_RE = re.compile(rf"{_PERMISSION_GROUP}(?:;\s*{_PERMISSION_GROUP})*\Z")


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


def _safe_error_text(value: str, token: str, *, limit: int) -> str:
    # Remove controls first so inserting one into a secret cannot bypass redaction.
    value = re.sub(r"[\x00-\x1f\x7f-\x9f\u2028\u2029]", "", value)
    value = _GITHUB_TOKEN_RE.sub("[REDACTED]", value)
    value = _BEARER_TOKEN_RE.sub("Bearer [REDACTED]", value)
    value = _NAMED_TOKEN_RE.sub("token=[REDACTED]", value)
    if token:
        value = value.replace(token, "[REDACTED]")
    # GitHub Actions recognizes ::command:: lines in workflow logs.
    value = value.replace("::", "%3A%3A")
    return value[:limit].rstrip()


def _http_error_diagnostics(error: urllib.error.HTTPError, token: str) -> list[str]:
    details: list[str] = []
    headers = getattr(error, "headers", None)
    if headers is not None:
        request_id = headers.get("X-GitHub-Request-Id")
        if isinstance(request_id, str) and _REQUEST_ID_RE.fullmatch(request_id):
            details.append(f"request_id={request_id}")

        for header, label in (
            ("X-RateLimit-Limit", "rate_limit_limit"),
            ("X-RateLimit-Remaining", "rate_limit_remaining"),
            ("X-RateLimit-Used", "rate_limit_used"),
            ("X-RateLimit-Reset", "rate_limit_reset"),
            ("Retry-After", "retry_after"),
        ):
            value = headers.get(header)
            if isinstance(value, str) and _NUMERIC_HEADER_RE.fullmatch(value):
                details.append(f"{label}={value}")

        resource = headers.get("X-RateLimit-Resource")
        if isinstance(resource, str) and re.fullmatch(r"[a-z_]{1,40}", resource):
            details.append(f"rate_limit_resource={resource}")

        permissions = headers.get("X-Accepted-GitHub-Permissions")
        if isinstance(permissions, str) and _PERMISSION_HEADER_RE.fullmatch(permissions):
            details.append(f"accepted_permissions={permissions}")

    try:
        body = error.read(_MAX_ERROR_BODY_BYTES + 1)
    except (OSError, ValueError):
        body = b""
    if isinstance(body, bytes) and len(body) <= _MAX_ERROR_BODY_BYTES:
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, RecursionError):
            payload = None
        if isinstance(payload, dict) and isinstance(payload.get("message"), str):
            message = _safe_error_text(
                payload["message"], token, limit=_MAX_API_MESSAGE_CHARS,
            )
            if message:
                details.append(f"message: {message}")
    return details


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
            try:
                request_target = url.split("?", 1)[0].split("#", 1)[0][:300]
                if error.code == 404:
                    message = _safe_error_text(
                        f"GitHub API resource was not found: {method} {request_target}",
                        self._token,
                        limit=_MAX_ERROR_TEXT_CHARS,
                    )
                    raise GitHubNotFound(message, status=404) from None
                summary = f"GitHub API request failed with HTTP {error.code}: {method} {request_target}"
                details = _http_error_diagnostics(error, self._token)
                if details:
                    summary += "; " + "; ".join(details)
                raise GitHubApiError(
                    _safe_error_text(summary, self._token, limit=_MAX_ERROR_TEXT_CHARS),
                    status=error.code,
                ) from None
            finally:
                error.close()
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

    def get_bytes(self, path: str, *, accept: str = "application/octet-stream") -> bytes:
        response = self._request(
            "GET",
            self._path_url(self._base, path),
            accept=accept,
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
