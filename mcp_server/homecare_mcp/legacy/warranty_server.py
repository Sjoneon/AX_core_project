"""기존 보증 조회 MCP 서버(호환용)

새 Agent는 homecare_mcp.server를 사용합니다.
실행: python -m homecare_mcp.legacy.warranty_server
"""
from __future__ import annotations

import os
from collections.abc import Callable

import httpx
from fastmcp import FastMCP
from fastmcp.exceptions import ToolError

from homecare_mcp.auth import request_token
from homecare_mcp.backend_client import resolve_backend_origin
from homecare_mcp.models import WarrantyInput, WarrantyResult
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def create_server(backend_url: str | None = None, token_provider: Callable[[], str] = request_token,
                  transport: httpx.AsyncBaseTransport | None = None) -> FastMCP:
    """기존 보증 조회 MCP 서버 생성(호환용)"""
    upstream = resolve_backend_origin(backend_url)
    server = FastMCP("HomeCare Warranty", mask_error_details=True)

    @server.tool(name="check_warranty", annotations=READ_ONLY_ANNOTATIONS)
    async def check_warranty(request: WarrantyInput) -> WarrantyResult:
        """제품 전체와 주요 부품별 보증을 따로 계산한다. 확인되지 않은 날짜·유형은 만들지 않는다.

        제조사 정책은 서버에서 읽는다. 2년·4년 예시는 실제 정책으로 입력하지 않는다.
        주요 부품 보증은 그 부품에만 적용되며 기술료·출장비까지 자동 면제하지 않는다.
        """
        token = token_provider()
        try:
            async with httpx.AsyncClient(transport=transport, timeout=5, follow_redirects=False,
                                         trust_env=False) as client:
                response = await client.post(upstream + "/api/warranty/check",
                                             headers={"Authorization": "Bearer " + token},
                                             json=request.model_dump(mode="json"))
            if response.status_code in (401, 403):
                raise ToolError("AUTH_REQUIRED: 로그인 인증을 확인해 주세요.")
            if response.status_code == 400:
                raise ToolError("INVALID_ARGUMENT: 날짜·제품 유형·구입 조건을 확인해 주세요.")
            response.raise_for_status()
            return WarrantyResult.model_validate(response.json())
        except ToolError:
            raise
        except (httpx.HTTPError, ValueError) as error:
            # 백엔드 응답 본문·요청 헤더·인증값의 클라이언트 노출 방지
            raise ToolError("WARRANTY_UNAVAILABLE: 보증 정보를 확인하지 못했습니다.") from error

    return server


if __name__ == "__main__":
    mode = os.environ.get("HOMECARE_MCP_TRANSPORT", "stdio")
    if mode == "stdio":
        create_server().run(transport="stdio")
    elif mode == "http":
        create_server().run(transport="http", host=os.environ.get("HOMECARE_MCP_HOST", "127.0.0.1"), port=8000)
    else:
        raise SystemExit("HOMECARE_MCP_TRANSPORT must be stdio or http")
