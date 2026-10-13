# 두 컨테이너의 운영 배포

대상: 서울 리전 EC2 `i-0223afa15057a76b2`, Elastic IP `43.202.80.116`.
현재 API는 IP 인증서 기반 `https://43.202.80.116`을 사용합니다.
[HTTPS·CORS 안내](HTTPS_CORS.md)에 인증서 발급·자동 갱신·Vercel 연결을 기록했습니다.

2026-10-12, 두 컨테이너 상시 배포와 검증을 완료했습니다.
6단계에서 배포한 릴리스 ID는 `manual-https-20261012-f4c0a29c11`이며
[릴리스 기록](STAGE6_RELEASE.json)에 ECR digest·S3 파일·SHA-256을 보관합니다.

## 실행 구성

`compose.prod.yaml`의 프로젝트 이름은 `homecare`이며 서비스는 `backend`, `nginx` 두 개입니다.
기본 Compose 네트워크 안에서 Nginx가 앱으로 요청을 전달합니다.

| 항목 | backend | nginx |
|---|---|---|
| 이미지 | ECR backend digest | ECR nginx digest |
| 호스트 포트 | 없음 | 80·443 |
| 메모리 상한 | 1GiB, JVM heap 최대 65% | 128MiB |
| 실행 사용자 | 10001:10001 | Nginx 기본 master/worker |
| 루트 파일시스템 | 읽기 전용, `/tmp`는 tmpfs | 읽기 전용, cache·PID는 tmpfs |
| 재시작 | unless-stopped | unless-stopped |
| healthcheck | 앱 health HTTP 상태 | 로컬 전용 `/internal-health`의 프록시 경유 앱 health |
| CloudWatch | `/homecare/prod/backend` | `/homecare/prod/nginx` |

로그는 EC2 역할로 전송하고 로컬 캐시는 파일당 10MB·최대 3개입니다.
비동기 전송 버퍼는 4MB이며 CloudWatch 전송이 지연되면 버퍼를 넘는 로그는 유실될 수 있습니다.
로그 그룹 보존기간은 14일입니다.
Docker는 프로세스 종료 시 재시작하며 healthcheck가 unhealthy인 것만으로 재시작하지는 않습니다.
알림·운영 대응은 후속 운영 단계에서 구성합니다.

앱은 SSM에서 준비한 `/opt/homecare/config/runtime.env`만 환경 파일로 읽고
RDS PKCS12 truststore를 읽기 전용으로 연결합니다. Nginx에는 앱 비밀값을 전달하지 않습니다.
AWS 장기 키·RDS 관리자 비밀번호는 컨테이너에 전달하지 않습니다.
Nginx에만 `/etc/letsencrypt`와 ACME webroot를 읽기 전용으로 연결합니다.

## 릴리스 파일과 시작

수동 첫 배포는 불변 ECR 태그로 두 이미지를 올리고 실제 배포에는 digest를 사용합니다.
기본 소스 커밋과 작업 폴더의 새 설정을 구분해 릴리스 기록에 남깁니다.
코드·이미지 참조만 아래 경로에 보관하며 비밀값은 묶음에 넣지 않습니다.

```text
S3: releases/<release-id>/<릴리스 기록의 파일명>
EC2: /opt/homecare/releases/<release-id>/
  compose.prod.yaml
  images.env            # RELEASE_ID, BACKEND_IMAGE, NGINX_IMAGE; 비밀값 없음
  release.json          # 소스 기준 커밋·이미지 digest
  start-service.sh
  write-runtime-env.py
  verify-service.py
  setup-https.sh / renew-nginx.sh / compose.acme.yaml / bootstrap.conf
/opt/homecare/current -> 정상 기동을 확인한 릴리스 디렉터리
```

EC2 역할로 S3의 릴리스 파일을 내려받아 기록한 SHA-256을 확인하고 압축을 풉니다.
최초 배포에서 설정 작성 스크립트를 `/usr/local/lib/homecare/write-runtime-env.py`에 설치합니다.
그 후 EC2에서 root 권한으로 실행합니다.

```bash
bash /opt/homecare/releases/<release-id>/start-service.sh /opt/homecare/releases/<release-id>
```

시작 스크립트는 운영 환경 파일을 갱신하고 Compose 문법을 검사합니다.
EC2 역할로 ECR에 로그인하고 이미지 pull 후 두 서비스가 healthy가 될 때까지 기다립니다.
`--remove-orphans`로 이름 변경 전의 `app` 등 현재 구성에서 빠진 서비스 컨테이너를 정리합니다.
Nginx 설정 검사·reload와 프록시 health 확인 후에만 `current` 링크를 갱신합니다.
Docker 로그인 파일은 임시 디렉터리에 두고 종료 시 삭제합니다.

이 단계의 시작 스크립트는 유효한 인증서가 이미 존재하는 수동 배포용입니다.
7단계 자동 배포는 [CI/CD 안내](CI_CD.md)의 `deploy-service.sh`를 사용하며
배포 잠금·설정 스냅샷·실패 복원을 제공합니다.
앱 설정을 포함할 수 있는 `docker compose config`나 전체 `docker inspect` 출력을
채팅·CI 로그에 붙이지 않고 필요한 상태 필드만 조회합니다.

## 상태 확인과 검증

```bash
curl -fsS https://43.202.80.116/actuator/health
# 정상 응답: status가 UP (liveness/readiness 그룹 이름이 함께 포함될 수 있음)

# EC2에서 서비스 상태 확인
docker compose --project-name homecare --env-file /opt/homecare/current/images.env \
  -f /opt/homecare/current/compose.prod.yaml ps

# EC2에서 합성 데이터와 앱 교체를 포함한 검증
python3 /opt/homecare/current/verify-service.py
```

검증 스크립트는 컨테이너 수·포트·메모리·로그·재시작 정책과 프록시 경로를 확인합니다.
인증서를 검증하는 HTTPS로 합성 사용자 회원가입·로그인·상담·내역 조회·삭제를 테스트하고,
앱을 재생성한 뒤 Nginx reload·데이터와 JWT 유지 여부를 확인합니다.
제품 카테고리 없는 문의의 로컬 안내 응답을 사용하므로 OpenAI를 호출하지 않습니다.
검증 사용자와 그 데이터만 삭제하며 두 서비스 컨테이너는 유지합니다.
이 명령은 앱 교체 중 짧은 중단이 발생하므로 실습 또는 정해진 점검 시간에 실행합니다.

외부 health·인증 없는 API 응답과 8080·3306 접근 차단은 로컬에서 별도로 확인합니다.
운영 프론트엔드는 `https://homecare-omega-eight.vercel.app`이며 해당 origin만 CORS에 허용합니다.

실제 API·앱 재생성·Nginx reload 검증을 통과했고 합성 데이터는 정리했습니다.
동일 릴리스 시작 시 두 컨테이너 ID가 유지됐으며, EC2를 실제 재부팅한 뒤에도
두 컨테이너가 자동으로 healthy가 되고 외부 health가 복구됐습니다.
외부 8080·3306 접속은 차단됐고 두 로그 그룹에 운영 로그가 전송됐습니다.
앱 교체 중에는 짧게 502가 발생할 수 있습니다.

서비스명 변경 후 `homecare-backend-1`, `homecare-nginx-1`만 실행되며,
변경 전 JWT와 상담 데이터가 유지되는 것을 확인했습니다.

[Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/),
[CloudWatch 로그 드라이버](https://docs.docker.com/engine/logging/drivers/awslogs/),
[로그 캐시 제한](https://docs.docker.com/engine/logging/dual-logging/).
