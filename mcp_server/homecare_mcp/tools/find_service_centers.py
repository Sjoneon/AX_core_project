from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, Region
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def find_service_centers(conversation_id: int, region_override: Region | None = None) -> Envelope:
        """제품 배송지의 시·군·구→시·도 센터 최대 3곳과 공식 경로를 조회한다. 지역 변경은 사용자 선택만 사용한다."""
        return await backend.call_tool("find_service_centers", conversation_id,
                                       region_override=region_override.model_dump() if region_override else None)
