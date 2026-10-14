"""HomeCare MCP 실행 진입점: python -m homecare_mcp"""
import logging
import os

from homecare_mcp.server import create_server


def _names(variable: str) -> list[str]:
    return [value.strip() for value in os.environ.get(variable, "").split(",") if value.strip()]


def http_security() -> dict:
    """Host/Origin 검증 설정 조회(DNS rebinding 방지)

    HOMECARE_MCP_ALLOWED_HOSTS와 HOMECARE_MCP_ALLOWED_ORIGINS의 허용 목록을 반영합니다.
    """
    return {"host_origin_protection": True, "allowed_hosts": _names("HOMECARE_MCP_ALLOWED_HOSTS"),
            "allowed_origins": _names("HOMECARE_MCP_ALLOWED_ORIGINS")}


def main() -> None:
    """로그 설정 후 stdio 또는 HTTP 모드로 MCP 서버 실행"""
    # MCP 호출 로그 출력 형식 설정
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    logging.getLogger("homecare_mcp").addHandler(handler)
    logging.getLogger("homecare_mcp").setLevel(logging.INFO)
    mcp = create_server()
    # 실행 모드 조회(기본값 stdio)
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
