# RDS 초기화와 TLS 검증

대상: 서울 리전 `528215464049` 계정의 `homecare-prod-mysql`, DB `project_db`.
실행 날짜: 2026-10-12. DB 연결과 초기화는 퍼블릭 서브넷 EC2에서 SSM을 통해 수행합니다.

## 초기 스키마와 앱 계정

`sql/V001__initial_schema.sql`에 현재 JPA 엔티티의 초기 스키마를 저장했습니다.
사용자 `app_users`, 상담 `chat_conversations`, 메시지·검색 근거 `chat_messages`를
InnoDB·utf8mb4로 생성합니다. 이메일 중복 방지와 두 외래 키를 포함합니다.
적용 버전·SQL SHA-256·적용 시각은 관리자용 `schema_migrations`에 기록합니다.

앱 계정은 `homecare_app`이며 접속 호스트는 EC2 서브넷 `10.20.1.0/24`로 제한합니다.
`REQUIRE SSL`을 적용하고 위 세 테이블의 SELECT·INSERT·UPDATE·DELETE만 허용합니다.
스키마 이력이나 다른 DB·테이블은 읽을 수 없고 CREATE·ALTER·DROP·GRANT 권한이 없습니다.
비밀번호는 기존 Parameter Store SecureString을 사용하며 재실행 시 바꾸지 않습니다.
RDS 보안 그룹은 EC2 보안 그룹에서 오는 3306 연결만 허용합니다.

## TLS 준비

`prepare-rds-tls.sh`를 SSM으로 EC2에서 root 권한으로 실행했습니다.
DB 초기화·검증용 `python3-pymysql`과 truststore 생성용 Java 21 keytool을 설치합니다.
AWS 공식 서울 리전 번들을 HTTPS로 내려받아 자기 서명 루트 CA만 확인·등록합니다.

| 호스트 파일 | 용도 |
|---|---|
| `/opt/homecare/config/rds-roots.pem` | Python 관리 연결의 인증서·호스트 검증 |
| `/opt/homecare/config/rds-truststore.p12` | Spring Boot JDBC의 명시적 PKCS12 truststore |

두 파일은 공개 CA 인증서만 담고 root 소유 0644입니다. 설정 디렉터리는 0700을 유지합니다.
앱 컨테이너에는 PKCS12 파일을 `/app/config/rds-truststore.p12`에 읽기 전용으로 연결합니다.
JDBC는 이미 등록한 `sslMode=VERIFY_IDENTITY` 설정을 사용합니다.
`changeit`은 공개 인증서 저장소의 비밀번호이며 DB 자격증명이 아닙니다.

AWS는 앱 truststore에 루트 CA만 등록하도록 안내합니다.
인증서 갱신 시 이 스크립트로 CA 파일을 준비하고 앱 연결을 검증한 뒤 반영합니다.
[AWS RDS TLS·인증서 번들 안내](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html).

## 초기화 실행과 재실행

EC2에 위 TLS 파일과 Python 패키지가 준비된 상태에서 프로젝트 루트에서 실행합니다.
로컬 AWS CLI는 기존 관리자 자격증명을 사용합니다.

```bash
python3 ops/run-rds-initialization.py
```

이 명령은 다음 순서로 실행합니다.

1. AWS 계정과 RDS 관리자 비밀 ARN을 확인합니다. ARN은 비밀번호가 아닙니다.
2. EC2 역할에 해당 비밀 하나의 GetSecretValue 권한을 임시로 추가합니다.
   권한은 30분 뒤 만료되고, 실행 종료 시 `finally`에서 제거합니다.
3. 비밀값이 없는 초기화 코드·SQL만 SSM으로 전달합니다.
4. EC2에서 관리자 비밀과 앱 DB 설정을 메모리로 읽고 인증서·호스트를 검증해 접속합니다.
5. 초기 SQL과 앱 계정을 적용하고 TLS·권한·CREATE 거부를 확인합니다.

관리자 비밀번호를 런타임 환경 파일·SSM 명령·Git·Terraform state에 저장하거나 출력하지 않습니다.
앱에는 관리자 비밀 조회 권한을 상시 부여하지 않습니다.

처음에는 빈 DB에만 SQL을 적용합니다. 재실행 시 적용된 V001의 checksum을 확인하고
DDL을 건너뜁니다. 알려지지 않은 테이블이나 부분 적용 상태는 수정하지 않고 중단합니다.
MySQL DDL은 전체가 하나의 트랜잭션으로 되돌려지지 않으므로, 부분 실패 시
관리자가 실제 상태를 확인해야 합니다. 이미 적용한 V001 파일은 수정하지 않습니다.
후속 변경은 별도 SQL로 검토·백업 후 관리자 연결에서 적용합니다.

후속 자동화에서는 [DB 마이그레이션](DATABASE_MIGRATIONS.md)을 사용합니다.
Flyway 전환 뒤에는 이 초기화 명령을 반복하지 않습니다.
기존 V001은 변경하지 않고 새 버전 SQL을 CI에서 검증해 배포 전에 적용합니다.

프로세스가 강제 종료되어 임시 정책이 남았다면 실행 상태를 먼저 확인한 뒤 제거합니다.
기존 임시 정책이 있으면 초기화 명령은 덮어쓰지 않고 중단합니다.

```bash
aws iam delete-role-policy --role-name homecare-prod-ec2 \
  --policy-name homecare-rds-initialization-temporary
```

## 검증

실제 EC2·RDS 검증을 완료했습니다. 비암호화 접속과 신뢰하지 않는 CA는 거부됐으며,
앱의 CREATE·초기화 이력 조회와 임시 정책 제거 후 관리자 비밀 조회도 거부됐습니다.
스키마·권한·앱 기동·설정 보호 관련 로컬 테스트 총 13개를 통과했습니다.

초기 SQL은 실제 RDS 적용 전에 임시 로컬 MySQL 8.4에서 Spring Boot의
`ddl-auto=validate`와 health UP으로 검증했습니다.

`verify-rds.py`는 EC2에서 지정 백엔드 이미지를 임시로 실행합니다.
호스트 포트는 `127.0.0.1:18084`만 사용하며 JDBC·앱 계정·CA 설정은 운영과 동일합니다.
검증용 OpenAI 키로 덮어쓰고 AI API를 호출하지 않습니다.

```bash
# EC2에서 root 권한으로, ECR 검증 이미지를 pull한 뒤 실행
python3 /usr/local/lib/homecare/verify-rds.py --image <검증할_백엔드_이미지>

# 로컬에서 초기화·권한·설정 파일의 보호 동작 검사
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s ops/tests -v
```

검증 범위는 Hibernate validate 기동·health, 회원가입·로그인,
앱 계정의 상담·메시지 저장·수정·조회·삭제, 한글·이모지·검색 근거 변환,
재시작 후 데이터·JWT 유지입니다. 이 실행에서 만든 임시 컨테이너와 UUID로 구분한
검증 사용자·상담·메시지만 정리합니다. 기존 데이터는 삭제하지 않습니다.
이번 실행의 검증 데이터·컨테이너·EC2 이미지 및 ECR 임시 태그·자식 manifest를 제거했습니다.

현재 `mysqlTest`는 Flyway로 같은 SQL을 적용하고 Hibernate `validate`로 실행합니다.
운영 RDS에 연결하지 않고 별도 테스트 MySQL에서 실행합니다.

상시 앱·Nginx 배포는 5단계이며 이번 단계의 컨테이너는 검증용입니다.
