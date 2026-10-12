# HomeCare AI 백엔드

사용자 계정과 대화 내역은 MySQL에 저장하고, 로그인 시 발급한 JWT로 API를 인증합니다.
Spring AI로 가상 수리 매뉴얼을 검색하고, 검색 근거와 고객 문의를 OpenAI에 전달해 답변합니다.

## 실행

프로젝트 루트에서 실행합니다.

```bash
cd backend
./gradlew bootRun
```

`backend`를 작업 디렉터리로 사용해야 `.env`를 읽습니다. Java 21과 의존성을 다운로드할
인터넷 연결이 필요하며, Gradle은 포함된 wrapper를 사용합니다.

기존 `backend/.env`를 자동으로 읽습니다. 따옴표가 있는 값도 지원합니다.
필수 변수는 다음과 같습니다. 현재 로컬 `.env`에는 무작위 `JWT_SECRET`이 추가되어 있습니다.

```dotenv
OPENAI_API_KEY="your-openai-api-key"
OPENAI_MODEL=gpt-4o-mini
JWT_SECRET=your-base64-encoded-signing-key
JWT_EXPIRATION_MS=3600000
```

`JWT_SECRET`은 32바이트 이상의 무작위 값을 Base64로 인코딩한 서명 키입니다.
새 환경에서는 `openssl rand -base64 32`로 생성한 값을 사용합니다.
키가 바뀌면 기존 로그인 토큰은 무효화됩니다. 토큰의 기본 유효 기간은 1시간입니다.
`JWT_EXPIRATION_MS`로 유효 기간을 밀리초 단위로 변경할 수 있습니다.
운영체제 환경 변수와 Spring 실행 옵션이 `.env`보다 우선합니다.
API 키, 모델, JWT 서명 키를 모두 설정해야 합니다.
실제 채팅 호출에는 해당 키의 모델 접근 권한과 API 사용 가능한 잔액이 필요합니다.
키는 소스에 저장하지 않으며 `.env`는 기존 `.gitignore`로 제외됩니다.

서버는 `127.0.0.1:8080`에서 실행됩니다. 포트를 바꾸려면 다음처럼 실행합니다.

```bash
./gradlew bootRun --args='--server.port=8081'
```

## MySQL

예제 `temp_project/todolist`의 계정 정보를 사용하며 DB 이름은 `project_db`입니다.
MySQL 서버와 `project_db`가 먼저 준비되어 있어야 합니다.

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/project_db
spring.datasource.username=spring
spring.datasource.password=spring1234
```

다른 환경에서는 실행 환경변수 또는 `.env`의 `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 변경할 수 있습니다.
설정 예시는 `.env.example`에 있으며, 실제 비밀값은 Git에 저장하지 않습니다.
로컬 개발용 `ddl-auto=update` 설정으로 기동 시 필요한 테이블을 생성합니다.
`app_users`는 계정, `chat_conversations`는 사용자별 대화 제목과 생성·갱신 시간,
`chat_messages`는 대화에 속한 질문·답변과 작성 시간을 저장합니다.
`chat_conversations.category`는 제품 카테고리를 저장합니다. 기존 기록은 `null`로 유지합니다.
`chat_messages.sources`에는 답변 생성에 전달한 검색 근거를 JSON 문자열로 저장합니다.
기존 메시지의 근거는 비어 있으며, 가상 매뉴얼이 나중에 바뀌어도 저장된 근거 본문은 유지됩니다.
테이블에는 `id`, 고유한 `email`, BCrypt 해시인 `password`, `role`을 저장합니다.
사용자 계정과 대화 내역은 서버를 재시작해도 유지됩니다.

## API

### 회원가입·로그인

```bash
curl -X POST http://127.0.0.1:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password123"}'

curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password123"}'
```

회원가입은 201 상태와 `{"id":1,"email":"user@example.com","role":"USER"}`를 반환합니다.
로그인은 200 상태와 `{"accessToken":"JWT","email":"user@example.com"}`를 반환합니다.
회원가입 후 로그인해야 하며, 비밀번호는 응답에 포함하지 않습니다.
이메일은 소문자로 저장하고 비교합니다. 이미 가입된 이메일은 409,
잘못된 로그인 정보는 401, 유효하지 않은 입력은 400을 반환합니다.
가입 비밀번호는 8자 이상이며, BCrypt 제한에 따라 UTF-8 기준 72바이트 이하여야 합니다.

### 서버 상태

