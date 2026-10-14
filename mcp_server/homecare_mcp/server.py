"""인증된 읽기 전용 HomeCare MCP 도구 9개 구성(데이터·도메인 로직은 Spring에서 처리)"""
from __future__ import annotations

from collections.abc import Callable

import httpx
from fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import JSONResponse

from homecare_mcp import log_privacy
from homecare_mcp.auth import request_token
from homecare_mcp.backend_client import BackendClient, resolve_backend_origin
from homecare_mcp.tools import TOOL_NAMES, register_tools


def create_server(backend_url: str | None = None, token_provider: Callable[[], str] = request_token,
                  transport: httpx.AsyncBaseTransport | None = None) -> FastMCP:
    """로그 보호와 읽기 전용 도구를 등록한 MCP 서버 생성

    backend_url이 없으면 환경 변수 또는 기본 백엔드 주소를 사용합니다.
    transport는 HTTPX 전송 구현을 대체하는 입력입니다(개발/테스트용).
    /health는 프로세스 상태와 도구 수만 반환합니다.
    """
    # 로그 보호와 요청별 백엔드 클라이언트 구성
    log_privacy.install()
    backend = BackendClient(resolve_backend_origin(backend_url), token_provider, transport)
    mcp = FastMCP("HomeCare AI", mask_error_details=True)

    @mcp.custom_route("/health", methods=["GET"])
    async def health(request: Request):
        """MCP 프로세스 상태와 등록 도구 수 조회"""
        return JSONResponse({"status": "UP", "service": "homecare-mcp", "tool_count": len(TOOL_NAMES)})

    # 읽기 전용 도구 9개 등록
    register_tools(mcp, backend)
    return mcp
