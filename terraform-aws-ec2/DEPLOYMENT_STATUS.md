# AWS 인프라 배포 현황

확인일: 2026-10-13, 리전: `ap-northeast-2`

## 적용 결과

서비스 Terraform 관리 자원 34개와 bootstrap 상태 저장 자원 6개를 생성했습니다.
적용 후 두 구성의 plan 모두 변경 없음으로 확인했습니다. 기존 실습 자원은 작업 시작 전에
제거된 상태였으며 기존 state 백업은 보존했습니다.

3단계에서 서비스 권한·OIDC·일반 설정·로그 그룹 자원 14개를 추가했습니다.
8단계에서 운영 대시보드 1개를 추가하고 서비스 namespace의 메트릭 전송 권한을 적용했습니다.
현재 서비스 Terraform 관리 자원은 49개이며 EC2·RDS는 교체하지 않았습니다.
사용자 승인 후 앱 SecureString 3개를 Terraform 외부에서 등록했습니다.
EC2 역할로 일반 설정과 비밀값 총 10개를 읽어 root 소유 0600의
`/opt/homecare/config/runtime.env`를 생성했습니다.

4단계에서 RDS `project_db`에 V001 초기 스키마와 CRUD 전용 `homecare_app` 계정을
생성하고 CA truststore·JDBC TLS·실제 앱 기동·재시작 후 데이터 보존을 검증했습니다.
관리자 비밀 조회 권한은 초기화에만 임시 부여한 뒤 제거했습니다.
검증용 데이터·컨테이너·ECR 태그와 manifest는 정리했습니다.

5단계에서 `homecare-backend-1`, `homecare-nginx-1` 두 컨테이너를 상시 배포했습니다.
Nginx HTTP 80으로 API를 전달하며 앱의 8080은 호스트에 공개하지 않습니다.
실제 EC2 재부팅 후 두 컨테이너 자동 복구와 외부 health를 확인했습니다.
5단계 릴리스는 [당시 기록](../ops/STAGE5_RELEASE.json)에 보관합니다.

6단계에서 별도 도메인 없이 Let's Encrypt IP 인증서로 HTTPS·CORS를 적용했습니다.
인증서 갱신 dry-run·Nginx reload hook과 활성 타이머를 확인했습니다.
기존 Vercel 프로젝트 `homecare`의 Production API 주소를 등록하고 CLI로 운영 배포를 승격했습니다.
당시 릴리스는 [HTTPS 릴리스 기록](../ops/STAGE6_RELEASE.json)에 저장했습니다.

7단계에서 GitHub Actions·OIDC·ECR·SSM 배포 자동화와 실제 실패 복원을 검증했습니다.
8단계에서 Vercel Git·운영 승격 검사·호스트 메트릭을 연결했습니다.
현재 앱 릴리스는 EC2 `current` 링크와 Actions 배포 요약에서 확인합니다.

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
| 운영 컨테이너 | `homecare-backend-1`, `homecare-nginx-1`, 둘 다 healthy |
| HTTPS health | `https://43.202.80.116/actuator/health` |
| 운영 프론트엔드 | `https://homecare-omega-eight.vercel.app` |
| 인증서 갱신 | Certbot 5.8.0, IP `shortlived` 프로필, Snap systemd timer·reload hook |
| 대시보드 | CloudWatch `homecare-prod` |
| 호스트 관측 | `homecare-metrics.timer`, `homecare/prod` namespace의 메트릭 5개 |
| 프론트엔드 자동 배포 | Git main·Root Directory frontend·동일 커밋 ci/deploy 승격 검사 |

SSM 접속 명령:

```bash
aws ssm start-session --region ap-northeast-2 --target i-0223afa15057a76b2
```

Session Manager를 CLI에서 사용하려면 Session Manager plugin이 필요합니다.
현재 HTTPS API와 Vercel 프론트엔드가 연결됐고 Git 기반 CI/CD가 동작합니다.

## 검증 결과

