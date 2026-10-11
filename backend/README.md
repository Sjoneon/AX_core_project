# AI 챗봇 백엔드

Java 21, Spring Boot 4.1.1, Spring AI 2.0.1을 사용하는 로컬 REST 서버입니다.
`temp_project/todolist`의 생성자 주입, Controller → Service → DTO 구조를 따릅니다.
사용자 계정은 MySQL에 저장하고, 로그인 시 발급한 JWT로 채팅 API를 인증합니다.
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
로컬 개발용 `ddl-auto=update` 설정으로 기동 시 `app_users` 테이블을 생성합니다.
테이블에는 `id`, 고유한 `email`, BCrypt 해시인 `password`, `role`을 저장합니다.
사용자 계정은 서버를 재시작해도 유지됩니다.

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
{"reply":"Spring Boot는 Spring 기반 애플리케이션을 쉽게 만들고 실행하도록 도와주는 프레임워크입니다."}
```

`message`는 공백만으로 구성되지 않은 1~4000자의 문자열이어야 합니다.
로그인 응답의 토큰을 `Authorization: Bearer <accessToken>` 헤더로 전달해야 합니다.
토큰이 없거나 변조·만료된 경우 401을 반환하고 LLM을 호출하지 않습니다.
답변은 기본적으로 한국어이며 사용자가 다른 언어를 요청하면 그 언어로 답합니다.
동기 방식으로 전체 답변을 한 번에 반환합니다.

| HTTP 상태 | 의미 |
| --- | --- |
| 200 | 답변 성공 |
| 400 | 빈 메시지, 길이 초과, 누락되거나 잘못된 JSON 본문 |
| 401 | 로그인 필요 또는 유효하지 않은 JWT |
| 502 | LLM 인증·통신 실패, 시간 초과 또는 빈 답변 |

오류 응답은 `{"message":"오류 설명"}` 형식입니다. 외부 API의 오류 내용이나 키를
응답에 포함하지 않습니다. OpenAI 요청 제한 시간은 30초이며 자동 재시도는 하지 않습니다.

## 구조

```text
src/main/java/com/project/backend/
├── BackendApplication.java        # .env 로딩과 서버 기동
├── auth/                         # 회원가입·로그인 Controller, Service, DTO
├── user/                         # 사용자 엔티티, Repository, UserDetailsService
├── config/                       # Spring Security, JWT 서명·검증 설정
├── security/                     # JWT 발급
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
이번 DB 연동은 사용자 계정 저장용이며 채팅 기록은 DB에 저장하지 않습니다.
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
테스트는 H2 메모리 DB와 가짜 API 키를 사용하므로 실제 MySQL 데이터나 LLM 사용량에 영향을 주지 않습니다.

공식 문서: [Spring AI 시작하기](https://docs.spring.io/spring-ai/reference/getting-started.html),
[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html),
[OpenAI 연동 설정](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html),
[dotenv-java](https://github.com/cdimascio/dotenv-java),
[Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
