from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def get_repair_candidates(conversation_id: int) -> Envelope:
        """매핑·허용 부품 그룹과 REFERENCE 후기만 조회한다. 검증 후기·국내 호환 미확인 전용 부품은 제외한다."""
        return await backend.call_tool("get_repair_candidates", conversation_id)
