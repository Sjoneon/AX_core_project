"""Entry point: python -m homecare_mcp"""
import logging
import os

from homecare_mcp.server import create_server


def _names(variable: str) -> list[str]:
    return [value.strip() for value in os.environ.get(variable, "").split(",") if value.strip()]


def http_security() -> dict:
    """Strict Host/Origin validation (DNS rebinding): 127.0.0.1/localhost plus HOMECARE_MCP_ALLOWED_HOSTS
    (the compose service name), and no browser origins unless HOMECARE_MCP_ALLOWED_ORIGINS lists one."""
    return {"host_origin_protection": True, "allowed_hosts": _names("HOMECARE_MCP_ALLOWED_HOSTS"),
            "allowed_origins": _names("HOMECARE_MCP_ALLOWED_ORIGINS")}


def main() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    logging.getLogger("homecare_mcp").addHandler(handler)
    logging.getLogger("homecare_mcp").setLevel(logging.INFO)
    mcp = create_server()
    mode = os.environ.get("HOMECARE_MCP_TRANSPORT", "stdio")
    if mode == "stdio":
        mcp.run(transport="stdio")
    elif mode == "http":
        mcp.run(transport="http", host=os.environ.get("HOMECARE_MCP_HOST", "127.0.0.1"),
                port=int(os.environ.get("HOMECARE_MCP_PORT", "8000")), **http_security())
    else:
        raise SystemExit("HOMECARE_MCP_TRANSPORT must be stdio or http")


if __name__ == "__main__":
    main()
