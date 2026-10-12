# AWS 인프라 배포 현황

확인일: 2026-10-12, 리전: `ap-northeast-2`

## 적용 결과

서비스 Terraform 관리 자원 34개와 bootstrap 상태 저장 자원 6개를 생성했습니다.
적용 후 두 구성의 plan 모두 변경 없음으로 확인했습니다. 기존 실습 자원은 작업 시작 전에
제거된 상태였으며 기존 state 백업은 보존했습니다.

3단계에서 서비스 권한·OIDC·일반 설정·로그 그룹 자원 14개를 추가했습니다.
현재 서비스 Terraform 관리 자원은 48개이며 EC2·RDS는 교체하지 않았습니다.
사용자 승인 후 앱 SecureString 3개를 Terraform 외부에서 등록했습니다.
EC2 역할로 일반 설정과 비밀값 총 10개를 읽어 root 소유 0600의
`/opt/homecare/config/runtime.env`를 생성했습니다.

4단계에서 RDS `project_db`에 V001 초기 스키마와 CRUD 전용 `homecare_app` 계정을
생성하고 CA truststore·JDBC TLS·실제 앱 기동·재시작 후 데이터 보존을 검증했습니다.
관리자 비밀 조회 권한은 초기화에만 임시 부여한 뒤 제거했습니다.
검증용 데이터·컨테이너·ECR 태그와 manifest는 정리했습니다.

| 자원 | 생성 결과 |
|---|---|
| VPC | `vpc-04a6e4a332688fadb` |
| EC2 | `i-0223afa15057a76b2`, `t3.small`, `running` |
| Elastic IP | `43.202.80.116` |
| EC2 내부 IP | `10.20.1.4` |
| SSM | `Online` |
| RDS | `homecare-prod-mysql`, MySQL 8.4.11, `available` |
| RDS endpoint | `homecare-prod-mysql.cnsso26use8s.ap-northeast-2.rds.amazonaws.com:3306` |
| DB 스키마 | `project_db`, 앱 테이블 3개와 관리자용 `schema_migrations` |
| DB 앱 계정 | `homecare_app`@`10.20.1.0/24`, SSL 필수, 앱 테이블별 CRUD |
| ECR backend | `528215464049.dkr.ecr.ap-northeast-2.amazonaws.com/homecare-prod/backend` |
| ECR nginx | `528215464049.dkr.ecr.ap-northeast-2.amazonaws.com/homecare-prod/nginx` |
| 배포 파일 S3 | `homecare-prod-releases-528215464049-ap-northeast-2` |
| Terraform state S3 | `homecare-tfstate-528215464049-ap-northeast-2` |
| 서비스 state key | `prod/infrastructure.tfstate` |
| bootstrap state key | `bootstrap/terraform.tfstate` |
| GitHub 배포 역할 | `arn:aws:iam::528215464049:role/homecare-prod-github-deploy` |
| GitHub 환경 | `production`, `main` 브랜치만 허용 |

SSM 접속 명령:

```bash
aws ssm start-session --region ap-northeast-2 --target i-0223afa15057a76b2
```

Session Manager를 CLI에서 사용하려면 Session Manager plugin이 필요합니다.
앱과 Nginx는 아직 배포하지 않았으므로 Elastic IP의 웹 포트에서 서비스가 실행되는 단계는 아닙니다.

## 검증 결과

