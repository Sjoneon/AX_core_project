from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, Region
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def find_service_centers(conversation_id: int, region_override: Region | None = None) -> Envelope:
        """등록 제품의 설치 지역 확인 여부와 공식 출장 접수·센터 찾기 경로를 반환한다. 센터 목록은 연동되지 않았고 예약하지 않는다. 지역 변경은 사용자 선택만 사용한다."""
        return await backend.call_tool("find_service_centers", conversation_id,
                                       region_override=region_override.model_dump() if region_override else None)
