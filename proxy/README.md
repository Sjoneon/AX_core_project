# Nginx 프록시

공식 Nginx 1.30.5 Alpine 이미지를 digest로 고정하고 `default.conf`를 포함합니다.
운영 배포는 `../ops/compose.prod.yaml`을 사용합니다.

| 경로 | 동작 |
|---|---|
| `/api/...` | 경로를 유지해 `backend:8080`에 전달 |
| `/actuator/health` | 앱 health만 전달, 접근 로그 제외 |
| 그 외 | 404 |

5단계는 HTTP 80만 제공합니다. API 도메인·인증서·443·HTTP 리다이렉트와
CORS origin 허용은 6단계에서 적용합니다. React 파일은 Vercel에서 제공합니다.

앱 주소는 Docker DNS `127.0.0.11`에서 10초 단위로 갱신합니다.
앱 컨테이너 교체 후에도 주소를 다시 찾으며 시작 스크립트에서 설정 검사와 reload도 수행합니다.
프록시 read/send timeout은 110초입니다.
X-Forwarded-For는 실제 접속 IP로 덮어쓰고 access 로그에는 query string·헤더·본문을 넣지 않습니다.

```bash
docker build --platform linux/amd64 -t homecare-nginx:local proxy
docker run --rm --platform linux/amd64 homecare-nginx:local nginx -t
```

위 명령은 프로젝트 루트에서 실행합니다. 실제 배포 절차는
[운영 컨테이너 안내](../ops/SERVICE_DEPLOYMENT.md)를 참고합니다.

[Nginx 프록시 경로 처리](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[upstream DNS 갱신](https://nginx.org/en/docs/http/ngx_http_upstream_module.html).
