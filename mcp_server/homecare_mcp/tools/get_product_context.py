from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def get_product_context(conversation_id: int) -> Envelope:
        """본인의 등록 제품과 확정 상태를 조회한다. 자동 선택·등록·수정하지 않는다."""
        return await backend.call_tool("get_product_context", conversation_id)
