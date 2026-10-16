# 로컬·CI·RDS 스키마 변경 자동화

2026-10-13: 구현·운영 계정 준비·main 반영·실제 자동 배포 검증 완료.
첫 검증 기록은 [DATABASE_MIGRATION_VERIFICATION.json](DATABASE_MIGRATION_VERIFICATION.json)에 저장했습니다.
최신 배포 상태는 GitHub Actions·EC2의 current 릴리스에서 확인합니다.

스키마 변경의 원본은 [`sql/`](sql/)의 버전별 SQL입니다. Gradle이 같은 파일을
백엔드 JAR의 `db/migration`에 넣습니다. 로컬·CI·운영은 Flyway로 이 SQL을 실행합니다.
로컬 DB의 현재 구조나 데이터 덤프를 운영에 자동 복사하지 않습니다.

현재 배포 순서는 V001(계정·상담·메시지) → V002(등록 제품) → V003(MCP 상담·보증 필드) → V004(도구 호출 기록)입니다.
기존 V001·V002는 변경하지 않았습니다. V003은 V002의 제품 테이블을 확장하며 기존 이름·모델·구매일·주소를 유지합니다.
기존 제품의 추가 보증 정보는 NULL로 남겨, 앱이 원래 모델명·구매일에서 보증 입력을 읽습니다.
V003 이후에도 이전 앱은 원래 컬럼으로 제품을 읽고 쓸 수 있습니다. 기존 에어컨 상담은 조회·삭제할 수 있습니다.
`ops/rds/sql`의 V001~V006은 과거 기능 브랜치의 별도 수동 스키마이며 JAR에 포함하지 않습니다.
같은 버전 번호의 다른 SQL을 섞어 적용하거나 Flyway 이력을 삭제/repair하지 않습니다.
해당 수동 스키마를 이미 적용한 DB는 배포 전에 실제 구조·이력을 확인하고 별도 데이터 전환을 준비해야 합니다.

## 개발할 때

1. 엔티티를 변경할 때 `ops/sql/V005__설명.sql`처럼 현재 마지막 버전 다음 번호의 새 파일을 만듭니다.
   이미 적용된 SQL, 특히 기존 `V001__initial_schema.sql`은 수정하지 않습니다.
2. 로컬 MySQL에 SQL을 적용하고 `ddl-auto=validate`로 앱을 실행해 확인합니다.
3. 임시 MySQL에서 마이그레이션과 API 테스트를 통과시킨 뒤 코드·SQL을 함께 커밋합니다.
4. `main` push 시 CI → ECR → EC2의 SQL 적용 → 컨테이너 교체 → HTTPS 확인 순서로 배포됩니다.
   Vercel은 기존과 같이 같은 커밋의 `ci`·`deploy` 성공 후 운영 도메인에 반영합니다.

예를 들어 **선택 항목인 새 컬럼**을 추가할 때 새 SQL에 다음 형태를 사용합니다.
실제 컬럼과 타입은 엔티티에 맞춰 작성합니다. 아래 예시는 운영에 추가되어 있지 않습니다.

```sql
ALTER TABLE app_users ADD COLUMN display_name VARCHAR(100) NULL;
```

엔티티만 수정하거나 SQL 없이 로컬 DB를 직접 변경하면 운영 스키마는 바뀌지 않습니다.
운영 Hibernate는 SQL을 대신 생성하지 않습니다. 로컬 기본값은 `update`지만, 배포 검증은 반드시 Flyway와 `validate`로 수행합니다.

### 로컬 DB에 적용

자신이 관리하는 빈 개발 DB와 DDL 가능한 로컬 계정을 준비한 뒤 `backend`에서 실행합니다.
이미 Hibernate로 생성한 비어 있지 않은 DB는 임의로 baseline하지 않습니다.
기존 데이터를 보존하려면 덤프와 V001 구조의 차이를 검토하고 별도 전환해야 합니다.
우선 새 빈 개발 DB를 사용하는 편이 간단합니다. 데이터베이스 자체 생성은 Flyway 작업 범위가 아닙니다.

```bash
export MIGRATION_JDBC_URL='jdbc:mysql://127.0.0.1:3306/project_db'
export MIGRATION_USERNAME='spring'
read -s 'MIGRATION_PASSWORD?로컬 DB 비밀번호: '
export MIGRATION_PASSWORD
./gradlew --no-daemon migrateDatabase
unset MIGRATION_PASSWORD
./gradlew bootRun --args='--spring.jpa.hibernate.ddl-auto=validate'
```