```bash
curl http://127.0.0.1:8080/actuator/health
```

```json
{ "status": "UP" }
```

이 API는 인증 없이 서버와 DB 연결 상태를 확인하며 LLM을 호출하지 않습니다.

### 채팅

```bash
curl -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN' \
  -d '{"message":"세탁기에 HC-E01이 뜨고 물이 안 들어와요.","category":"WASHER"}'
```

응답 예시:

```json
{
  "reply": "가상 매뉴얼에서 HC-E01은 급수 지연을 의미합니다. [HC-WS-001]",
  "conversationId": 1,
  "sources": [
    {
      "documentId": "HC-WS-001",
      "title": "HC-E01: 급수 지연",
      "category": "WASHER",
      "version": "1.0",
      "file": "rag-test-data/manuals/washer.md",
      "content": "매뉴얼 해당 항목의 전체 본문 (이 예시에서는 생략)"
    }
  ]
}
```

`message`는 공백만으로 구성되지 않은 1~4000자의 문자열이어야 합니다.
로그인 응답의 토큰을 `Authorization: Bearer <accessToken>` 헤더로 전달해야 합니다.
토큰이 없거나 변조·만료된 경우 401을 반환하고 LLM을 호출하지 않습니다.
답변은 한국어이며, 제공된 가상 매뉴얼에 근거하도록 요청합니다. 실제 생성 문장은 예시와 다를 수 있습니다.
동기 방식으로 전체 답변을 한 번에 반환합니다.
`conversationId`를 생략하거나 `null`로 보내면 새 대화를 만듭니다.
기존 대화에 질문을 추가하려면 `{"message":"추가 질문","conversationId":1}`처럼 전달합니다.
새 상담에는 `{"message":"탈수할 때 소리가 커요","category":"WASHER"}`처럼 제품 카테고리를 지정할 수 있습니다.
지원 값은 `AIR_CONDITIONER`, `REFRIGERATOR`, `WASHER`, `DRYER`, `DISHWASHER`입니다.
카테고리는 대화에 저장되며 목록·상세 응답의 `category` 필드로 반환합니다.
후속 요청에서 카테고리를 생략하면 저장된 값을 사용하며, 다른 카테고리로 변경하려 하면 400을 반환합니다.
선택한 제품 카테고리를 LLM 질문에 함께 전달하되 DB의 질문 본문은 고객이 입력한 원문으로 저장합니다.
이전 클라이언트와 기존 기록을 위해 카테고리는 선택 항목입니다. 미지정 상담은 LLM 호출 없이 제품 선택을 안내합니다.
첫 질문의 앞 50자를 대화 제목으로 사용합니다. 성공한 질문·답변은 하나의 트랜잭션으로 저장하며,
AI 호출이 실패하거나 답변이 비어 있으면 대화 내역을 변경하지 않습니다.

### RAG 동작

1. `rag-test-data/manuals/*.md`를 `## HC-...` 단위로 나누어 20개 문서를 읽습니다. Gradle 빌드 시 매뉴얼을 JAR에 포함하므로 실행 위치와 관계없이 읽을 수 있습니다.
2. 선택한 카테고리와 오류 코드를 확인합니다. 카테고리가 질문 속 제품과 다르면 확인 질문을 반환하며, 해당 제품 자료에 없는 `HC-E...` 코드는 의미를 만들어 답하지 않습니다.
3. 최초 검색 시 `text-embedding-3-small`로 20개 문서를 배치 임베딩해 Spring AI `SimpleVectorStore`에 보관합니다. 이후에는 질문만 임베딩합니다.
4. 제품명을 포함한 질문으로 검색하고 카테고리 메타데이터로 범위를 제한합니다. 유사도 0.30 이상인 상위 2개 문서를 찾습니다. 명시한 오류 코드가 있으면 그 코드의 문서를 우선 포함하며, 중복을 제거해 최대 3개 항목을 전달합니다.
5. 검색한 매뉴얼과 질문을 `ChatClient`에 전달합니다. 근거에 없는 원인·수리비·보증 정책을 만들지 않도록 요청합니다. 검색 결과가 없으면 LLM 호출 없이 정보 부족 안내를 반환합니다.
6. 답변과 검색 근거를 함께 저장하고 `sources`로 반환합니다. `GET /api/conversations/{id}`의 각 메시지에도 `sources`가 포함됩니다.

