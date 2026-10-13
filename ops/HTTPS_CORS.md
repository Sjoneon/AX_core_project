# HTTPS와 Vercel CORS

API 주소: `https://43.202.80.116`.
프론트엔드 운영 origin: `https://homecare-omega-eight.vercel.app`.
사용자에게 별도 API 도메인이 없어 Elastic IP의 IP 인증서를 사용합니다.
기존 Vercel 프로젝트 `homecare`는 8단계에서 Git에 연결했습니다.
[운영·자동 배포 안내](OPERATIONS.md)를 참고합니다.

2026-10-12 실제 적용·검증을 완료했습니다. [릴리스 기록](STAGE6_RELEASE.json)을 참고합니다.
최초 인증서 만료는 2026-10-19 10:29:08 KST이며 갱신 시 연장됩니다.

## 인증서와 두 컨테이너

호스트의 Certbot 5.8.0으로 Let's Encrypt `shortlived` IP 인증서를 발급합니다.
프로필의 인증서 수명은 160시간이며 자동 갱신을 전제로 사용합니다.
인증서 이름은 `homecare-api`, SAN은 `IP Address:43.202.80.116`입니다.

`/etc/letsencrypt` 전체를 Nginx에 읽기 전용으로 연결해 live→archive 심볼릭 링크와
갱신된 인증서 파일을 함께 볼 수 있게 합니다. 개인 키는 EC2에만 보관하고
Git·Terraform state·Docker 이미지·S3 릴리스·Vercel에는 포함하지 않습니다.
Certbot은 호스트 프로세스이므로 상시 컨테이너는 `backend`, `nginx` 두 개입니다.

Nginx는 80·443만 호스트에 게시하며 8080은 게시하지 않습니다.
80은 ACME HTTP-01 파일을 제공하고 나머지 사용자 요청은 고정 API 주소로 308 리다이렉트합니다.
로컬 healthcheck용 `/internal-health`는 loopback에서만 허용합니다.
443에서는 TLS 1.2·1.3과 인증서 검증을 사용하며 기존 `/api/...` 경로를 유지합니다.

## 초기 발급과 갱신

`proxy/bootstrap.conf`와 `compose.acme.yaml`은 발급 전 임시 HTTP 단계에만 사용합니다.
`setup-https.sh`가 Certbot 설치·임시 프록시·IP 인증서 발급·정상 HTTPS 전환을 수행합니다.
발급된 인증서를 읽는 정상 실행은 `start-service.sh`를 사용합니다.

```bash
# 준비된 릴리스 디렉터리에서 EC2 root 권한으로 최초 실행
bash /opt/homecare/releases/<release-id>/setup-https.sh /opt/homecare/releases/<release-id>

# 일상적인 앱 배포는 main push의 GitHub Actions가 수행
# 신규 릴리스 수동 배포: 유효한 인증서가 이미 존재해야 함
bash /opt/homecare/releases/<새-release-id>/deploy-service.sh /opt/homecare/releases/<새-release-id>
```

자동 갱신은 Snap의 `snap.certbot.renew.timer`를 사용합니다.
갱신 성공 후 `/etc/letsencrypt/renewal-hooks/deploy/homecare-nginx`가
현재 릴리스의 Nginx 문법을 검사하고 reload합니다. private key는 출력하지 않습니다.
ACME 계정에 이메일을 등록하지 않습니다. 8단계 대시보드에서 인증서 남은 시간과
갱신 타이머를 확인하며 이메일·예산 알림은 사용자 요청으로 생략했습니다.

```bash
systemctl status snap.certbot.renew.timer
systemctl list-timers snap.certbot.renew.timer
/snap/bin/certbot renew --cert-name homecare-api --dry-run --run-deploy-hooks
openssl x509 -in /etc/letsencrypt/live/homecare-api/fullchain.pem -noout -dates -ext subjectAltName
```

