# core_project 단일 EC2 배포 인프라 구축 계획

작성일: 2026-10-12

> 1~3단계의 구현·검증을 완료했습니다. AWS 인프라를 실제 생성했고 Terraform state를 S3로 이전했으며 EC2 실행 환경·권한·비밀값을 준비했습니다. DB 초기화·Nginx·배포 자동화는 후속 단계입니다.

3단계 완료: EC2의 Docker·Compose·AWS CLI, IAM·OIDC·로그 권한, GitHub production 환경과 일반 설정을 준비했습니다. 사용자 승인 후 앱 비밀값 3개를 SecureString으로 등록하고 EC2 역할로 복호화해 운영 환경 파일을 생성했습니다. [실행 환경 안내](ops/README.md)를 참고합니다.

### 구현 현황

- 1단계 완료: Gradle Wrapper 포함, `app.jar` 빌드, Docker 빌드 범위 제한, DB·JWT 환경변수, 프론트엔드 API 주소와 Node 24 설정.
- 검증 완료: 빈 Gradle 캐시의 새 체크아웃 빌드, H2 테스트 56개, MySQL 8.4 테스트 56개, Node 24의 새 의존성 설치·lint·로컬/운영 API 설정 빌드.
- 컨테이너 검증 완료: `linux/amd64` 이미지 기동, 로그인, JWT 기본 1시간·변경값 적용, `ddl-auto=validate` 재기동 및 상담 데이터 유지.
- 기존 Terraform 작업 폴더는 `terraform-aws-ec2/`이며, 후속 Nginx 구성은 `proxy/`를 사용합니다.
- 2단계 완료: VPC·퍼블릭 서브넷 1개·프라이빗 서브넷 2개, EC2·EIP, 비공개 RDS, ECR 2개, 배포 파일 S3와 별도 state S3를 생성했습니다.
- Terraform 검증 완료: 서비스 mock 테스트 2개, bootstrap mock 테스트 1개, 실제 AWS 설정 확인, 서비스와 bootstrap의 변경 없는 재실행 plan.
- 현재 EC2는 `running` 및 SSM `Online`, RDS는 `available`입니다. [배포 현황](terraform-aws-ec2/DEPLOYMENT_STATUS.md)과 [Terraform 실행 안내](terraform-aws-ec2/README.md)를 참고합니다.
- 무료 계정이 7일 백업을 거부하여 사용자 선택에 따라 현재 RDS 백업은 1일입니다. 서비스용 기본값은 7일이며, 유료 플랜 전환 후 늘릴 수 있습니다.
- 3단계 검증 완료: 실제 EC2에서 ECR 두 저장소 pull·컨테이너 실행·CloudWatch 로그 전송·SecureString 복호화를 확인했습니다. 운영 환경 파일은 root 소유 0600이며 재실행 후 키가 유지됩니다. Terraform 서비스 테스트 3개, 앱 설정 스크립트 테스트 5개와 변경 없는 plan을 확인했습니다. 비밀값은 Terraform state나 Git에 포함될 소스 파일에 없습니다.

## 1. 목표와 확정 아키텍처

학습·포트폴리오 용도로 서울 리전(`ap-northeast-2`)에서 구성합니다. 기존 데이터 이전은 없으며, 배포 중 짧은 서비스 중단을 허용합니다. 기존 실습 state와 실제 AWS를 확인해 남아 있는 실습 EC2·VPC가 없음을 확인한 뒤, `terraform-aws-ec2/`를 서비스용 구성으로 변경·적용했습니다.

**퍼블릭 서브넷 하나에 EC2 한 대를 두고, 그 EC2 안에서 Nginx와 Spring Boot 컨테이너를 하나씩 실행합니다.** 프론트엔드는 Vercel에서 제공하고 브라우저가 API 도메인을 직접 호출합니다.

```text
Vercel → 브라우저에서 React 실행
                   │
                   │ HTTPS API 요청
                   ▼
AWS VPC
├─ 퍼블릭 서브넷
│  └─ EC2 1대 + Elastic IP
│     └─ Docker Compose
│        ├─ Nginx 컨테이너
│        │  └─ 호스트 80·443 공개
│        └─ Spring Boot 컨테이너
│           └─ 내부 8080, 호스트 포트 미공개
│                    │
│                    ├─ OpenAI API 호출
│                    └─ RDS 연결
└─ 프라이빗 서브넷 2개 / 서로 다른 AZ
   └─ RDS MySQL Single-AZ
```

