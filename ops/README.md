# EC2 실행 환경과 앱 설정 준비

2026-10-12, `i-0223afa15057a76b2`에 Docker 29.9.0, Compose 5.6.0,
AWS CLI 2.37.12를 SSM으로 설치했습니다. Docker 자동 시작과 json-file 로그
회전(파일당 10MB, 최대 3개)을 적용했습니다.

## 권한과 자격증명

- 로컬 AWS CLI는 사용자 `~/.aws/credentials` 또는 SSO·임시 자격증명을 사용합니다.
- EC2의 AWS CLI·Docker는 `homecare-prod-ec2` 인스턴스 역할을 사용합니다.
- GitHub Actions는 `homecare-prod-github-deploy` 역할을 OIDC로 사용합니다.
- 로컬 AWS 액세스 키는 EC2나 GitHub에 복사하지 않습니다.
- EC2에는 서비스 ECR pull, 배포 버킷의 `releases/*` 읽기, `/homecare/prod/*` 설정 읽기,
  지정 로그 그룹에 쓰기를 허용했습니다. 다른 앱의 파라미터와 Terraform state 접근은 차단합니다.

## 준비된 일반 설정

Terraform이 `/homecare/prod/` 아래에 일반 String 파라미터 7개를 관리합니다.

| 이름 | 설정 |
|---|---|
| `OPENAI_MODEL` | `gpt-4o-mini` |
| `JWT_EXPIRATION_MS` | `3600000` |
| `SERVER_ADDRESS` | `0.0.0.0` |
| `SERVER_PORT` | `8080` |
| `SPRING_DATASOURCE_USERNAME` | `homecare_app` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate` |
| `SPRING_DATASOURCE_URL` | RDS 주소, `sslMode=VERIFY_IDENTITY`, 명시적 RDS CA truststore |

DB 앱 계정·스키마와 `/app/config/rds-truststore.p12`는 4단계에서 준비합니다.
truststore 비밀번호 `changeit`은 공개 CA 인증서를 담은 파일의 설정이며 DB 비밀번호가 아닙니다.
컨테이너에는 호스트의 공개 CA truststore를 해당 경로에 읽기 전용으로 연결할 예정입니다.

## 등록된 앱 비밀값

사용자의 명시적 승인 후 아래 SecureString 3개를 서울 리전의 AWS 계정
`528215464049`에 등록했습니다. AWS 관리형 SSM KMS 키로 암호화하며,
실제 값은 채팅이나 로그에 출력하지 않습니다.

| 대상 | 값의 출처 |
|---|---|
| `/homecare/prod/OPENAI_API_KEY` | 로컬 `backend/.env`의 기존 OpenAI API 키 |
| `/homecare/prod/JWT_SECRET` | 최초 등록 시 생성한 운영용 무작위 32바이트 Base64 키 |
| `/homecare/prod/SPRING_DATASOURCE_PASSWORD` | 최초 등록 시 생성한 운영 DB 앱 계정 비밀번호 |

로컬 등록 명령(현재 재실행하면 기존 값을 보존):

```bash
python3 ops/register-parameters.py
```

이 스크립트는 계정을 검사하고, 존재하는 SecureString을 덮어쓰지 않습니다.
생성한 키를 소스·Terraform 변수·state에 저장하지 않으며, AWS CLI 요청용 임시 JSON은
0600 권한으로 작성한 뒤 삭제합니다. 값이나 AWS 오류의 원문은 출력하지 않습니다.

## EC2 런타임 환경 파일

`write-runtime-env.py`는 EC2의 `/usr/local/lib/homecare/`에 설치했습니다.
EC2에서 아래 명령으로 환경 파일을 준비했습니다. 배포 시에도 같은 명령을 사용합니다.

```bash
sudo python3 /usr/local/lib/homecare/write-runtime-env.py
```

EC2 역할로 파라미터 10개를 읽어 `/opt/homecare/config/runtime.env`를 원자적으로
교체하고 파일 권한을 0600으로 설정합니다. 설정 디렉터리는 root 소유 0700입니다.
누락된 값이나 줄바꿈이 있는 값은 거부하며 기존 파일을 유지합니다. 비밀값은 출력하지 않습니다.
실제 EC2 역할로 SecureString 복호화와 파일 생성을 확인했고, 재실행 후 모든 값이
동일함을 확인했습니다. 등록 스크립트를 재실행해도 세 비밀값의 버전은 1로 유지됩니다.
이 파일은 Docker 환경 파일용이며 shell에서 `source`로 실행하지 않습니다.

## GitHub 배포 준비

- 저장소: `gwonu1/core_project`.
- Environment: `production`, 허용 브랜치: `main`만.
- OIDC subject는 저장소 API에서 확인한 불변 ID 형식입니다.
  `repo:gwonu1@190448546/core_project@1414051183:environment:production`.
- 역할 ARN·리전·EC2 ID·배포 버킷·ECR 주소와 저장소 이름을 production Variables로 등록했습니다.
- 앱 키나 AWS 장기 액세스 키를 GitHub Secrets에 등록하지 않았습니다.
- 배포 작업은 `environment: production`, `permissions: id-token: write`를 사용합니다.
  실제 워크플로 작성·실행과 OIDC 토큰 발급 검증은 7단계입니다.

## 설치 재현과 검증

`bootstrap-ec2.sh`는 EC2에서 root 권한으로 실행하는 초기 준비 스크립트입니다.
공식 Docker APT 저장소를 사용하며 AWS CLI 공식 설치 스크립트의 GPG 검증을 거칩니다.
실행 중 Docker를 재시작하므로 최초 준비 또는 정해진 유지보수 시간에 실행합니다.
SSM 명령에는 이 스크립트 코드만 전달하며 키·비밀번호를 넣지 않습니다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s ops/tests -v
bash -n ops/bootstrap-ec2.sh
terraform -chdir=terraform-aws-ec2 test
```

실제 EC2에서 인스턴스 역할 사용, ECR 두 저장소의 검증 이미지 pull·컨테이너 실행,
CloudWatch 로그 전송을 확인했습니다. 검증용 ECR 태그는 제거했습니다.
IAM 시뮬레이션으로 다른 앱 파라미터·state 읽기, EC2 역할의 이미지 push,
GitHub 역할의 직접적인 비밀값 읽기와 다른 EC2 배포가 거부되는 것도 확인했습니다.
등록된 비밀값이 Terraform state와 Git 추적 대상·새 소스 파일에 없음을 값 출력 없이 확인했습니다.

기존 EC2·RDS 교체 없이 Terraform 자원 14개를 추가했고, 적용 후 plan은 변경 없음입니다.
CloudWatch 로그 보존은 14일이며 사용량에 따라 로그 저장·수집 비용이 발생합니다.

참고: [Docker Ubuntu 설치](https://docs.docker.com/engine/install/ubuntu/),
[AWS CLI 설치](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html),
[GitHub AWS OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).
