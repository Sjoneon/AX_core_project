# HomeCare 서비스 AWS 인프라

퍼블릭 서브넷의 EC2 한 대에서 Nginx·Spring Boot 컨테이너 두 개를 실행할 인프라입니다.
AWS 기반 자원과 실행 권한·GitHub OIDC·일반 설정·로그 그룹을 관리합니다.
EC2 Docker 설치는 `../ops/`의 SSM 초기 준비 스크립트로 수행했습니다.
앱 비밀값·EC2 운영 환경 파일과 RDS 앱 계정·초기 스키마·TLS 검증을 완료했습니다.
`../proxy/`의 Nginx·상시 앱 배포·HTTPS 구성과 GitHub Actions 워크플로는 후속 단계입니다.

2026-10-12 실제 AWS 적용과 검증을 완료했습니다. 생성한 자원과 검증 결과는
[배포 현황](DEPLOYMENT_STATUS.md)에 기록했습니다.

## 구성

3단계의 실행 환경·비밀값 등록 범위·GitHub 설정은 [ops 안내](../ops/README.md)를 참고합니다.
4단계의 SQL·앱 계정·TLS·임시 관리자 권한은 [RDS 초기화 안내](../ops/RDS_INITIALIZATION.md)를 참고합니다.

| 항목 | 설정 |
|---|---|
| 리전 | 서울 `ap-northeast-2` |
| Terraform / AWS Provider | CLI 1.16.5 권장, AWS Provider 6.68.0 고정 |
| VPC | `10.20.0.0/16` |
| EC2 서브넷 | 퍼블릭 1개, `ap-northeast-2a` |
| EC2 | Ubuntu 24.04 LTS amd64, `t3.small`, 암호화 gp3 30GB |
| 공인 IP | Elastic IP |
| 관리 접근 | SSM, SSH 키·22 포트 없음 |
| 공개 포트 | 80·443. 앱 8080은 미공개 |
| DB 서브넷 | 프라이빗 2개, a·c AZ, 인터넷 기본 경로 없음 |
| RDS | MySQL 8.4.11, `db.t4g.micro`, Single-AZ, 암호화 gp3 20GB |
| DB 접근 | 앱 보안 그룹에서만 3306, TLS 필수 |
| DB 보호 | 현재 무료 계정 백업 1일, 서비스 기본값 7일, 삭제 방지·최종 스냅샷 필수 |
| DB 관리자 비밀번호 | RDS가 Secrets Manager에서 생성·관리; Terraform에 값 저장 안 함 |
| ECR | `homecare-prod/backend`, `homecare-prod/nginx`, 태그 불변·push 시 검사 |
| 배포 파일 | 비공개·암호화·버전 관리된 별도 S3 |
| Terraform state | 전용 S3, 암호화·버전 관리·TLS·퍼블릭 차단, S3 lockfile |

이번 작업 시작 전 state에는 관리 자원이 없었고, 기존 실습 태그의 EC2·VPC도 없음을 확인했습니다.
기존 `terraform.tfstate.backup`은 삭제하지 않습니다. 다른 환경에서 실행할 때는 state와
실제 자원을 먼저 확인하고, 이미 존재하는 자원은 import 또는 별도 이전 계획을 사용하세요.

AMI와 MySQL 버전은 2026-10-12 서울 리전 AWS API에서 제공 여부를 확인했습니다.
AMI는 image ID와 Canonical 소유자를 검사하며 `most_recent`로 자동 변경하지 않습니다.
MySQL 자동 마이너 업그레이드는 비활성화했습니다. 정기 점검 시 버전을 명시적으로
변경하고 테스트·plan 검토 후 적용합니다.

현재 AWS 무료 계정은 7일 백업 설정을 `FreeTierRestrictionError`로 거부했습니다.
사용자 선택에 따라 `db_backup_retention_days=1`로 적용합니다. 무료 플랜에서 허용되는
범위로 운영하며, 유료 플랜 전환 후 7일로 늘리는 것을 권장합니다. 1일을 초과한
과거 시점으로 복구해야 하는 경우에는 별도로 보존한 스냅샷이 필요합니다.

## 비용 기준

2026-10-12 AWS Pricing API 조회, 월 730시간 상시 실행, USD 기준입니다.
무료 혜택이나 크레딧 차감 전 정가이며 실제 청구액은 계정 혜택과 사용량에 따라 달라집니다.

| 항목 | 단가 | 월 예상 |
|---|---|---:|
| EC2 `t3.small` Linux | $0.026/시간 | $18.98 |
| RDS `db.t4g.micro` MySQL Single-AZ | $0.025/시간 | $18.25 |
| EBS gp3 30GB | $0.0912/GB·월 | $2.74 |
| RDS gp3 20GB | $0.131/GB·월 | $2.62 |
| 공인 IPv4 1개 | $0.005/시간 | $3.65 |
| 기본 합계 | | **약 $46.24/월** |

세금, 데이터 전송, Secrets Manager, S3·ECR 사용량, 추가 스냅샷·로그, OpenAI,
도메인·Vercel 비용은 별도입니다. RDS 저장공간은 최대 100GB까지 자동 확장되며
확장 후 스토리지 비용은 증가합니다. EC2 CPU credit은 `standard`로 설정하여
추가 CPU credit 과금을 막지만, 지속적인 CPU 부하에서는 처리량이 제한됩니다.
비용 알림과 부하 기반 증설은 후속 운영 단계에서 구성합니다.

