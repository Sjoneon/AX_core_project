# GitHub Actions CI/CD

7단계 구현: [워크플로](../.github/workflows/ci-cd.yml),
[릴리스 게시·SSM 대기](ci-release.py), [EC2 배포·복원](deploy-service.sh).
Terraform에서 준비한 기존 역할·저장소·버킷을 사용하며 인프라 교체는 없습니다.

## 실행 흐름

PR, `main` push와 수동 실행에서 Java 21 테스트·JAR 빌드,
임시 MySQL 8.4 통합 테스트, Node 24 설치·lint·빌드,
운영 스크립트 테스트와 두 Docker 이미지 빌드·HTTPS/CORS 테스트를 실행합니다.
PR은 AWS 권한·운영 설정을 받지 않습니다. `pull_request_target`은 사용하지 않습니다.

`main`의 같은 워크플로에서 CI가 성공해야 배포 작업이 시작됩니다.
해당 실행에서 만든 JAR만 내려받고 `production` 환경의 OIDC 역할로 AWS에 접근합니다.
AWS 액세스 키를 GitHub에 복사하거나 새로운 Secrets를 등록할 필요가 없습니다.
외부 Actions는 공식 릴리스의 전체 커밋 SHA로 고정했습니다.

1. 두 ECR 저장소에 전체 커밋 SHA 태그가 있으면 재사용합니다. 없으면 이미지를 빌드·게시합니다.
2. 실제 ECR digest를 조회해 `images.env`에 기록합니다.
3. `<commit-sha>-<run-id>-<run-attempt>`별로 Compose·배포 스크립트·digest·소스 기록을 묶습니다.
4. S3 `releases/<release-id>/release.tar.gz`에 게시하고 SHA-256을 SSM 명령에 전달합니다.
5. EC2 역할로 묶음을 내려받아 해시를 확인하고 root 전용 릴리스 폴더에 풉니다.
6. 배포 잠금 획득 후 Parameter Store 설정과 이미지를 준비하고 컨테이너를 교체합니다.
7. 두 서비스의 health, Nginx 설정 검사·reload와 인증서 검증 HTTPS health를 확인합니다.
8. SSM 실행 종료·exit code 0을 기다린 뒤 GitHub runner에서도 외부 HTTPS health를 확인합니다.

SHA 이미지 태그는 불변이며 재실행도 덮어쓰지 않습니다.
릴리스 묶음은 실행 시도마다 별도 경로를 사용해 실행 중인 폴더를 덮어쓰지 않습니다.
서비스 배포는 기존 `backend`, `nginx` 두 컨테이너 구성을 유지합니다.
프론트엔드 빌드는 검증만 수행하며 Vercel 자동 배포 연결은 8단계입니다.

## 잠금과 실패 복원

GitHub에서 같은 ref의 실행을 직렬화하며 실행 중인 작업은 새 push로 취소하지 않습니다.
GitHub concurrency는 대기 중인 오래된 실행을 새 실행으로 대체할 수 있습니다.
EC2는 `/opt/homecare/deploy.lock`의 `flock`을 사용합니다.

설정 읽기·Compose 검사·이미지 pull 실패는 컨테이너 교체 전에 종료합니다.
교체 직전 현재 릴리스의 `runtime.snapshot.env`에 기존 실행 설정을 root 전용 0600으로 보관합니다.
새 서비스가 180초 안에 healthy가 되지 않거나 Nginx·HTTPS 검증이 실패하면
이전 설정을 원자적으로 복원하고 이전 릴리스의 Compose·digest로 서비스를 다시 시작합니다.
복원 성공이어도 해당 배포는 실패로 보고되어 GitHub가 성공으로 표시하지 않습니다.

정상 배포 후 `/opt/homecare/current`를 새 릴리스로 바꾸고
`/opt/homecare/previous`에 이전 정상 릴리스를 남깁니다.
정상 대상이 없는 첫 배포 실패나 복원 실패는 각각 명시적으로 오류를 남깁니다.
DB 데이터·스키마와 자동 갱신되는 TLS 인증서는 앱 롤백으로 되돌리지 않습니다.
DB 변경은 이전 앱과의 호환성을 별도로 확인해야 합니다.

`runtime.snapshot.env`와 준비 중인 설정 파일은 EC2에만 존재합니다.
S3·Git·이미지·CI 로그에는 비밀값을 넣지 않습니다.
전체 `docker inspect`, `docker compose config`, 앱 로그를 CI에 출력하지 않습니다.
SSM 대기 시간이 초과되면 원격 실행을 강제 취소하지 않고 실패로 보고합니다.
이 경우 SSM 명령 상태를 확인한 뒤 재시도합니다.

## 사용자가 확인할 부분

이 워크플로가 원격 `main`에 반영되면 자동 배포가 시작됩니다.
앱 교체 중 짧은 중단이 발생할 수 있습니다. GitHub의 **Actions → Homecare CI and deployment**에서
`ci`, `deploy` 결과와 배포 요약의 release ID·이미지 digest·SSM command ID를 확인합니다.
같은 커밋의 재실행은 **Re-run all jobs**, 수동 실행은 **Run workflow → main**을 사용합니다.

기존 GitHub `production` Variables 7개와 `main`만 허용하는 환경 정책을 그대로 사용합니다.
AWS 비밀값 등록이나 Vercel CLI 재인증은 필요하지 않습니다.
Vercel Git 연결·배포 검사·운영 모니터링은 다음 단계에서 진행합니다.

실패 시 SSM 출력의 `ROLLBACK_OK` 또는 `ROLLBACK_FAILED`를 먼저 확인합니다.
수동 복원은 EC2에서 root로 이전 릴리스의 저장된 설정을 복원한 뒤 수행합니다.
설정 스냅샷은 콘솔·채팅에 출력하지 않습니다.

```bash
(
  set -e
  exec 9>/opt/homecare/deploy.lock
  flock -w 600 9
  target=$(readlink -f /opt/homecare/previous)
  install -m 600 "$target/runtime.snapshot.env" /opt/homecare/config/.runtime-restore
  mv -f /opt/homecare/config/.runtime-restore /opt/homecare/config/runtime.env
  compose=(docker compose --project-name homecare --env-file "$target/images.env" -f "$target/compose.prod.yaml")
  "${compose[@]}" up -d --remove-orphans --wait --wait-timeout 180
  "${compose[@]}" exec -T nginx nginx -t
  "${compose[@]}" exec -T nginx nginx -s reload
  curl -fsS https://43.202.80.116/actuator/health
  ln -sfn "$target" /opt/homecare/.current-restore
  mv -Tf /opt/homecare/.current-restore /opt/homecare/current
)
```

## 검증

- 로컬 운영 스크립트 테스트: 준비 실패·교체 실패·설정 복원·첫 배포 실패·SSM 종료 확인.
- `actionlint`: 워크플로 구문·권한·표현식 검사.
- 실제 GitHub OIDC 배포와 EC2 실패 릴리스 복원 결과는 검증 후 아래에 기록합니다.

참고: [GitHub AWS OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency),
[SSM 명령 상태](https://docs.aws.amazon.com/systems-manager/latest/userguide/monitor-commands.html).