프라이빗 서브넷 두 개는 RDS subnet group 구성을 위한 것입니다. RDS 인스턴스를 두 대 만드는 것은 아닙니다.

배포 흐름은 다음과 같습니다.

```text
GitHub PR → GitHub Actions 테스트·빌드
GitHub main → CI 성공 → ECR 이미지 push → SSM 배포 요청
                                            │
                                            ▼
                                         EC2 1대
                                            │
                              이미지 pull → 컨테이너 교체
                                            │
                                  health 확인 / 실패 시 복원

Vercel Git 연동 → 프론트엔드 빌드
                 → 동일 커밋 CI·백엔드 배포 성공 확인 → 운영 반영
```

Terraform은 AWS 자원을 관리하고, 애플리케이션 릴리스는 GitHub Actions와 SSM으로 관리합니다.

## 2. 구현 전 코드 분석 결과

| 확인한 내용 | 계획에 반영할 사항 |
|---|---|
| Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 사용 | Java 21로 CI 빌드 통일, 기존 의존성 버전의 빌드 재현성 검증 |
| React 19, Vite 8 사용 | Vercel 정적 배포, CI와 Vercel의 Node 버전 통일 |
| 프론트엔드가 `/api/...` 상대 경로 호출 | 운영 API 기본 주소 설정 추가 |
| Vite 개발 서버에만 API 프록시 존재 | Vercel 운영 환경에서는 브라우저가 API 도메인 직접 호출 |
| Compose가 `host.docker.internal`의 MySQL에 연결하고 8080 공개 | RDS 연결과 Docker 내부 통신으로 변경 |
| 코드에서 요구하는 `jwt.expiration-ms` 설정이 없음 | 새 환경의 기동 실패를 막도록 만료시간 명시 |
| Gradle Wrapper JAR가 로컬에는 있지만 Git 추적에서 제외됨 | 새 CI 실행 환경에서도 빌드하도록 Git 추적 복구 |
| Dockerfile이 `build/libs/*.jar` 복사 | 실행 가능한 Boot JAR 하나를 명확히 선택 |
| DB는 로컬 주소·계정과 `ddl-auto=update` 사용 | 운영 환경변수와 명시적인 스키마 초기화 절차 적용 |
| Nginx·GitHub Actions 구성 없음, 기존 Terraform 구성은 별도 폴더로 제공됨 | `proxy/`에서 Nginx 구성, `terraform-aws-ec2/`의 기존 자원·state 확인 후 인프라 확장 검토 |

주요 근거:

- [백엔드 빌드 설정](backend/build.gradle)
- [백엔드 실행 설정](backend/src/main/resources/application.properties)
- [Vite 개발 프록시 설정](frontend/vite.config.js)

첨부 아키텍처 그림과 실제 데이터 구조에는 차이가 있습니다. 현재 MySQL에는 사용자·상담·메시지·검색 근거가 저장됩니다. 검색용 매뉴얼 원본은 JAR에 포함되고, 벡터 인덱스는 앱 메모리에 생성됩니다. 따라서 초기에는 별도 벡터 DB나 매뉴얼 저장용 서버를 추가하지 않습니다. 재시작 후 첫 상담에서는 문서 임베딩을 다시 수행합니다.

초기 계획에서는 정적 분석과 프론트엔드 lint만 수행했습니다. 1단계 구현 후 새 빌드와 H2·MySQL 테스트를 실행했으며, 결과는 위 구현 현황에 기록했습니다.

## 3. 단계별 구축 계획

### 1단계 — 새 환경에서 빌드·기동 가능하도록 준비

- Git 추적에서 제외된 Gradle Wrapper JAR 문제를 해결합니다.
- Java 21로 `clean test bootJar`를 실행하고 Docker 이미지에는 검증된 Boot JAR 하나만 넣습니다.
- Docker 빌드 컨텍스트에서 `.env`, Git 데이터, 불필요한 빌드 산출물을 제외합니다.
- JWT 만료시간은 기본 1시간으로 명시하고 환경변수로 조정할 수 있게 합니다.
- 운영 DB 연결은 `SPRING_DATASOURCE_*` 환경변수로 설정합니다. 현재 코드에는 README의 `DB_URL`을 Spring datasource 설정으로 변환하는 처리가 없습니다.
- 프론트엔드에 `VITE_API_BASE_URL`을 적용합니다. 로컬에서는 API 기본 주소를 비워 기존 Vite 프록시를 유지합니다.
- Node 24로 프론트엔드 CI와 Vercel 빌드 환경을 통일합니다.
- 기존 H2·모의 OpenAI 테스트에 더해 배포 대상 MySQL 버전에서 초기 스키마와 주요 저장·조회 동작을 검증합니다.

