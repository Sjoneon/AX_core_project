"""Keep tool argument values, backend payloads and credentials out of server logs."""
from __future__ import annotations

import logging

FASTMCP_SERVER_LOGGER = "fastmcp.server.server"


class ToolLogFilter(logging.Filter):
    """FastMCP logs rejected arguments with their input values and unexpected tool errors with tracebacks.

    Customer text can be an argument value, so only the tool name and the validation error type/location are kept.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if record.msg == "Invalid arguments for tool %r: %s" and isinstance(record.args, tuple) and len(record.args) == 2:
            name, detail = record.args
            safe = ([{"type": item.get("type"), "loc": item.get("loc")} for item in detail if isinstance(item, dict)]
                    if isinstance(detail, list) else "redacted")
            record.args = (name, safe)
        elif isinstance(record.msg, str) and record.msg.startswith("Error calling tool"):
            record.exc_info = None
            record.exc_text = None
            record.stack_info = None
        return True


def install() -> None:
    logger = logging.getLogger(FASTMCP_SERVER_LOGGER)
    if not any(isinstance(existing, ToolLogFilter) for existing in logger.filters):
        logger.addFilter(ToolLogFilter())
