"""모델에 노출하는 도구 입력 타입(Spring에서도 호출별 재검증)"""
from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Field

# Spring에 모델 코드 원문 전달(공백 제외 영문·숫자·.-/ 1~40자)
# Spring에서 대문자로 정규화하고 RD21ESE.AKOR 등의 접미사 보존
ModelCode = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^\s*[A-Za-z0-9./-]{1,40}\s*$",
                                 description="고객이 말한 모델 코드 원문. 자르거나 고치지 않는다.")]
ProductCategoryInput = Literal["WASHER", "DRYER", "REFRIGERATOR", "DISHWASHER",
                               "세탁기", "건조기", "냉장고", "김치냉장고", "식기세척기"]
TimeSlot = Literal["WEEKDAY", "NIGHT_HOLIDAY", "평일", "야간·휴일"]
Month = Annotated[int, Field(ge=1, le=12, strict=True, description="방문 예정 월(1~12). 고객이 말했을 때만 넣는다.")]
