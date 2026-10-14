from __future__ import annotations

import os

from fastmcp.exceptions import ToolError
from fastmcp.server.dependencies import get_http_headers


def request_token() -> str:
    # Authorization is request-scoped and never part of the model's tool arguments.
    headers = {key.lower(): value for key, value in get_http_headers(include={"authorization"}).items()}
    authorization = headers.get("authorization", "")
    if authorization.startswith("Bearer ") and authorization[7:].strip():
        return authorization[7:].strip()
    # A stdio process is private to one local user. HTTP mode must never use a shared user token.
    if os.environ.get("HOMECARE_MCP_TRANSPORT", "stdio") == "stdio":
        token = os.environ.get("HOMECARE_USER_TOKEN", "").strip()
        if token:
            return token
    raise ToolError("AUTH_REQUIRED: HomeCare 로그인 인증이 필요합니다.")