- Terraform 문법 검사와 fmt 검사를 통과했습니다.
- 서비스 mock 테스트 3개와 bootstrap mock 테스트 1개를 통과했습니다.
- AWS API로 EC2 공개 포트가 80·443뿐이고 SSH 키가 없음을 확인했습니다.
- 실제 EBS가 암호화된 gp3 30GB이며 EC2 종료 방지·IMDSv2 필수 설정을 확인했습니다.
- RDS는 비공개이며 앱 보안 그룹의 3306 연결만 허용합니다. DB 서브넷에는 인터넷 기본 경로가 없습니다.
- RDS 암호화·삭제 방지, TLS 필수 파라미터, 관리형 관리자 비밀의 `active` 상태를 확인했습니다.
- RDS 관리자 비밀번호는 Terraform state에 포함되지 않습니다. 2단계는 메타데이터만 검사했고, 4단계 초기화 때 EC2에서 메모리로 조회했습니다. 파일·SSM 명령·로그에 값은 저장하지 않았습니다.
- state와 배포 S3의 암호화·버전 관리·퍼블릭 접근 차단, 두 원격 state 객체를 확인했습니다.
- ECR 태그 불변성과 push 시 스캔 설정을 확인했습니다.
- bootstrap과 서비스의 실제 재실행 plan은 종료 코드 0, 변경 없음입니다.
- EC2에 Docker 29.9.0·Compose 5.6.0·AWS CLI 2.37.12를 설치하고 자동 시작·로그 회전을 적용했습니다.
- EC2 역할로 두 ECR 저장소의 이미지 pull·검증 컨테이너 실행·CloudWatch 로그 전송을 확인했습니다. 검증용 태그는 제거했습니다.
- 운영 설정 스크립트 테스트 5개와 실제 SecureString 복호화·파일 권한·재실행 시 키 유지 검증을 통과했습니다.
- 세 앱 비밀값이 Terraform state와 Git 추적 대상·새 소스 파일에 없음을 확인했습니다. 실제 값은 출력하지 않았습니다.
- GitHub production 환경을 main 브랜치로 제한하고 배포용 Variables를 등록했습니다. 실제 OIDC 토큰 발급 검증은 워크플로를 작성하는 7단계입니다.
- V001 초기 SQL을 임시 MySQL 8.4와 실제 RDS에서 Hibernate validate·health UP으로 확인했습니다.
- 실제 RDS 앱 계정으로 회원가입·로그인, 한글·이모지·검색 근거 조회와 재시작 후 상담 데이터·JWT 보존을 확인했습니다. OpenAI는 호출하지 않았습니다.
- 초기화 재실행 시 V001 checksum이 일치했고 DDL과 앱 비밀번호를 유지했습니다. 운영 스크립트 테스트는 총 13개를 통과했습니다.
- 비암호화 연결·신뢰하지 않는 CA·앱의 CREATE 및 초기화 이력 조회가 거부됐습니다. 초기화용 정책 삭제 후 실제 EC2 관리자 비밀 조회도 거부됐습니다.
- 검증 데이터·임시 컨테이너·이미지를 제거하고 운영 환경 파일의 root 소유 0600을 확인했습니다. 상세 절차는 [RDS 초기화 안내](../ops/RDS_INITIALIZATION.md)에 기록했습니다.

## 무료 계정 조정

최초 RDS 생성은 백업 보존 7일이 무료 플랜 한도를 초과하여
`FreeTierRestrictionError`로 거부됐습니다. 사용자 선택에 따라 보존기간을 1일로 조정한 뒤
RDS만 생성하는 plan으로 재시도하여 완료했습니다. 기존 자원의 재생성·삭제는 없었습니다.

`db_backup_retention_days`의 서비스용 기본값은 7이고, 현재 로컬 설정과 예시는 1입니다.
계정 플랜을 전환하면 7로 변경·검증·적용할 수 있습니다. 삭제 방지·최종 스냅샷 설정은 유지합니다.
무료 혜택·크레딧 차감 전 기본 정가 예상은 약 $46.24/월이며 상세 비용은 [README](README.md)를 참고합니다.

## 후속 단계

1. `../proxy/`에서 Nginx를 구성하고 Spring Boot와 함께 EC2에 컨테이너 두 개를 배포합니다.
2. API 도메인·HTTPS·CORS, GitHub Actions·ECR·SSM 배포 자동화와 Vercel 연결을 진행합니다.
