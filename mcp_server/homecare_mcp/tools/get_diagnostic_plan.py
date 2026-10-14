from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def get_diagnostic_plan(conversation_id: int) -> Envelope:
        """확정 제품의 증상·오류별 검토된 자가검진 계획을 조회한다.
        선행 조건은 NEEDS_INPUT 질문으로 한 번에 하나씩 확인하며 모두 충족되기 전에는 단계를 반환하지 않는다.
        승인된 현재 단계만 반환하고 해결·모든 단계 실패·조건 미충족·실행 미승인·조치 없음을 구분한다.
        읽기 전용 조회이며 사용자 응답과 상태 갱신은 Spring 상담 API에서 처리한다.
        """
        return await backend.call_tool("get_diagnostic_plan", conversation_id)
