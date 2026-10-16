# Nginx 프록시

공식 Nginx 1.30.5 Alpine 이미지를 digest로 고정하고 `default.conf`를 포함합니다.
운영 배포는 `../ops/compose/compose.prod.yaml`을 사용합니다.

| 경로 | 동작 |
|---|---|
| `/api/tools...` | 404. MCP 컨테이너만 내부 네트워크(`backend:8080`)로 호출 |
| `/api/...` | 경로를 유지해 `backend:8080`에 전달. IP별 요청 제한: `POST /api/chat` 분당 30회(burst 10), `/api/auth/` 분당 10회(burst 5), 나머지 초당 10회(burst 30), 초과 시 429 |
| `/actuator/health` | 앱 health만 전달, 접근 로그 제외 |
| 그 외 | 404 |

현재 API는 `https://43.202.80.116`의 IP 인증서를 사용합니다.
80은 ACME 인증 경로를 제공하고 사용자 요청을 HTTPS로 전환하며 443이 API를 제공합니다.
`https://homecare-omega-eight.vercel.app`만 CORS에 허용합니다. React 파일은 Vercel에서 제공합니다.
`bootstrap.conf`는 인증서 발급 전 임시 HTTP 설정이며 정상 배포에서는 사용하지 않습니다.
로컬 전용 `/internal-health`는 Nginx healthcheck에 사용합니다.

앱 주소는 Docker DNS `127.0.0.11`에서 10초 단위로 갱신합니다.
앱 컨테이너 교체 후에도 주소를 다시 찾으며 시작 스크립트에서 설정 검사와 reload도 수행합니다.
프록시 read/send timeout은 110초입니다.
`/api/`는 응답 버퍼링을 끄므로 스트리밍 채팅의 SSE 이벤트를 도착하는 대로 전달합니다.
X-Forwarded-For는 실제 접속 IP로 덮어쓰고 access 로그에는 query string·헤더·본문을 넣지 않습니다.

```bash
docker build --platform linux/amd64 -t homecare-nginx:stage6 proxy
python3 ops/checks/test-proxy.py
```

위 명령은 프로젝트 루트에서 실행합니다. 실제 배포 절차는
[운영 컨테이너 안내](../ops/docs/service_deployment.md)와
[HTTPS·인증서 갱신 안내](../ops/docs/https_cors.md)를 참고합니다.

[Nginx 프록시 경로 처리](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[upstream DNS 갱신](https://nginx.org/en/docs/http/ngx_http_upstream_module.html).
