from __future__ import annotations

import os

from fastmcp.exceptions import ToolError
from fastmcp.server.dependencies import get_http_headers


def request_token() -> str:
    """요청별 HomeCare JWT 조회

    Authorization의 Bearer 토큰을 우선 사용합니다.
    stdio 모드에서만 HOMECARE_USER_TOKEN을 대체 입력으로 사용합니다(선택사항).
    인증값이 없으면 ToolError를 발생시킵니다.
    """
    # 도구 인자와 분리된 요청별 Authorization 조회
    headers = {key.lower(): value for key, value in get_http_headers(include={"authorization"}).items()}
    authorization = headers.get("authorization", "")
    if authorization.startswith("Bearer ") and authorization[7:].strip():
        return authorization[7:].strip()
    # stdio 모드에서만 로컬 사용자 토큰 사용(HTTP 공유 토큰 제외)
    if os.environ.get("HOMECARE_MCP_TRANSPORT", "stdio") == "stdio":
        token = os.environ.get("HOMECARE_USER_TOKEN", "").strip()
        if token:
            return token
    raise ToolError("AUTH_REQUIRED: HomeCare 로그인 인증이 필요합니다.")