위 비밀번호 입력 문법은 macOS 기본 zsh용입니다. 개발 DB의 주소·계정이 다르면 바꿉니다.
앱의 `SPRING_DATASOURCE_*`도 같은 개발 DB를 가리켜야 합니다.
마이그레이션 명령은 OS 환경 변수만 사용하며 `.env`를 읽거나 OpenAI를 호출하지 않습니다.
운영 RDS 비밀번호를 개발 PC에 복사할 필요가 없습니다.

### 임시 MySQL 검증

`backend`에서 실행합니다. 이 DB는 localhost 3307·tmpfs이며 운영 RDS와 연결되지 않습니다.

```bash
docker compose -p homecare-migration-check -f compose.mysql-test.yaml up -d --wait
./gradlew --no-daemon migrationTest mysqlSchemaTest mysqlTest
docker compose -p homecare-migration-check -f compose.mysql-test.yaml down -v
```

`migrationTest`는 빈 DB 생성, 기존 V001 등록, 재실행, nullable 컬럼 추가와 데이터 보존,
적용된 SQL의 수정 감지, 부분 DDL 실패와 재실행 거부, `clean` 금지를 확인합니다.
업그레이드·실패 SQL은 테스트 임시 폴더에만 만들어집니다. 운영 SQL에 테스트 V002·V003을 넣지 않습니다.
`mysqlTest`의 기존 API 테스트도 Flyway가 생성한 스키마에서 Hibernate `validate`로 실행됩니다.
H2 테스트는 기존처럼 별도 메모리 DB에서 `create-drop`으로 실행합니다.

## 운영 계정과 최초 전환

`homecare_app`은 DDL 권한이 없으며, 데이터 읽기·쓰기만 합니다.
전용 `homecare_migrator@10.20.1.0/24`는 TLS를 사용하고 `project_db`에 한정한
SELECT·INSERT·UPDATE·DELETE·CREATE·ALTER·INDEX·REFERENCES와 GRANT OPTION을 갖습니다.
GRANT OPTION은 새 앱 테이블의 CRUD 권한을 앱 계정에 부여하기 위해 필요합니다.
MySQL에서는 이 옵션이 같은 DB 수준의 권한 전체에 적용됩니다.
DROP·CREATE USER·다른 DB 권한·전역 관리 권한은 부여하지 않습니다.
컬럼 삭제는 ALTER 권한으로 가능하므로 SQL 검토가 필요합니다.

비밀번호는 `/homecare/prod/MIGRATION_DATASOURCE_PASSWORD` SecureString에 저장합니다.
Git·이미지·Terraform state에 비밀번호를 넣지 않습니다. EC2의 기존 파라미터 읽기 범위에 포함되어
새 Terraform 리소스나 GitHub Secret은 필요하지 않습니다.
앱 런타임 설정 writer는 이 키를 읽지 않으며, 마이그레이션 전용 임시 컨테이너만 전달받습니다.
상시 컨테이너는 `backend`·`nginx`·내부 전용 `mcp` 세 개입니다.

최초 한 번, **기존 RDS가 V001 상태일 때만** 로컬 관리자 AWS 자격증명으로 계정을 준비합니다.

```bash
python3 ops/run-rds-initialization.py --migration-account
```

명령은 새 SecureString이 없을 때만 생성하고 기존 값을 덮어쓰지 않습니다.
RDS 관리자 비밀 읽기 권한을 EC2에 30분 만료 조건으로 잠시 부여하고 종료 시 제거합니다.
앱 데이터·앱 권한·기존 DB 비밀번호를 변경하거나 새 앱 테이블을 만들지 않습니다.
기존 테이블·V001 SHA-256·앱 권한을 먼저 확인하고 별도 마이그레이션 계정을 만듭니다.
중단 시 남은 임시 정책은 [초기화 안내](RDS_INITIALIZATION.md)의 절차로 확인·제거합니다.

