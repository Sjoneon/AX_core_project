from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def get_diagnostic_plan(conversation_id: int) -> Envelope:
        """확정 제품의 증상·오류별 자가검진 계획을 조회한다. 위험 또는 실행 미승인 조치를 차단한다."""
        return await backend.call_tool("get_diagnostic_plan", conversation_id)