## 초기화와 적용 순서

SSO 또는 임시 AWS 자격증명을 준비하고 `aws sts get-caller-identity`로 계정을 확인합니다.
`aws_account_id`는 Provider의 `allowed_account_ids`에 사용되며 다른 계정에는 적용되지 않습니다.
설정 예시에는 현재 작업 계정과 확인한 AMI가 들어 있습니다. 계정 변경 시 반드시 수정합니다.
비밀값은 `.tfvars`, backend 설정, user data에 넣지 않습니다.

### 1. 상태 저장소 bootstrap

```bash
cd terraform-aws-ec2/bootstrap
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform validate
terraform test
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

생성된 상태 버킷에 bootstrap state도 이전합니다.

```bash
cp backend.tf.example backend.tf
cp backend.hcl.example backend.hcl
terraform init -migrate-state -backend-config=backend.hcl
terraform plan
```

이전 완료 후 S3의 `bootstrap/terraform.tfstate`와 변경 없는 plan을 확인합니다.
상태 버킷에는 `prevent_destroy`를 적용했습니다. bootstrap과 서비스는 별도 state로 관리합니다.

이미 상태 버킷을 생성한 환경의 새 체크아웃에서는 위 로컬 bootstrap apply를 반복하지 않습니다.
`backend.tf.example`과 `backend.hcl.example`을 먼저 복사한 뒤
`terraform init -backend-config=backend.hcl`로 기존 원격 state에 연결합니다.
서비스 폴더도 기존 원격 state에 연결한 뒤 plan을 확인합니다. 새 머신에서 빈 로컬 state를
원격 state로 강제 복사하지 않습니다.

### 2. 서비스 인프라

```bash
cd ..
cp terraform.tfvars.example terraform.tfvars
cp backend.hcl.example backend.hcl
terraform init -migrate-state -backend-config=backend.hcl
terraform validate
terraform test
terraform plan -out=service.tfplan
terraform apply service.tfplan
terraform plan -detailed-exitcode
```

마지막 plan의 종료 코드 0이 변경 없음입니다. 생성 직후 변경이 있다면 원인을 확인한 뒤
후속 작업을 진행합니다. 실제 적용에는 S3 backend 초기화 후 생성한 plan을 사용합니다.
적용한 plan은 state 변경으로 오래된 자료가 되므로 후속 변경 전에 새 plan을 생성합니다.

최초 init에서는 state 이전 여부를 확인합니다. 기존 state가 있는 경우 내용을 검토하고
이전하며, 백업 파일을 새 state로 덮어쓰지 않습니다. `.tfplan`, state, `.terraform/`,
로컬 backend 설정과 `.tfvars`는 Git에서 제외됩니다. Provider lock 파일은 저장합니다.

## 적용 후 확인

```bash
terraform output
aws ssm describe-instance-information --region ap-northeast-2
```

- EC2 상태와 SSM `Online` 여부를 확인합니다. SSM 준비용 user data만 실행하며 앱은 배포하지 않습니다.
- 출력된 Elastic IP를 후속 API DNS 설정에 사용합니다. 기존 `public_ip` 출력은 EIP를 가리킵니다.
- EC2 보안 그룹에는 80·443만 있어야 하며 RDS는 앱 보안 그룹의 3306만 허용해야 합니다.
- RDS는 `available`, 퍼블릭 접근 비활성화, 암호화·백업·삭제 방지 활성화 상태를 확인합니다.
- Secrets Manager는 비밀 ARN만 출력합니다. EC2에는 아직 DB 관리자 비밀 조회 권한을 부여하지 않습니다.
- state 버킷과 배포 버킷의 암호화·버전 관리·공개 차단을 확인합니다.
- ECR의 태그 불변성과 스캔 설정을 확인합니다. 릴리스 태그는 자동 삭제하지 않습니다.

RDS 백업 창은 UTC 18:00–19:00(KST 다음 날 03:00–04:00), 유지보수 창은
UTC 일요일 19:00–20:00(KST 월요일 04:00–05:00)입니다.

## 삭제·교체

실제 데이터가 저장된 이후에는 인프라 교체·삭제 전에 백업과 복구 계획을 준비합니다.
EC2는 API 종료 방지, RDS는 삭제 방지를 적용했습니다. 제거가 필요한 경우 해당 보호
설정을 명시적으로 변경·적용한 뒤 삭제 계획을 확인합니다. RDS 삭제 전에는
`final_snapshot_identifier`를 아직 존재하지 않는 이름으로 지정하세요.
상태 버킷은 서비스 인프라와 함께 삭제하지 않습니다. 릴리스 버킷·ECR도 강제 삭제하지 않습니다.

## 검증과 참고

`terraform test`는 mock provider를 사용하므로 AWS 자원을 생성하지 않습니다.
테스트는 공개 포트, DB 네트워크 격리, 암호화·백업, SSM·IMDSv2,
이미지 태그 불변성, S3 버전 관리·TLS 조건을 검증합니다.
실제 AWS 연결·지원 버전·권한은 별도의 plan과 적용 후 API 확인으로 검증합니다.

- [Terraform S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3)
- [RDS 관리형 관리자 비밀번호](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-secrets-manager.html)
- [Ubuntu SSM Agent](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu.html)
- [공인 IPv4 가격](https://aws.amazon.com/vpc/pricing/)
- [AWS Pricing API](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/price-changes.html)
