from __future__ import annotations

from fastmcp import FastMCP
from pydantic import Field

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.models import Envelope, Month, ProductCategoryInput, TimeSlot
from homecare_mcp.tools.read_only import READ_ONLY_ANNOTATIONS


def register(mcp: FastMCP, backend: BackendClient) -> None:
    @mcp.tool(annotations=READ_ONLY_ANNOTATIONS)
    async def estimate_repair_cost(conversation_id: int, candidate_set_id: str,
                                   candidate_ids: list[str] = Field(max_length=2),
                                   confidence: float = Field(default=0, ge=0, le=1),
                                   time_slot: TimeSlot = "WEEKDAY",
                                   month: Month | None = None,
                                   product_category: ProductCategoryInput | None = None) -> Envelope:
        """서버 허용 후보의 참고용 예상 수리비 범위(중앙·80% 범위)와 신뢰도를 계산한다. 견적이 아니다.
        평일 출장비 기준이며 time_slot과 month(6~8월)는 수리비 표의 출장비 가산만 더한다. 후보들은 대안이며 합산하지 않는다.
        supported_candidates와 일치하고 확신도 0.5 이상·국내 같은 부품 사례 3건 이상인 후보만 계산한다.
        자료가 부족하면 NO_DATA와 부족한 정보를 반환한다. 가격·보증은 서버 자료에서 읽고 무상 여부는 판정하지 않는다.
        """
        return await backend.call_tool("estimate_repair_cost", conversation_id, candidate_set_id=candidate_set_id,
                                       candidate_ids=candidate_ids, confidence=confidence, time_slot=time_slot,
                                       month=month, product_category=product_category)