**완료 기준:** 새 체크아웃에서 비밀값 없이 테스트·빌드가 성공하고, 별도로 주입한 실행 설정으로 컨테이너가 기동됩니다.

### 2단계 — Terraform 상태 저장과 AWS 자원 생성

- `terraform-aws-ec2/`의 기존 state와 실제 AWS 자원 상태를 먼저 확인하고, 기존 EC2·VPC를 이어서 사용할 범위를 확정합니다. 확인 없이 동일 목적의 자원을 중복 생성하지 않습니다.
- 작은 bootstrap 구성으로 Terraform 상태 저장용 S3 버킷을 먼저 생성합니다.
- 암호화·버전 관리·퍼블릭 접근 차단을 설정하고 bootstrap 상태도 생성 후 S3로 이전합니다.
- 환경별 state key를 구분하고 `use_lockfile`로 동시 변경을 막습니다.
- Terraform·AWS Provider 버전과 Provider lock 파일을 저장소에 고정합니다.
- 인프라 관리에는 SSO 또는 임시 자격증명을 사용합니다.
- 첫 구축은 `plan` 검토 후 수동 `apply`로 수행합니다. 일반 앱 push가 인프라 변경까지 실행하지 않도록 분리합니다.
- Terraform 출력은 EC2 식별자, ECR 주소, RDS endpoint 등 비밀이 아닌 값으로 제한합니다.

초기 AWS 자원 구성:

| 구성 요소 | 초기 구성 |
|---|---|
| 리전 | 서울 `ap-northeast-2` |
| VPC | 신규 VPC, DNS 활성화 |
| 서브넷 | EC2용 퍼블릭 1개, 서로 다른 AZ의 RDS용 프라이빗 2개 |
| EC2 | Ubuntu 24.04 LTS x86_64, `t3.small`, 암호화 gp3 30GB |
| 공인 주소 | Elastic IP 1개 |
| RDS | MySQL 8.4.11, `db.t4g.micro`, Single-AZ, 암호화 gp3 20GB, 현재 백업 1일 |
| ECR | 백엔드·Nginx 이미지 저장소 |
| 배포 파일 | Terraform state와 분리한 비공개 S3 버킷 |
| 초기 제외 | ALB, NAT Gateway, Auto Scaling, Kubernetes |

RDS의 정확한 마이너 버전과 인스턴스 조합은 서울 리전에서 제공되는 값을 확인해 고정합니다. EC2 AMI도 확인한 이미지로 고정하여 변경을 검토할 수 있게 합니다.

네트워크 규칙:

- EC2 인바운드는 80·443만 공개합니다. 22·8080은 공개하지 않습니다.
- RDS는 퍼블릭 접근을 비활성화하고 EC2 보안 그룹에서 오는 3306만 허용합니다.
- EC2 퍼블릭 서브넷에는 Internet Gateway 경로를 설정합니다. EC2는 이를 통해 ECR·SSM·OpenAI 등에 접근합니다.
- RDS 프라이빗 서브넷에는 인터넷 기본 경로를 만들지 않습니다.

**완료 기준:** Terraform 재실행 시 불필요한 변경이 없고, state가 Git에 포함되지 않으며, 외부에서 8080·3306에 접근할 수 없습니다.

### 3단계 — EC2 실행 환경·권한·비밀값 준비

- EC2에 Docker, Compose, AWS CLI, SSM Agent를 설치하고 재부팅 후 자동 시작을 설정합니다.
- user data에는 기본 실행 환경 준비만 포함하고 비밀값이나 앱 릴리스를 넣지 않습니다.
- GitHub는 OIDC로 AWS 임시 자격증명을 사용합니다. 실제 저장소의 OIDC subject 형식을 확인해 저장소·운영 환경으로 신뢰 범위를 제한하고 운영 환경은 `main`에서만 사용합니다.

권한 구분:

| 역할 | 권한 범위 |
|---|---|
| 인프라 관리자 | Terraform 실행 및 자원 관리 |
| GitHub 배포 역할 | 지정 ECR push, 배포 파일 S3 업로드, 대상 EC2의 SSM 배포 실행·결과 조회 |
| EC2 역할 | ECR pull, 배포 파일 다운로드, 해당 서비스 비밀값 조회, SSM·로그 전송 |

실행 설정 관리:

| 설정 | 관리 방식 |
|---|---|
| OpenAI 키, JWT 서명 키, 앱 DB 비밀번호 | SSM Parameter Store `SecureString` |
| RDS 관리자 비밀번호 | RDS의 Secrets Manager 연동 |
| RDS 주소·DB명·모델명·포트 | 일반 실행 설정 |
| 프론트엔드 API 주소 | Vercel의 `VITE_API_BASE_URL` |

- 비밀값은 Terraform 변수나 state에 전달하지 않고 별도 등록합니다.
- EC2는 배포 시 비밀값을 읽어 권한이 제한된 런타임 환경 파일로 전달하며, 로그에는 출력하지 않습니다.
- JWT 서명 키는 재배포 때마다 재생성하지 않습니다.
- Spring Boot 컨테이너에는 필요한 앱 실행 설정만 전달합니다.

**완료 기준:** SSH 없이 SSM 관리가 가능하고, EC2가 ECR 이미지와 필요한 비밀값을 읽을 수 있으며, 비밀값이 저장소·이미지·state·로그에 포함되지 않습니다.

### 4단계 — RDS 초기화

- 신규 DB에 현재 사용자·상담·메시지 엔티티에 맞춘 초기 SQL을 적용합니다.
- 초기 SQL은 저장소에서 버전 관리하고 SSM 경유 관리 연결로 실행합니다.
- RDS 관리자 계정은 초기화·관리용으로 사용하고 앱 전용 DB 계정을 별도로 생성합니다.
- 앱 계정에는 필요한 데이터 접근 권한만 부여합니다.
- 운영 앱은 `ddl-auto=validate`로 실행합니다.
- 초기에는 스키마 변경을 운영자가 별도 적용합니다. 앱 배포가 자동으로 테이블을 변경하거나 DB를 되돌리지 않도록 합니다.
- JDBC 연결에는 RDS 인증서 검증을 포함한 TLS를 적용합니다.

**완료 기준:** 앱 전용 계정으로 기동하고, 재시작 후에도 사용자·상담 데이터가 유지됩니다.

### 5단계 — EC2 안의 컨테이너 두 개 연결

- Nginx Dockerfile과 프록시 설정은 기존 `proxy/` 폴더에 작성합니다.
- Compose 서비스는 `nginx`, `app` 두 개로 구성합니다.
- Nginx만 호스트의 80·443 포트를 게시합니다.
- Spring Boot는 컨테이너에서 `0.0.0.0:8080`으로 수신하되 호스트 `ports` 매핑은 두지 않습니다.
- 두 서비스는 동일한 Docker 네트워크에 연결합니다.
- Nginx는 `http://app:8080`으로 프록시하며 `/api/...` 경로를 보존합니다.
- 상태 확인용 `/actuator/health`만 추가 전달하고 상세 정보는 숨깁니다.
- 컨테이너 자동 재시작, 로그 크기 제한, JVM 메모리 상한을 설정합니다.
- 앱 컨테이너 교체 후 Nginx를 reload하여 변경된 컨테이너 주소를 다시 해석합니다.

**완료 기준:** Nginx를 통한 API 호출은 성공하고, EC2 공인 주소의 8080 직접 접속은 실패합니다.

### 6단계 — HTTPS·CORS 구성

도메인과 인증서는 다음 순서로 준비합니다.

1. API용 도메인을 확보하고 DNS A 레코드를 Elastic IP에 연결합니다.
2. HTTP 전용 Nginx로 ACME 인증 경로를 먼저 제공합니다.
3. 호스트의 Certbot으로 인증서를 발급합니다.
4. 인증서 디렉터리를 Nginx에 읽기 전용으로 연결하고 HTTPS를 활성화합니다.
5. 인증 경로 외의 HTTP 요청은 HTTPS로 전환합니다.
6. 호스트 systemd timer로 인증서를 갱신하고 성공 시 Nginx를 reload합니다.

Certbot은 호스트에서 실행하므로 상시 컨테이너 수는 두 개로 유지합니다.

