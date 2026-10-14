"""실제 Streamable HTTP MCP 경로의 도구·인증·소유권 확인(LLM 호출 제외)

토큰과 상담 ID는 HOMECARE_SMOKE_* 환경 변수로만 전달합니다.
실행: python -m scripts.smoke_client [--verbose]
"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
import json
import os
import re
from typing import Any
from urllib.parse import urlsplit

from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport

from homecare_mcp.tools import TOOL_NAMES

CONFIDENCES = {"HIGH", "MEDIUM", "LOW", "UNDETERMINED"}
STATUSES = {"OK", "NEEDS_INPUT", "PARTIAL", "NO_DATA", "NOT_VERIFIED", "BLOCKED_BY_SAFETY", "ERROR"}
ERROR_CODE = re.compile(r"\b([A-Z][A-Z_]{2,}):")
SENSITIVE_KEYS = {
    "authorization", "token", "accesstoken", "password", "secret", "api_key",
    "conversation_id", "conversationid", "foreign_conversation_id",
}


@dataclass(frozen=True)
class Settings:
    """로컬 MCP 검증 환경 설정(인증값 출력 제외)"""

    token: str
    conversation_id: int
    foreign_conversation_id: int
    url: str

    @classmethod
    def from_environment(cls) -> Settings:
        """환경 변수의 인증값·상담 ID·로컬 주소 검증"""
        token = os.environ.get("HOMECARE_SMOKE_TOKEN", "").strip()
        if not token:
            raise ValueError("HOMECARE_SMOKE_TOKEN 설정이 필요합니다.")
        try:
            own = int(os.environ["HOMECARE_SMOKE_CONVERSATION_ID"])
            foreign = int(os.environ["HOMECARE_SMOKE_FOREIGN_CONVERSATION_ID"])
        except (KeyError, ValueError) as error:
            raise ValueError("본인·타 사용자 상담 ID 환경 변수에 양의 정수를 설정해 주세요.") from error
        if min(own, foreign) < 1 or own == foreign:
            raise ValueError("본인·타 사용자 상담 ID는 서로 다른 양의 정수여야 합니다.")
        url = os.environ.get("HOMECARE_SMOKE_URL", "http://127.0.0.1:8000/mcp")
        parsed = urlsplit(url)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("검증 주소는 로컬 HTTP MCP 주소만 사용할 수 있습니다.")
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path != "/mcp":
            raise ValueError("검증 주소에는 인증값·쿼리 없이 /mcp 경로를 지정해 주세요.")
        return cls(token, own, foreign, url)


def redact(value: Any, settings: Settings, path: tuple[str, ...] = ()) -> Any:
    """상세 출력에서도 인증값과 상담 ID 제외"""
    if isinstance(value, dict):
        return {
            key: "<redacted>" if key.lower() in SENSITIVE_KEYS
            or (key == "id" and any(part in {"snapshot", "conversation"} for part in path))
            else redact(item, settings, (*path, key))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact(item, settings, path) for item in value]
    if isinstance(value, str):
        return value.replace(settings.token, "<redacted>")
    return value


def envelope(result: Any) -> dict[str, Any]:
    """FastMCP의 직접 응답과 result wrapper에서 공통 envelope 조회"""
    raw = result.structured_content
    if raw is None:
        for item in result.content:
            if getattr(item, "type", None) == "text":
                try:
                    raw = json.loads(item.text)
                except (ValueError, TypeError):
                    continue
                break
    if isinstance(raw, dict) and isinstance(raw.get("result"), dict):
        raw = raw["result"]
    if not isinstance(raw, dict):
        raise ValueError("CONTRACT_MISMATCH")
    if raw.get("status") not in STATUSES or raw.get("confidence") not in CONFIDENCES:
        raise ValueError("CONTRACT_MISMATCH")
    if raw.get("independent_action_allowed") is not False:
        raise ValueError("CONTRACT_MISMATCH")
    if not isinstance(raw.get("source"), dict) or not isinstance(raw.get("caveats"), list):
        raise ValueError("CONTRACT_MISMATCH")
    if not isinstance(raw.get("meta", {}).get("context_version"), int):
        raise ValueError("CONTRACT_MISMATCH")
    return raw


class SmokeRun:
    """도구 호출 결과와 최소 검증 요약 수집"""

    def __init__(self, settings: Settings, verbose: bool):
        self.settings = settings
        self.verbose = verbose
        self.rows: list[tuple[str, str, str, str, str]] = []

    async def call(self, client: Client, name: str, args: dict[str, Any], *, case: str | None = None,
                   expected_error: str | None = None) -> dict[str, Any] | None:
        """인증값·상담 ID 출력 없이 실제 도구 응답 검증"""
        label = case or name
        try:
            result = await client.call_tool(name, args, raise_on_error=False)
            if result.is_error:
                text = " ".join(item.text for item in result.content if getattr(item, "type", None) == "text")
                match = ERROR_CODE.search(text)
                code = match[1] if match else "TOOL_ERROR"
                self.rows.append((label, "ERROR", "-", code, "PASS" if code == expected_error else "FAIL"))
                return None
            raw = envelope(result)
            code = (raw.get("error") or {}).get("code", "-")
            passed = code == expected_error if expected_error else raw["status"] != "ERROR"
            self.rows.append((label, raw["status"], raw["confidence"], code, "PASS" if passed else "FAIL"))
            if self.verbose:
                print(json.dumps({"tool": label, "result": redact(raw, self.settings)}, ensure_ascii=False, indent=2))
            return raw
        except Exception:
            self.rows.append((label, "ERROR", "-", "CLIENT_OR_CONTRACT_ERROR", "FAIL"))
            return None

    def require(self, condition: bool) -> None:
        """직전 호출의 동작 조건이 맞지 않으면 실패 표시"""
        if not condition and self.rows[-1][-1] == "PASS":
            label, status, confidence, _, _ = self.rows[-1]
            self.rows[-1] = (label, status, confidence, "EXPECTED_RESULT_MISMATCH", "FAIL")

    async def run(self) -> int:
        """도구 9개와 무인증·타 사용자 상담 거부 확인"""
        headers = {"Authorization": "Bearer " + self.settings.token}
        transport = StreamableHttpTransport(self.settings.url, headers=headers)
        owned = {"conversation_id": self.settings.conversation_id}
        try:
            async with Client(transport, timeout=15) as client:
                tools = await client.list_tools()
                names = {tool.name for tool in tools}
                valid = names == set(TOOL_NAMES) and all(
                    tool.annotations is not None and tool.annotations.readOnlyHint is True for tool in tools
                )
                self.rows.append(("discovery (9 / readOnlyHint)", "OK" if valid else "ERROR", "-", "-", "PASS" if valid else "FAIL"))
                if not valid:
                    return self.finish()

                raw = await self.call(client, "lookup_model", {**owned, "model_code": "FX24ENER"}, case="lookup_model / EXACT")
                self.require(raw is not None and raw.get("data", {}).get("match_status") == "EXACT")
                raw = await self.call(client, "lookup_model", {**owned, "model_code": "RD21ESE.AKOR"}, case="lookup_model / suffix")
                self.require(raw is not None and raw.get("data", {}).get("match_status") == "NOT_FOUND"
                             and raw.get("data", {}).get("normalized_code") == "RD21ESE.AKOR")
                await self.call(client, "get_product_context", owned)
                await self.call(client, "search_knowledge", {**owned, "query": "OE 배수 오류", "topic": "MANUAL", "top_k": 3, "model_code": "FX24ENER"})
                await self.call(client, "get_diagnostic_plan", owned)
                candidates = await self.call(client, "get_repair_candidates", owned)
                data = (candidates or {}).get("data") or {}
                allowed = data.get("mapped_candidates") or data.get("allowed_candidates") or []
                selected = [item["id"] for item in allowed[:2] if isinstance(item, dict) and "id" in item]
                if selected and data.get("candidate_set_id"):
                    await self.call(client, "estimate_repair_cost", {
                        **owned, "candidate_set_id": data["candidate_set_id"], "candidate_ids": selected,
                        "confidence": 0.7, "time_slot": "WEEKDAY",
                    })
                else:
                    self.rows.append(("estimate_repair_cost", "NO_DATA", "-", "NO_ALLOWED_CANDIDATES", "SKIP"))
                await self.call(client, "check_warranty", owned)
                await self.call(client, "find_service_centers", owned)
                await self.call(client, "prepare_service_handoff", {**owned, "reason": "NOT_RESOLVED"})
                await self.call(client, "get_product_context", {"conversation_id": self.settings.foreign_conversation_id},
                                case="foreign conversation", expected_error="OWNERSHIP_NOT_FOUND")

            # 별도 세션에서 인증 헤더 누락 시 도구 호출 거부 확인
            async with Client(StreamableHttpTransport(self.settings.url), timeout=15) as client:
                await self.call(client, "get_product_context", owned, case="missing token", expected_error="AUTH_REQUIRED")
        except Exception:
            self.rows.append(("MCP connection", "ERROR", "-", "CLIENT_UNAVAILABLE", "FAIL"))
        return self.finish()

    def finish(self) -> int:
        print("도구 / 확인 항목 | status | confidence | 오류 코드 | 결과")
        print("-" * 96)
        for row in self.rows:
            print(" | ".join(row))
        return 1 if any(row[-1] != "PASS" for row in self.rows) else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="인증값·상담 ID를 제외한 결과 본문 출력")
    args = parser.parse_args()
    try:
        settings = Settings.from_environment()
    except ValueError as error:
        print(str(error))
        return 2
    return asyncio.run(SmokeRun(settings, args.verbose).run())


if __name__ == "__main__":
    raise SystemExit(main())
