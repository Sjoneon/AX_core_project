from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


# 사용자가 선택한 시·도 및 시·군·구 입력 스키마
class Region(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sido: str = Field(min_length=1, max_length=20)
    sigungu: str = Field(min_length=1, max_length=30)
