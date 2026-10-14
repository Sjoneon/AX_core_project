# Exact version and multi-arch digest (Docker Hub python:3.12.15-slim-bookworm, 2026-10-06).
FROM python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --no-create-home homecare
COPY homecare_mcp ./homecare_mcp
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOMECARE_MCP_TRANSPORT=http HOMECARE_MCP_HOST=0.0.0.0
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=15s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["python", "-m", "homecare_mcp"]
