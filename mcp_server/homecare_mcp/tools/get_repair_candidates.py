from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def get_repair_candidates(conversation_id: int) -> Envelope:
        """제조사 근거의 원인 후보 최대 2개, 비용에 연결할 supported_candidates와 허용 부품 그룹을 조회한다.
        명시적 부품 비용 문의(USER_PART_REQUEST)는 고장 진단과 구분한다. 후보 묶음은 상담·제품·자료 버전에 묶인다.
        REFERENCE 후기만 사용하고 VALIDATION·다른 모델·미검증 자료는 근거에서 제외한다.
        """
        return await backend.call_tool("get_repair_candidates", conversation_id)
