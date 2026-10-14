from __future__ import annotations

from fastmcp import FastMCP
from fastmcp.exceptions import ToolError

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, WarrantyInput, WarrantyResult
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def check_warranty(conversation_id: int | None = None,
                             request: WarrantyInput | None = None) -> Envelope | WarrantyResult:
        """제품 전체 보증과 주요 부품별 별도 보증을 계산한다. 날짜·기간·무상 항목을 추측하지 않는다.
        Agent는 conversation_id를 사용한다. request는 사용자가 직접 확인한 보증 조회의 호환 입력이다.
        """
        if conversation_id is not None:
            if request is not None:
                raise ToolError("INVALID_ARGUMENT: 상담과 수동 입력을 함께 보내지 마세요.")
            return await backend.call_tool("check_warranty", conversation_id)
        if request is None:
            raise ToolError("INVALID_ARGUMENT: 상담 또는 확인된 보증 입력이 필요합니다.")
        return WarrantyResult.model_validate(await backend.post("/api/warranty/check", request.model_dump(mode="json")))