벡터 DB나 추가 서버 설치는 필요하지 않습니다. MySQL은 계정·상담·근거 저장에 사용하고,
벡터 인덱스는 메모리에만 유지하므로 서버 재시작 후 첫 검색에서 다시 임베딩합니다.
서버 기동과 health 확인은 외부 AI 호출 없이 가능하며, 최초 상담은 인덱스 생성 때문에 더 오래 걸릴 수 있습니다.
임베딩과 답변 생성에는 기존 OpenAI API 키를 사용합니다. 임베딩 실패 시 일반 LLM 답변으로 우회하지 않고 502를 반환합니다.

`sources`는 **답변 생성에 전달한 검색 결과**이며, 모든 항목을 답변에서 실제로 인용했다는 뜻은 아닙니다.
근거 존재 여부와 답변의 근거 준수 여부는 별도로 평가해야 합니다. 생성 답변의 모든 문장을 자동 검증하지는 않습니다.
카테고리가 없는 기존 상담은 조회할 수 있지만, 새로운 근거 기반 상담은 카테고리를 선택해 시작하세요.
이전 메시지는 LLM에 전달하지 않으므로 후속 질문에도 오류 코드와 증상을 적어야 합니다.

가상 매뉴얼을 변경했다면 서버를 다시 빌드·시작하면 반영됩니다.
[데이터 설명과 평가 질문](rag-test-data/README.md)을 참고하세요. 평가 질문 파일은 검색 문서에 포함하지 않습니다.

| HTTP 상태 | 의미                                              |
| --------- | ------------------------------------------------- |
| 200       | 답변 성공                                         |
| 400       | 빈 메시지, 길이 초과, 누락되거나 잘못된 JSON 본문 |
| 401       | 로그인 필요 또는 유효하지 않은 JWT                |
| 404       | 존재하지 않거나 다른 사용자의 대화                |
| 502       | LLM 인증·통신 실패, 시간 초과 또는 빈 답변        |

오류 응답은 `{"message":"오류 설명"}` 형식입니다. 외부 API의 오류 내용이나 키를
응답에 포함하지 않습니다. OpenAI 요청 제한 시간은 30초이며 자동 재시도는 하지 않습니다.

### 이전 대화 조회

두 API 모두 로그인 토큰이 필요합니다. 사용자 정보는 JWT에서 가져오며,
다른 사용자의 대화는 조회하거나 메시지를 추가할 수 없습니다.

```bash
curl http://127.0.0.1:8080/api/conversations \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'

curl http://127.0.0.1:8080/api/conversations/1 \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

목록은 마지막 메시지 저장 시간의 내림차순이며 메시지 본문은 포함하지 않습니다.

```json
[
  {
    "id": 1,
    "title": "Spring Boot가 무엇인지 간단히 설명해 줘.",
    "createdAt": "2026-10-11T09:00:00Z",
    "updatedAt": "2026-10-11T09:00:00Z"
  }
]
```

상세 응답은 메시지를 저장 순서대로 반환합니다.

```json
{
  "id": 1,
  "title": "Spring Boot가 무엇인지 간단히 설명해 줘.",
  "messages": [
    {
      "id": 1,
      "role": "user",
      "content": "Spring Boot가 무엇인지 간단히 설명해 줘.",
      "createdAt": "2026-10-11T09:00:00Z"
    },
    {
      "id": 2,
      "role": "assistant",
      "content": "Spring Boot는 Spring 애플리케이션 개발을 돕는 프레임워크입니다.",
      "createdAt": "2026-10-11T09:00:00Z"
    }
  ]
}
```

저장된 대화가 없으면 목록은 `[]`입니다. 존재하지 않거나 본인 소유가 아닌 대화 ID는
동일하게 404와 `{"message":"대화를 찾을 수 없습니다."}`를 반환합니다.
이 기능을 추가하기 전에 화면에만 표시했던 대화는 복원할 수 없습니다.

### 상담 삭제

```bash
curl -X DELETE http://127.0.0.1:8080/api/conversations/1 \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

본인 소유의 상담과 포함된 메시지를 하나의 트랜잭션으로 삭제하고, 응답 본문 없이 204를 반환합니다.
다른 상담과 사용자 계정은 유지됩니다. 존재하지 않거나 다른 사용자의 상담은 404,
인증이 없거나 토큰이 유효하지 않으면 401을 반환합니다. 삭제한 기록은 복구할 수 없습니다.

## 구조