CORS는 Nginx 한 곳에서 처리합니다.

- 확정된 Vercel 운영 origin만 허용합니다.
- `GET`, `POST`, `DELETE`, `OPTIONS`와 `Authorization`, `Content-Type`을 허용합니다.
- 허용된 preflight는 백엔드 인증 전에 204로 응답합니다.
- 오류 응답에도 CORS 헤더를 적용하고 `Vary: Origin`을 설정합니다.
- `*.vercel.app` 전체 허용이나 임의 Origin 반사는 사용하지 않습니다.

채팅은 스트리밍이 아닌 동기 응답입니다. 최초 요청은 문서 임베딩·질문 임베딩·답변 생성을 순차 수행할 수 있습니다. 현재 프론트의 120초 제한을 고려하여 Nginx read timeout은 110초로 시작하고 실제 지연 테스트로 확인합니다.

**완료 기준:** HTTPS, 인증서 갱신, 로그인·채팅의 preflight와 실제 요청이 성공합니다.

### 7단계 — GitHub Actions·ECR·SSM 배포 자동화

초기에는 복잡한 변경 경로 최적화 없이 모든 PR에서 공통 CI를 실행합니다.

PR 검증:

- 백엔드: Java 21, Gradle 테스트·Boot JAR 빌드.
- 프론트엔드: Node 24, `npm ci`, lint·빌드.
- Docker 이미지 빌드 검증.
- 운영 AWS 권한과 비밀값은 제공하지 않음.

`main` 배포:

1. 동일 커밋의 CI 성공을 확인합니다.
2. OIDC로 AWS 배포 역할을 획득합니다.
3. 백엔드·Nginx 이미지를 빌드하고 ECR에 push합니다.
4. 커밋 SHA 태그와 이미지 digest를 기록합니다.
5. Compose·배포 스크립트·digest 목록을 동일 SHA의 릴리스 묶음으로 S3에 업로드합니다.
6. SSM으로 해당 릴리스의 EC2 배포를 요청합니다.
7. EC2에서 이미지와 설정을 먼저 내려받고 컨테이너를 교체합니다.
8. 내부 health와 외부 HTTPS health를 확인한 후 성공 처리합니다.

이미지·배포 정책:

- ECR 태그는 덮어쓰지 않고 실제 배포는 digest로 지정합니다.
- 같은 커밋의 재실행에서는 기존 이미지를 재사용합니다.
- GitHub 배포를 직렬화하고 EC2에도 배포 잠금을 둡니다.
- 실행 중인 배포를 새 push로 강제 취소하지 않습니다.
- 직전 정상 릴리스의 이미지·Compose·설정을 함께 보관합니다.
- 새 앱이 제한 시간 안에 healthy가 되지 않으면 직전 정상 릴리스로 복원합니다.
- 앱 교체 후 Nginx를 reload하고 프록시 경유 health까지 확인합니다.
- 첫 배포 실패는 복원 대상이 없으므로 실패 상태와 로그를 남깁니다.
- SSM 요청 접수만으로 성공 처리하지 않고 원격 실행 종료와 health 결과까지 기다립니다.
- 앱 롤백에 따라 DB를 자동 복원하지 않습니다.

**완료 기준:** `main` 반영부터 배포까지 자동화되고, 잘못된 이미지 배포 시 이전 앱·설정으로 복원됩니다.

### 8단계 — Vercel 연결·운영 확인·백업

Vercel 설정:

- Root Directory: `frontend`.
- Build Command: `npm run build`.
- Output Directory: `dist`.
- 운영 환경변수: `VITE_API_BASE_URL=https://실제-API-도메인`.
- 로그인·회원가입·상담·내역 조회·삭제는 모두 공통 API 주소를 사용합니다.
- OpenAI 키·DB 비밀번호·JWT 서명 키는 프론트엔드 환경변수에 넣지 않습니다.
- Preview origin은 운영 API CORS 허용 목록에 자동 추가하지 않습니다.

Vercel Git 연동으로 자동 빌드하되, Deployment Checks에서 동일 커밋의 CI·백엔드 배포 성공을 확인한 뒤 운영 도메인에 반영합니다. 프론트와 백엔드 변경은 이전 버전과 호환되도록 유지합니다.

운영·복구 설정:

