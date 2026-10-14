"""HTTP client for the Spring tool API. Spring owns all domain logic; this layer only forwards."""
from __future__ import annotations

import logging
import os
import time
from collections.abc import Callable
from typing import Any

import httpx
from fastmcp.exceptions import ToolError
from fastmcp.server.dependencies import get_http_headers

from homecare_mcp.models import Envelope

CONTEXT_VERSION_HEADER = "X-HomeCare-Context-Version"
LOG = logging.getLogger("homecare_mcp.tools")


def resolve_backend_origin(backend_url: str | None = None) -> str:
    origin = (backend_url or os.environ.get("HOMECARE_BACKEND_URL", "http://127.0.0.1:8080")).rstrip("/")
    if not origin.startswith(("http://", "https://")):
        raise ValueError("HOMECARE_BACKEND_URL must be an HTTP(S) origin")
    return origin


class BackendClient:
    def __init__(self, origin: str, token_provider: Callable[[], str],
                 transport: httpx.AsyncBaseTransport | None = None):
        self.origin = origin
        self.token_provider = token_provider
        self.transport = transport

    async def post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        headers = {"Authorization": "Bearer " + self.token_provider()}
        incoming = {k.lower(): v for k, v in get_http_headers(include={CONTEXT_VERSION_HEADER.lower()}).items()}
        if CONTEXT_VERSION_HEADER.lower() in incoming:
            headers[CONTEXT_VERSION_HEADER] = incoming[CONTEXT_VERSION_HEADER.lower()]
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=5, trust_env=False,
                                         follow_redirects=False) as client:
                response = await client.post(self.origin + path, json=payload, headers=headers)
            if response.status_code in (401, 403):
                raise ToolError("AUTH_REQUIRED: 로그인 인증을 확인해 주세요.")
            if response.status_code == 404:
                raise ToolError("OWNERSHIP_NOT_FOUND: 상담 또는 제품을 찾을 수 없습니다.")
            if response.status_code == 409:
                raise ToolError("CONTEXT_STALE: 상담 상태를 새로 확인해 주세요.")
            if response.status_code == 400:
                raise ToolError("INVALID_ARGUMENT: 확인된 입력과 허용 후보를 사용해 주세요.")
            response.raise_for_status()
            return response.json()
        except ToolError:
            raise
        except (httpx.HTTPError, ValueError) as error:
            # Never return upstream bodies, request headers, or credentials to the client.
            raise ToolError("TOOL_UNAVAILABLE: 근거 데이터를 확인하지 못했습니다.") from error

    async def call_tool(self, name: str, conversation_id: int, **args: Any) -> Envelope:
        if conversation_id < 1:
            raise ToolError("INVALID_ARGUMENT: 상담 ID가 필요합니다.")
        started = time.monotonic()
        try:
            value = await self.post("/api/tools/" + name, {"conversation_id": conversation_id, **args})
            try:
                result = Envelope.model_validate(value)
            except ValueError as error:
                raise ToolError("INVALID_RESULT: 도구 결과 계약을 확인해 주세요.") from error
        except ToolError as error:
            # Only the error code is logged, never arguments, tokens or backend bodies.
            LOG.info("homecare_tool_call trace_id=none tool=%s status=ERROR error_code=%s elapsed_ms=%d",
                     name, str(error).split(":", 1)[0], _elapsed(started))
            raise
        LOG.info("homecare_tool_call trace_id=%s tool=%s status=%s elapsed_ms=%d",
                 result.meta.get("trace_id", "none"), name, result.status, _elapsed(started))
        return result


def _elapsed(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
