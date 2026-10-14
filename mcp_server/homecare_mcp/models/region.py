from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class Region(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sido: str = Field(min_length=1, max_length=20)
    sigungu: str = Field(min_length=1, max_length=30)
