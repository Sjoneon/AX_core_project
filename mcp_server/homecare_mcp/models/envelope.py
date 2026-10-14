"""Common result contract shared by every HomeCare MCP tool."""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

ToolStatus = Literal["OK", "NEEDS_INPUT", "PARTIAL", "NO_DATA", "NOT_VERIFIED", "BLOCKED_BY_SAFETY", "ERROR"]
Confidence = Literal["HIGH", "MEDIUM", "LOW", "UNDETERMINED"]


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