Certbot 갱신은 CA 부하 분산을 위해 무작위 대기를 할 수 있습니다.
dry-run은 staging 인증서로 갱신 가능 여부와 hook을 검사하며 실제 운영 인증서를 대체하지 않습니다.
공인 IP가 바뀌면 인증서·Nginx API 주소·Vercel API 주소를 함께 변경하고 검증해야 합니다.

## CORS

Nginx 한 곳에서 다음을 처리합니다.

- 정확히 `https://homecare-omega-eight.vercel.app`만 허용하며 임의 origin을 반사하지 않습니다.
- 허용된 OPTIONS는 인증 전에 204로 응답합니다.
- GET·POST·DELETE·OPTIONS 및 Authorization·Content-Type을 허용합니다.
- 허용 origin의 401 등 오류 응답에도 CORS 헤더와 `Vary: Origin`을 붙입니다.
- 다른 origin·preview 주소·미지원 preflight 메서드는 403으로 거부합니다.
- 프론트엔드는 쿠키 대신 메모리의 Bearer JWT를 사용하므로 credentials 허용 헤더를 추가하지 않습니다.

## Vercel

연결 프로젝트는 `frontend/.vercel/project.json`에 있으며 인증 토큰은 저장소에 넣지 않습니다.
Vite·Node 24 설정과 기존 운영 도메인을 사용합니다.
Production의 공개 Config `VITE_API_BASE_URL=https://43.202.80.116`만 등록합니다.
API 주소는 빌드에 포함되므로 주소 변경 시 새 배포가 필요합니다.

```bash
# Git 연동 후에는 저장소 루트에서 실행; 기존 값은 env update로 변경
vercel env add VITE_API_BASE_URL production --value https://43.202.80.116 --no-sensitive --yes
vercel deploy --prod --skip-domain --yes
# HTTPS·CORS와 새 빌드가 READY임을 확인한 뒤
vercel promote <새-배포-URL> --yes
```

6단계의 최초 CLI 배포는 Git 연결 없이 진행했습니다.
현재는 Git 연결과 동일 커밋의 CI·백엔드 배포 검사를 사용합니다.
Preview에는 API 주소를 자동 등록하지 않고 CORS origin도 추가하지 않습니다.

## 검증

```bash
# 로컬: Docker와 openssl이 필요하며 인증서·컨테이너는 임시 생성 후 제거
docker build --platform linux/amd64 -t homecare-nginx:stage6 proxy
python3 ops/test-proxy.py

# 외부: 인증서 검증을 끄지 않고 HTTPS 확인
curl -fsS https://43.202.80.116/actuator/health
curl -i -X OPTIONS https://43.202.80.116/api/chat \
  -H 'Origin: https://homecare-omega-eight.vercel.app' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type'

# EC2: 합성 계정·상담과 backend 재생성 후 데이터·JWT 유지 검사
python3 /opt/homecare/current/verify-service.py
```

API 검증은 OpenAI 호출 없이 카테고리 선택 안내 응답으로 상담 경로를 테스트합니다.
합성 데이터만 정리하며 기존 사용자 데이터는 유지합니다.
로컬 TLS·내부 health·CORS 회귀 검사와 실제 외부 HTTPS·preflight·오류 응답·Origin 차단,
HTTPS 인증·상담·삭제·backend 교체 후 데이터·JWT 유지 검증을 통과했습니다.
Certbot 갱신 dry-run과 실제 reload hook이 성공했고 타이머가 enabled·active입니다.
Vercel 운영 빌드가 HTTPS API 주소를 포함하며 공개 페이지가 정상 표시되는 것을 확인했습니다.

참고: [IP 인증서와 Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html),
[Certbot 갱신·hook](https://eff-certbot.readthedocs.io/en/stable/using.html),
[Nginx 오류 응답 헤더](https://nginx.org/en/docs/http/ngx_http_headers_module.html),
[Vercel CLI 배포](https://vercel.com/docs/cli/deploy).
