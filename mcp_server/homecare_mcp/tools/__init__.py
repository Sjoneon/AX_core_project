"""One module per MCP tool. Registration order is the discovery order exported to the contract."""
from __future__ import annotations

from fastmcp import FastMCP

from homecare_mcp.backend_client import BackendClient
from homecare_mcp.tools import (
    check_warranty,
    estimate_repair_cost,
    find_service_centers,
    get_diagnostic_plan,
    get_product_context,
    get_repair_candidates,
    lookup_model,
    prepare_service_handoff,
    search_knowledge,
)

TOOL_MODULES = (
    lookup_model,
    search_knowledge,
    get_product_context,
    get_diagnostic_plan,
    check_warranty,
    get_repair_candidates,
    estimate_repair_cost,
    find_service_centers,
    prepare_service_handoff,
)
TOOL_NAMES = tuple(module.__name__.rsplit(".", 1)[-1] for module in TOOL_MODULES)


def register_tools(mcp: FastMCP, backend: BackendClient) -> None:
    for module in TOOL_MODULES:
        module.register(mcp, backend)