- Terraform 문법 검사와 fmt 검사를 통과했습니다.
- 서비스 mock 테스트 4개와 기존 bootstrap mock 테스트 1개를 통과했습니다.
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
- GitHub production 환경을 main 브랜치로 제한하고 배포용 Variables를 등록했습니다. 7단계에서 실제 OIDC 토큰·ECR·SSM 배포·동일 커밋 이미지 재사용을 검증했습니다.
- V001 초기 SQL을 임시 MySQL 8.4와 실제 RDS에서 Hibernate validate·health UP으로 확인했습니다.
- 실제 RDS 앱 계정으로 회원가입·로그인, 한글·이모지·검색 근거 조회와 재시작 후 상담 데이터·JWT 보존을 확인했습니다. OpenAI는 호출하지 않았습니다.
- 초기화 재실행 시 V001 checksum이 일치했고 DDL과 앱 비밀번호를 유지했습니다. 운영 스크립트 테스트는 총 13개를 통과했습니다.
- 비암호화 연결·신뢰하지 않는 CA·앱의 CREATE 및 초기화 이력 조회가 거부됐습니다. 초기화용 정책 삭제 후 실제 EC2 관리자 비밀 조회도 거부됐습니다.
- 검증 데이터·임시 컨테이너·이미지를 제거하고 운영 환경 파일의 root 소유 0600을 확인했습니다. 상세 절차는 [RDS 초기화 안내](../ops/RDS_INITIALIZATION.md)에 기록했습니다.
- Nginx 1.30.5 이미지·운영 Compose 문법과 두 서비스의 메모리 상한·읽기 전용 파일시스템·재시작 정책·healthcheck를 검증했습니다. 앱은 일반 사용자로 실행합니다.
- 외부 health 200·인증 없는 API 401과 8080·3306 접근 차단을 확인했습니다. EC2 내부 프록시 경유 회원가입·로그인·상담·조회·삭제와 앱 재생성 후 RDS 데이터·JWT 보존을 확인했습니다.
- 같은 릴리스 시작을 재실행해 컨테이너 ID가 유지됐습니다. 실제 boot ID 변경으로 EC2 재부팅을 확인하고 두 컨테이너의 자동 healthy 복구를 검증했습니다.
- 운영 로그의 CloudWatch 전송과 제한된 로컬 캐시를 확인했습니다. 앱 비밀값은 Nginx 환경이나 로그 캐시에 없습니다. 합성 사용자와 상담 데이터는 제거했고 OpenAI는 호출하지 않았습니다.
- 서비스명을 `backend`로 변경하고 기존 컨테이너를 정리했습니다. 변경 전 JWT·상담 데이터와 변경 후 인증·상담·삭제·백엔드 재생성 검증을 통과했습니다. Compose 서비스·Nginx 대상·검증 코드·로그 스트림의 이름을 함께 변경했습니다.
- 외부 기본 CA 검증에서 IP SAN 인증서와 HTTPS health가 정상이고 HTTP 요청은 308로 전환됩니다. 정확한 Vercel origin의 preflight 204·401 CORS 헤더와 다른 origin·미지원 메서드 거부를 확인했습니다.
- HTTPS 회원가입·로그인·상담·삭제·backend 교체 후 데이터·JWT 유지, 로컬 TLS·CORS 회귀 검사를 통과했습니다. 합성 데이터만 제거했으며 OpenAI는 호출하지 않았습니다.
- Certbot 갱신 dry-run·실제 reload hook이 성공했고 `snap.certbot.renew.timer`가 enabled·active입니다. TLS 키는 root 소유 0600이며 Nginx에만 인증서 디렉터리를 읽기 전용으로 연결했습니다.
- 기존 Vercel 운영 도메인이 새 production 배포를 제공하며 공개 JavaScript에 HTTPS API 주소가 반영됐습니다. 브라우저 페이지와 오류 없는 초기 표시를 확인했고 Node 24·Git 미연결 상태를 유지했습니다.
- 8단계에서 Git 연결·Node 24·frontend 루트·production 검사 두 개를 적용했습니다. Git 빌드가 STAGED로 대기한 뒤 동일 커밋 CI·backend 배포 통과 후 PROMOTED로 전환되고 기존 운영 도메인에 연결됐습니다.
- 실제 브라우저 회원가입·로그인·첫 AI 상담·후속 상담·검색 근거·내역 재조회·UI 삭제·로그아웃을 확인했습니다. 승인된 OpenAI 호출을 사용했고 합성 상담·계정을 정리했습니다.
- 호스트 메트릭 5개가 CloudWatch에 수신됐고 대시보드·타이머와 제한된 IAM namespace를 검증했습니다. 운영 스크립트 테스트는 총 23개입니다.
- 자동 백업의 합성 데이터 커밋 시점으로 별도 비공개·암호화 RDS에 복원했습니다. 사용자·상담·한글/이모지 메시지와 TLS를 검증하고 임시 인스턴스·원본 검증 데이터를 정리했습니다. 운영 RDS 주소·삭제 보호·백업 1일은 유지했습니다.

## 무료 계정 조정

최초 RDS 생성은 백업 보존 7일이 무료 플랜 한도를 초과하여
`FreeTierRestrictionError`로 거부됐습니다. 사용자 선택에 따라 보존기간을 1일로 조정한 뒤
RDS만 생성하는 plan으로 재시도하여 완료했습니다. 기존 자원의 재생성·삭제는 없었습니다.

`db_backup_retention_days`의 서비스용 기본값은 7이고, 현재 로컬 설정과 예시는 1입니다.
계정 플랜을 전환하면 7로 변경·검증·적용할 수 있습니다. 삭제 방지·최종 스냅샷 설정은 유지합니다.
무료 혜택·크레딧 차감 전 기본 정가 예상은 약 $46.24/월이며 상세 비용은 [README](README.md)를 참고합니다.

## 운영 확인

이메일·예산 알림은 사용자 요청으로 생략했습니다.
백업 복구 검증과 운영 대응 절차는 [운영 안내](../ops/OPERATIONS.md)에 기록합니다.
