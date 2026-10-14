from __future__ import annotations

from typing import Literal

from fastmcp import FastMCP
from pydantic import Field

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, ModelCode
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def search_knowledge(conversation_id: int, query: str = Field(min_length=1, max_length=300),
                               topic: Literal["MANUAL", "WARRANTY", "SAFETY", "SERVICE"] = "MANUAL",
                               top_k: int = Field(default=3, ge=1, le=5),
                               model_code: ModelCode | None = None) -> Envelope:
        """상담 모델과 주제로 제조사 근거 후보를 찾는다. 안전 주의 문서를 먼저 돌려준다.
        model_code가 없으면 등록 제품 모델을 쓰고, 둘 다 없으면 모델별 문서는 판정하지 않는다.
        모델 목록이 없는 문서는 '적용 미판정'이며, 검색 후보는 조치·무상 보증 승인이 아니다.
        """
        return await backend.call_tool("search_knowledge", conversation_id, query=query, topic=topic, top_k=top_k,
                                       model_code=model_code)
