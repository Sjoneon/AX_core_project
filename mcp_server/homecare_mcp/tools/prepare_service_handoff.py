from __future__ import annotations

from typing import Literal

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS

HandoffReason = Literal["RISK", "NOT_RESOLVED", "UNCLASSIFIED", "OUT_OF_SCOPE", "INSUFFICIENT_EVIDENCE", "NOT_VERIFIED", "NO_SELF_CARE", "PRECONDITION_UNCONFIRMED"]


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def prepare_service_handoff(conversation_id: int, reason: HandoffReason) -> Envelope:
        """확인된 상담 사실만으로 복사할 요약과 공식 연결 경로를 준비한다. 전송·예약·전화 발신은 하지 않는다."""
        return await backend.call_tool("prepare_service_handoff", conversation_id, reason=reason)
