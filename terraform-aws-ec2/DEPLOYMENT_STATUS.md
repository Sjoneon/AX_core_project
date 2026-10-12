# AWS 인프라 배포 현황

확인일: 2026-10-12, 리전: `ap-northeast-2`

## 적용 결과

서비스 Terraform 관리 자원 34개와 bootstrap 상태 저장 자원 6개를 생성했습니다.
적용 후 두 구성의 plan 모두 변경 없음으로 확인했습니다. 기존 실습 자원은 작업 시작 전에
제거된 상태였으며 기존 state 백업은 보존했습니다.

| 자원 | 생성 결과 |
|---|---|
| VPC | `vpc-04a6e4a332688fadb` |
| EC2 | `i-0223afa15057a76b2`, `t3.small`, `running` |
| Elastic IP | `43.202.80.116` |
| EC2 내부 IP | `10.20.1.4` |
| SSM | `Online` |
| RDS | `homecare-prod-mysql`, MySQL 8.4.11, `available` |
| RDS endpoint | `homecare-prod-mysql.cnsso26use8s.ap-northeast-2.rds.amazonaws.com:3306` |
| ECR backend | `528215464049.dkr.ecr.ap-northeast-2.amazonaws.com/homecare-prod/backend` |
| ECR nginx | `528215464049.dkr.ecr.ap-northeast-2.amazonaws.com/homecare-prod/nginx` |
| 배포 파일 S3 | `homecare-prod-releases-528215464049-ap-northeast-2` |
| Terraform state S3 | `homecare-tfstate-528215464049-ap-northeast-2` |
| 서비스 state key | `prod/infrastructure.tfstate` |
| bootstrap state key | `bootstrap/terraform.tfstate` |

SSM 접속 명령:

```bash
aws ssm start-session --region ap-northeast-2 --target i-0223afa15057a76b2
```

Session Manager를 CLI에서 사용하려면 Session Manager plugin이 필요합니다.
앱과 Nginx는 아직 배포하지 않았으므로 Elastic IP의 웹 포트에서 서비스가 실행되는 단계는 아닙니다.

## 검증 결과

- Terraform 문법 검사와 fmt 검사를 통과했습니다.
- 서비스 mock 테스트 2개와 bootstrap mock 테스트 1개를 통과했습니다.
- AWS API로 EC2 공개 포트가 80·443뿐이고 SSH 키가 없음을 확인했습니다.
- 실제 EBS가 암호화된 gp3 30GB이며 EC2 종료 방지·IMDSv2 필수 설정을 확인했습니다.
- RDS는 비공개이며 앱 보안 그룹의 3306 연결만 허용합니다. DB 서브넷에는 인터넷 기본 경로가 없습니다.
- RDS 암호화·삭제 방지, TLS 필수 파라미터, 관리형 관리자 비밀의 `active` 상태를 확인했습니다.
- RDS 관리자 비밀번호가 Terraform state에 포함되지 않음을 확인했습니다. 비밀값은 조회하지 않았습니다.
- state와 배포 S3의 암호화·버전 관리·퍼블릭 접근 차단, 두 원격 state 객체를 확인했습니다.
- ECR 태그 불변성과 push 시 스캔 설정을 확인했습니다.
- bootstrap과 서비스의 실제 재실행 plan은 종료 코드 0, 변경 없음입니다.

## 무료 계정 조정

최초 RDS 생성은 백업 보존 7일이 무료 플랜 한도를 초과하여
`FreeTierRestrictionError`로 거부됐습니다. 사용자 선택에 따라 보존기간을 1일로 조정한 뒤
RDS만 생성하는 plan으로 재시도하여 완료했습니다. 기존 자원의 재생성·삭제는 없었습니다.

`db_backup_retention_days`의 서비스용 기본값은 7이고, 현재 로컬 설정과 예시는 1입니다.
계정 플랜을 전환하면 7로 변경·검증·적용할 수 있습니다. 삭제 방지·최종 스냅샷 설정은 유지합니다.
무료 혜택·크레딧 차감 전 기본 정가 예상은 약 $46.24/월이며 상세 비용은 [README](README.md)를 참고합니다.

## 후속 단계

1. EC2의 Docker·Compose·AWS CLI 실행 환경과 앱용 AWS 권한·비밀값을 준비합니다.
2. RDS 앱 계정·초기 스키마와 TLS 연결을 준비합니다.
3. `../proxy/`에서 Nginx를 구성하고 Spring Boot와 함께 EC2에 컨테이너 두 개를 배포합니다.
4. API 도메인·HTTPS·CORS, GitHub Actions·ECR·SSM 배포 자동화와 Vercel 연결을 진행합니다.
