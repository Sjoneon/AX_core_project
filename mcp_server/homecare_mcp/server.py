"""Nine authenticated, read-only HomeCare MCP tools. Data and domain logic live in Spring."""
from __future__ import annotations

from collections.abc import Callable

import httpx
from fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import JSONResponse

from homecare_mcp import log_privacy
from homecare_mcp.auth import request_token
from homecare_mcp.backend_client import BackendClient, resolve_backend_origin
from homecare_mcp.tools import TOOL_NAMES, register_tools


def create_server(backend_url: str | None = None, token_provider: Callable[[], str] = request_token,
                  transport: httpx.AsyncBaseTransport | None = None) -> FastMCP:
    log_privacy.install()
    backend = BackendClient(resolve_backend_origin(backend_url), token_provider, transport)
    mcp = FastMCP("HomeCare AI", mask_error_details=True)

    @mcp.custom_route("/health", methods=["GET"])
    async def health(request: Request):
        return JSONResponse({"status": "UP", "service": "homecare-mcp", "tool_count": len(TOOL_NAMES)})

    register_tools(mcp, backend)
    return mcp