```text
src/main/java/com/project/backend/
├── BackendApplication.java        # .env 로딩과 서버 기동
├── auth/                         # 회원가입·로그인 Controller, Service, DTO
├── user/                         # 사용자 엔티티, Repository, UserDetailsService
├── config/                       # Spring Security, JWT 서명·검증 설정
├── security/                     # JWT 발급
├── conversation/                 # 대화·메시지 엔티티, Repository, 조회·저장 API와 DTO
├── rag/                          # 가상 매뉴얼 로딩, 임베딩 검색, 근거 DTO
└── chat/
    ├── ChatController.java        # HTTP 요청과 입력 검증
    ├── ChatService.java           # 검색 근거와 질문을 Spring AI ChatClient에 전달
    ├── dto/
    │   ├── ChatRequest.java
    │   └── ChatResponse.java
    └── exception/
        ├── ChatUnavailableException.java
        └── GlobalExceptionHandler.java
```

채팅은 요청마다 독립적으로 답변하며 이전 대화 내용을 기억하지 않습니다.
저장된 기록은 조회용이며, 기존 대화에 질문을 추가하더라도 이전 메시지를 LLM에 전달하지 않습니다.
Spring Security의 JWT 검증 필터를 사용하며 인증용 쿠키나 HTTP 세션은 사용하지 않습니다.
로그아웃은 프론트엔드에서 토큰을 버리는 방식이며 서버의 토큰 폐기 목록은 없습니다.

## 검증 및 JAR 실행

```bash
./gradlew clean test bootJar
java -jar build/libs/app.jar
```

테스트는 로컬 모의 OpenAI 서버를 사용하여 실제 Spring AI HTTP 호출, 요청 모델과 인증 헤더,
성공 응답, 입력 검증, 외부 API 오류, 빈 답변, health API를 확인합니다.
회원가입, 비밀번호 해시 저장, 중복 이메일, 로그인 실패, 인증된 채팅,
비로그인·변조·만료 토큰 차단도 검증합니다.
대화·메시지 저장과 순서, 최신 대화 정렬, 재인증 후 조회, 사용자 간 접근 차단,
잘못된 대화 ID, AI 실패 시 기록 유지, 긴 답변과 유니코드 제목도 검증합니다.
기본 테스트는 H2 메모리 DB와 가짜 API 키를 사용하므로 실제 MySQL 데이터나 LLM 사용량에 영향을 주지 않습니다.
임베딩 모의 응답으로 카테고리 필터, 오류 코드 근거 우선 포함, 코드 없는 질문의 벡터 검색,
근거의 저장·재조회, 미등록 코드와 제품 불일치, 검색 결과 없음, 임베딩 실패도 검증합니다.
이 테스트는 실제 OpenAI의 검색 정확도나 답변 품질을 보장하지 않습니다.

### MySQL 8.4 검증

Docker를 실행한 상태에서 아래 명령으로 별도 테스트 MySQL을 기동합니다.
테스트는 테이블을 생성·삭제하므로 개발·운영 DB와 분리된 `backend_test`만 사용합니다.
테스트 DB는 로컬 3307 포트를 사용하며 데이터는 컨테이너 종료 시 제거됩니다.

```bash
docker compose -p core-stage1-test -f compose.mysql-test.yaml up -d --wait
./gradlew mysqlTest
docker compose -p core-stage1-test -f compose.mysql-test.yaml down
```

`mysqlTest`는 기본 테스트와 같은 API·인증·상담·검색 근거 검증을 MySQL에서 수행합니다.
테스트 실패 시에도 마지막 `down` 명령으로 테스트 DB를 정리하세요.

### Docker 이미지 빌드

```bash
./gradlew clean test bootJar
docker build -t core-backend:local .
```

Docker 빌드에는 `build/libs/app.jar`만 전달하며 `.env`와 소스·캐시는 제외합니다.
`compose.yaml`은 `backend` 서비스의 로컬 단독 실행용입니다. EC2에서는
`../ops/compose.prod.yaml`의 `backend`, `nginx` 두 서비스를 사용하며
앱 포트는 호스트에 공개하지 않습니다. [운영 배포 안내](../ops/SERVICE_DEPLOYMENT.md)를 참고합니다.

공식 문서: [Spring AI 시작하기](https://docs.spring.io/spring-ai/reference/getting-started.html),
[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html),
[OpenAI 연동 설정](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html),
[벡터 검색](https://docs.spring.io/spring-ai/reference/api/vectordbs.html),
[dotenv-java](https://github.com/cdimascio/dotenv-java),
[Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Spring Data JPA 트랜잭션](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).