첫 자동 배포는 기존 `schema_migrations`의 V001 SHA-256과 알려진 테이블 구성을 확인한 뒤
Flyway의 `flyway_schema_history`에 버전 1 baseline을 등록합니다.
기존 앱 테이블과 데이터에는 V001의 CREATE TABLE을 재실행하지 않습니다.
이후에도 기존 V001 SHA-256은 별도로 확인합니다. Flyway는 새 SQL의 적용 이력·checksum을 관리합니다.
신규 빈 개발 DB에는 V001부터 정상 실행합니다. 알 수 없는 기존 DB에는 자동 baseline하지 않습니다.
전환 이후에는 초기화·계정 준비 명령을 반복 실행하지 않습니다. 적용 상태가 달라지면 중단하도록 설계했습니다.

## 배포 실패와 호환성

EC2의 기존 배포 잠금 안에서 이미지를 pull한 후, 새 백엔드 이미지의 별도 Java 진입점으로
Flyway를 실행합니다. HTTP 서버·Spring·OpenAI는 시작하지 않습니다.
SQL은 이미지에 포함되어 있어 CI에서 검증한 커밋과 같습니다.
임시 컨테이너는 root가 아닌 사용자, 읽기 전용 파일 시스템, 메모리 제한으로 실행하고 종료 후 제거합니다.
전용 환경 파일은 root 0600이며 종료 시 삭제합니다. Flyway의 원시 SQL·접속 정보 로그는 CI에 출력하지 않습니다.
연결·소켓·DB 잠금 대기와 컨테이너 실행에 제한 시간을 둡니다.

SQL 실패는 컨테이너 교체·런타임 설정 변경 전에 배포를 중단합니다.
새 테이블에는 CRUD를 부여하지만 `schema_migrations`·`flyway_schema_history`는 앱에 공개하지 않습니다.
SQL 성공 후 앱 기동이 실패하면 기존 앱·설정으로 복원하며 **DB 변경은 남습니다**.
따라서 새 컬럼을 먼저 nullable로 추가하는 등 이전 앱과 호환되도록 변경해야 합니다.
컬럼 이름 변경·삭제나 필수 제약 추가는 호환 단계와 데이터 전환을 거쳐 여러 배포로 나눕니다.

MySQL DDL은 전체 마이그레이션을 트랜잭션으로 되돌리지 못할 수 있습니다.
부분 실패·시간 초과 시 적용 상태와 `flyway_schema_history`를 관리자가 확인합니다.
자동 `repair`·`clean`·DB 롤백은 실행하지 않습니다. 재배포로 오류를 숨기지 않습니다.
운영 백업은 현재 무료 플랜 기준 1일이며 복구 절차는 [운영 안내](OPERATIONS.md)를 참조합니다.

참고: [Spring Boot의 DB 초기화·Flyway 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## 실제 검증 결과

2026-10-16 MCP 통합 준비 검증(운영 DB 변경 없음):

- 기존 `ops/sql` V001·V002에서 V003·V004로 업그레이드, 재실행과 원래 제품·상담 데이터 보존을 일회용 MySQL 8.4에서 확인했습니다.
- 패키징한 네 SQL의 원본 일치와 Hibernate `validate` 기동, 기존 제품의 모델·구매일 조회 및 이전 앱 방식의 쓰기를 확인했습니다.
- 읽기 전용 amd64 Docker 이미지의 마이그레이션 진입점, 백엔드 기동, MCP HTTP 도구 9개 조회와 JWT 인증 도구 호출을 확인했습니다.

아래는 2026-10-13 최초 자동 배포의 검증 기록입니다.

- 로컬 H2 API 56개, MySQL API 56개, 마이그레이션 업그레이드·실패 검증, 운영 스크립트 27개 통과.
- 실제 amd64 이미지의 별도 Java 진입점·TLS·기존 V001 baseline·재실행·새 테이블 CRUD 부여 확인.
- [첫 GitHub CI/CD](https://github.com/gwonu1/core_project/actions/runs/38341086240) 통과 및 동일 커밋 Vercel 운영 승격 확인.
- 실제 RDS의 V001 baseline·기존 앱 테이블 구조/행 수/권한 유지 확인. 앱에는 마이그레이션 비밀번호를 전달하지 않음.
- 마이그레이션 임시 파일·컨테이너 정리, 상시 컨테이너 두 개 healthy, 공개 HTTPS health UP 확인.
- 추가 컬럼과 부분 실패는 임시 MySQL에서만 검증했으며 운영에는 테스트 V002·V003을 적용하지 않음.
