"""HomeCare MCP 도구의 공통 응답 계약"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

ToolStatus = Literal["OK", "NEEDS_INPUT", "PARTIAL", "NO_DATA", "NOT_VERIFIED", "BLOCKED_BY_SAFETY", "ERROR"]
Confidence = Literal["HIGH", "MEDIUM", "LOW", "UNDETERMINED"]


# 근거·신뢰도·해석 제한을 포함한 공통 도구 응답
# API 스키마에 반영되는 docstring은 기존 계약 유지
class Envelope(BaseModel):
    """source names data files and fact IDs; caveats are fixed interpretation notices; warnings are situational.

    independent_action_allowed is always false: a result never approves an action, free repair or part fit.
    """
    model_config = ConfigDict(extra="forbid")
    status: ToolStatus
    data: dict[str, Any] | None
    evidence: list[dict[str, Any]]
    missing_fields: list[str]
    warnings: list[str]
    error: dict[str, Any] | None
    meta: dict[str, Any]
    source: dict[str, Any]
    confidence: Confidence
    caveats: list[str]
    independent_action_allowed: Literal[False]