- CloudWatch에 Nginx·앱 로그를 수집하고 보존 기간은 14일로 시작합니다.
- 요청 본문·Authorization 헤더·비밀값은 로그에 남기지 않습니다.
- EC2 상태·메모리·디스크, RDS 저장공간·연결 수, health 실패를 확인할 수 있게 합니다.
- RDS 자동 백업은 서비스 운영 시 7일을 목표로 합니다. 현재 무료 계정은 사용자 선택에 따라 1일이며, 삭제 방지와 삭제 시 최종 스냅샷을 적용했습니다.
- ECR·릴리스 파일 정리 시 현재 운영 및 직전 정상 릴리스는 반드시 보존합니다.
- DB 복구는 새 RDS로 복원·검증한 뒤 연결을 전환합니다.
- 생성 전에 서울 리전 가격으로 EC2·EBS·공인 IPv4·RDS·로그·비밀 관리 비용을 산정하고 예산 알림을 설정합니다. OpenAI·도메인·Vercel 비용도 별도로 포함합니다.
- 실습 종료 시 DB 보존 여부를 확인한 뒤 자원을 정리하고 잔여 EIP·볼륨·스냅샷을 점검합니다.

**완료 기준:** 실제 Vercel 화면에서 회원가입부터 상담 내역 삭제까지 동작하며, EC2 재부팅 복구·인증서 갱신·이전 릴리스 복원·RDS 백업 복원을 각각 확인합니다.

## 4. 인터페이스 변경과 최종 인수 테스트

기존 API 경로와 응답 형식은 유지합니다. 주요 설정 변경은 프론트엔드 API 기본 주소, JWT 만료시간, 운영 DB·비밀값 주입, Nginx CORS·HTTPS 구성입니다.

| 영역 | 통과 기준 |
|---|---|
| 빌드 재현성 | 새 체크아웃에서 Wrapper·Java·Node 설정만으로 테스트·빌드 성공 |
| 네트워크 | 외부에 Nginx 80·443만 공개, RDS는 EC2에서만 접근 가능 |
| HTTPS·CORS | HTTP 전환, 인증서 검증, 허용 origin의 preflight와 실제 요청 성공 |
| 인증 | 회원가입·로그인 성공, 토큰 누락·변조·만료 시 401 |
| 데이터 | 재배포 후 계정·상담 유지, 다른 사용자의 상담 접근 차단 |
| AI | 첫 상담과 후속 상담 성공, 검색 근거 저장, 외부 AI 장애 시 정상 오류 응답 |
| 상태 확인 | health 확인만으로 OpenAI 호출이나 임베딩 비용이 발생하지 않음 |
| 배포 | 실패한 CI는 배포 차단, 실패한 릴리스는 이전 이미지·설정으로 복원 |
| 재기동 | EC2 재부팅 후 Docker·컨테이너·HTTPS 자동 복구 |
| 복구 | RDS 백업을 별도 인스턴스에 복원하고 데이터 확인 |

## 5. 초기 범위와 전제

- 운영 환경은 하나이며, EC2 한 대 안에서 Nginx·Spring Boot 컨테이너 두 개를 상시 실행합니다.
- RDS는 EC2 밖의 별도 관리형 서비스입니다.
- 별도 staging, 무중단 배포, ALB, NAT Gateway, 자동 확장, 다중 AZ 장애 대응은 초기 범위에 포함하지 않습니다.
- EC2 장애나 교체 중에는 서비스 중단이 발생할 수 있습니다.
- 실제 이용자가 늘어 가용성이 중요해지면 ALB·복수 EC2·Multi-AZ RDS로 확장합니다.
- 현재 단계에서는 빌드 재현성, 실행 설정의 안전한 주입, 배포 실패 복구를 먼저 완성합니다.

## 6. 공식 참고 문서

- [Terraform S3 backend 및 상태 잠금](https://developer.hashicorp.com/terraform/language/backend/s3)
- [RDS DB subnet group 요구 사항](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBSubnetGroup.html)
- [GitHub Actions의 AWS OIDC 구성](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [RDS와 Secrets Manager의 비밀번호 관리](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-secrets-manager.html)
- [SSM Agent 연결 요구 사항](https://docs.aws.amazon.com/en_en/systems-manager/latest/userguide/troubleshooting-ssm-agent.html)
- [ECR 이미지 태그 불변성](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html)
- [Let's Encrypt 인증 방식](https://letsencrypt.org/docs/challenge-types/)
- [Vercel Deployment Checks](https://vercel.com/docs/deployment-checks)
