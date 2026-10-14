from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, ModelCode
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def lookup_model(conversation_id: int, model_code: ModelCode) -> Envelope:
        """고객이 말한 모델 코드를 판매 코드와 정확히 일치할 때만 조회한다. 제품 등록 전에도 쓸 수 있다.
        원문을 보존하고 접미사를 자르거나 비슷한 코드로 바꾸지 않는다. 결과는 후보이며 부품 호환·무상 보증을 뜻하지 않는다.
        """
        return await backend.call_tool("lookup_model", conversation_id, model_code=model_code)
