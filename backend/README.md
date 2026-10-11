# AI 챗봇 백엔드

Java 21, Spring Boot 4.1.1, Spring AI 2.0.1을 사용하는 로컬 REST 서버입니다.
`temp_project/todolist`의 생성자 주입, Controller → Service → DTO 구조를 따릅니다.
사용자 계정과 대화 내역은 MySQL에 저장하고, 로그인 시 발급한 JWT로 API를 인증합니다.
Spring AI의 `ChatClient`로 OpenAI에 메시지를 전달하고 JSON으로 답변합니다.

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
```

`JWT_SECRET`은 32바이트 이상의 무작위 값을 Base64로 인코딩한 서명 키입니다.
새 환경에서는 `openssl rand -base64 32`로 생성한 값을 사용합니다.
키가 바뀌면 기존 로그인 토큰은 무효화됩니다. 토큰의 유효 기간은 1시간입니다.
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

다른 환경에서는 `.env`의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 변경할 수 있습니다.
로컬 개발용 `ddl-auto=update` 설정으로 기동 시 필요한 테이블을 생성합니다.
`app_users`는 계정, `chat_conversations`는 사용자별 대화 제목과 생성·갱신 시간,
`chat_messages`는 대화에 속한 질문·답변과 작성 시간을 저장합니다.
`chat_conversations.category`는 제품 카테고리를 저장합니다. 기존 기록은 `null`로 유지합니다.
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
{"status":"UP"}
```

이 API는 인증 없이 서버와 DB 연결 상태를 확인하며 LLM을 호출하지 않습니다.

### 채팅

```bash
curl -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN' \
  -d '{"message":"Spring Boot가 무엇인지 간단히 설명해 줘."}'
```

응답 예시:

```json
{"reply":"Spring Boot는 Spring 기반 애플리케이션을 쉽게 만들고 실행하도록 도와주는 프레임워크입니다.","conversationId":1}
```

`message`는 공백만으로 구성되지 않은 1~4000자의 문자열이어야 합니다.
로그인 응답의 토큰을 `Authorization: Bearer <accessToken>` 헤더로 전달해야 합니다.
토큰이 없거나 변조·만료된 경우 401을 반환하고 LLM을 호출하지 않습니다.
답변은 기본적으로 한국어이며 사용자가 다른 언어를 요청하면 그 언어로 답합니다.
동기 방식으로 전체 답변을 한 번에 반환합니다.
`conversationId`를 생략하거나 `null`로 보내면 새 대화를 만듭니다.
기존 대화에 질문을 추가하려면 `{"message":"추가 질문","conversationId":1}`처럼 전달합니다.
새 상담에는 `{"message":"탈수할 때 소리가 커요","category":"WASHER"}`처럼 제품 카테고리를 지정할 수 있습니다.
지원 값은 `AIR_CONDITIONER`, `REFRIGERATOR`, `WASHER`, `DRYER`, `DISHWASHER`입니다.
카테고리는 대화에 저장되며 목록·상세 응답의 `category` 필드로 반환합니다.
후속 요청에서 카테고리를 생략하면 저장된 값을 사용하며, 다른 카테고리로 변경하려 하면 400을 반환합니다.
선택한 제품 카테고리를 LLM 질문에 함께 전달하되 DB의 질문 본문은 고객이 입력한 원문으로 저장합니다.
이전 클라이언트와 기존 기록을 위해 카테고리는 선택 항목이며, 미지정 상담은 일반 질문으로 처리합니다.
첫 질문의 앞 50자를 대화 제목으로 사용합니다. 성공한 질문·답변은 하나의 트랜잭션으로 저장하며,
AI 호출이 실패하거나 답변이 비어 있으면 대화 내역을 변경하지 않습니다.

| HTTP 상태 | 의미 |
| --- | --- |
| 200 | 답변 성공 |
| 400 | 빈 메시지, 길이 초과, 누락되거나 잘못된 JSON 본문 |
| 401 | 로그인 필요 또는 유효하지 않은 JWT |
| 404 | 존재하지 않거나 다른 사용자의 대화 |
| 502 | LLM 인증·통신 실패, 시간 초과 또는 빈 답변 |

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
[{"id":1,"title":"Spring Boot가 무엇인지 간단히 설명해 줘.","createdAt":"2026-10-11T09:00:00Z","updatedAt":"2026-10-11T09:00:00Z"}]
```

상세 응답은 메시지를 저장 순서대로 반환합니다.

```json
{"id":1,"title":"Spring Boot가 무엇인지 간단히 설명해 줘.","messages":[{"id":1,"role":"user","content":"Spring Boot가 무엇인지 간단히 설명해 줘.","createdAt":"2026-10-11T09:00:00Z"},{"id":2,"role":"assistant","content":"Spring Boot는 Spring 애플리케이션 개발을 돕는 프레임워크입니다.","createdAt":"2026-10-11T09:00:00Z"}]}
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
└── chat/
    ├── ChatController.java        # HTTP 요청과 입력 검증
    ├── ChatService.java           # Spring AI ChatClient 호출
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
./gradlew test bootJar
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

테스트는 로컬 모의 OpenAI 서버를 사용하여 실제 Spring AI HTTP 호출, 요청 모델과 인증 헤더,
성공 응답, 입력 검증, 외부 API 오류, 빈 답변, health API를 확인합니다.
회원가입, 비밀번호 해시 저장, 중복 이메일, 로그인 실패, 인증된 채팅,
비로그인·변조·만료 토큰 차단도 검증합니다.
대화·메시지 저장과 순서, 최신 대화 정렬, 재인증 후 조회, 사용자 간 접근 차단,
잘못된 대화 ID, AI 실패 시 기록 유지, 긴 답변과 유니코드 제목도 검증합니다.
테스트는 H2 메모리 DB와 가짜 API 키를 사용하므로 실제 MySQL 데이터나 LLM 사용량에 영향을 주지 않습니다.

공식 문서: [Spring AI 시작하기](https://docs.spring.io/spring-ai/reference/getting-started.html),
[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html),
[OpenAI 연동 설정](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html),
[dotenv-java](https://github.com/cdimascio/dotenv-java),
[Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Spring Data JPA 트랜잭션](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).
