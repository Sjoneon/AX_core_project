"""Model-facing argument types. Spring validates the same rules again on every call."""
from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Field

# The original input is passed through unchanged so Spring can preserve it. After trimming outer spaces it must be
# 1-40 letters, digits or . - / (Spring upper-cases it and never cuts suffixes such as RD21ESE.AKOR).
ModelCode = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^\s*[A-Za-z0-9./-]{1,40}\s*$",
                                 description="고객이 말한 모델 코드 원문. 자르거나 고치지 않는다.")]
ProductCategoryInput = Literal["WASHER", "DRYER", "REFRIGERATOR", "DISHWASHER",
                               "세탁기", "건조기", "냉장고", "김치냉장고", "식기세척기"]
TimeSlot = Literal["WEEKDAY", "NIGHT_HOLIDAY", "평일", "야간·휴일"]
Month = Annotated[int, Field(ge=1, le=12, strict=True, description="방문 예정 월(1~12). 고객이 말했을 때만 넣는다.")]
