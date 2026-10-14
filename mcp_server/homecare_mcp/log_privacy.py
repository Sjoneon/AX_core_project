"""FastMCP 도구 오류 로그의 입력값과 traceback 제외"""
from __future__ import annotations

import logging

FASTMCP_SERVER_LOGGER = "fastmcp.server.server"


class ToolLogFilter(logging.Filter):
    """FastMCP 도구 로그의 민감정보 제외

    입력 검증 오류에는 도구명·오류 유형·위치만 유지합니다.
    도구 호출 오류에는 예외 traceback과 stack 정보를 제외합니다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        # 입력 검증 실패 시 값 대신 오류 유형·위치만 유지
        if record.msg == "Invalid arguments for tool %r: %s" and isinstance(record.args, tuple) and len(record.args) == 2:
            name, detail = record.args
            safe = ([{"type": item.get("type"), "loc": item.get("loc")} for item in detail if isinstance(item, dict)]
                    if isinstance(detail, list) else "redacted")
            record.args = (name, safe)
        # 도구 호출 오류의 traceback·stack 정보 제외
        elif isinstance(record.msg, str) and record.msg.startswith("Error calling tool"):
            record.exc_info = None
            record.exc_text = None
            record.stack_info = None
        return True


def install() -> None:
    """FastMCP 로그 필터 등록(중복 등록 제외)"""
    logger = logging.getLogger(FASTMCP_SERVER_LOGGER)
    if not any(isinstance(existing, ToolLogFilter) for existing in logger.filters):
        logger.addFilter(ToolLogFilter())
