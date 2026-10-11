# AI 챗봇 백엔드

Java 21, Spring Boot 4.1.1, Spring AI 2.0.1을 사용하는 로컬 REST 서버입니다.
`temp_project/todolist`의 생성자 주입, Controller → Service → DTO 구조를 따릅니다.
Spring AI의 `ChatClient`로 OpenAI에 메시지를 전달하고 JSON으로 답변합니다.

## 실행

프로젝트 루트에서 실행합니다.

```bash
cd backend
./gradlew bootRun
```

`backend`를 작업 디렉터리로 사용해야 `.env`를 읽습니다. Java 21과 의존성을 다운로드할
인터넷 연결이 필요하며, Gradle은 포함된 wrapper를 사용합니다.

기존 `backend/.env`의 `OPENAI_API_KEY`를 자동으로 읽습니다. `.env`를 새로 만들 때는
`.env.example`을 참고하세요. 따옴표가 있는 키 값도 지원합니다.

```dotenv
OPENAI_API_KEY="your-openai-api-key"
OPENAI_MODEL=gpt-4o-mini
```

`OPENAI_MODEL`은 선택 사항이며 기본값은 `gpt-4o-mini`입니다. 운영체제 환경 변수와
Spring 실행 옵션이 `.env`보다 우선합니다. API 키가 없으면 기동에 실패합니다.
실제 채팅 호출에는 해당 키의 모델 접근 권한과 API 사용 가능한 잔액이 필요합니다.
키는 소스에 저장하지 않으며 `.env`는 기존 `.gitignore`로 제외됩니다.

서버는 `127.0.0.1:8080`에서 실행됩니다. 포트를 바꾸려면 다음처럼 실행합니다.

```bash
./gradlew bootRun --args='--server.port=8081'
```

## API

### 서버 상태

```bash
curl http://127.0.0.1:8080/actuator/health
```

```json
{"status":"UP"}
```

이 API는 서버 기동 상태만 확인하며 LLM을 호출하지 않습니다.

### 채팅

```bash
curl -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Spring Boot가 무엇인지 간단히 설명해 줘."}'
```

응답 예시:

```json
{"reply":"Spring Boot는 Spring 기반 애플리케이션을 쉽게 만들고 실행하도록 도와주는 프레임워크입니다."}
```

`message`는 공백만으로 구성되지 않은 1~4000자의 문자열이어야 합니다.
답변은 기본적으로 한국어이며 사용자가 다른 언어를 요청하면 그 언어로 답합니다.
동기 방식으로 전체 답변을 한 번에 반환합니다.

| HTTP 상태 | 의미 |
| --- | --- |
| 200 | 답변 성공 |
| 400 | 빈 메시지, 길이 초과, 누락되거나 잘못된 JSON 본문 |
| 502 | LLM 인증·통신 실패, 시간 초과 또는 빈 답변 |

오류 응답은 `{"message":"오류 설명"}` 형식입니다. 외부 API의 오류 내용이나 키를
응답에 포함하지 않습니다. OpenAI 요청 제한 시간은 30초이며 자동 재시도는 하지 않습니다.

## 구조

```text
src/main/java/com/project/backend/
├── BackendApplication.java        # .env 로딩과 서버 기동
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

첫 버전은 요청마다 독립적으로 답변하며 이전 대화 내용을 기억하지 않습니다.
DB, 회원가입, 로그인, JWT, 스트리밍, RAG는 포함하지 않습니다.
기존 MySQL/JPA 의존성과 DB·JWT 설정은 이 서버에서 사용하지 않아 제거했습니다.

## 검증 및 JAR 실행

```bash
./gradlew test bootJar
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

테스트는 로컬 모의 OpenAI 서버를 사용하여 실제 Spring AI HTTP 호출, 요청 모델과 인증 헤더,
성공 응답, 입력 검증, 외부 API 오류, 빈 답변, health API를 확인합니다.
테스트에서는 가짜 API 키와 로컬 주소를 사용하므로 실제 LLM 비용이 발생하지 않습니다.

공식 문서: [Spring AI 시작하기](https://docs.spring.io/spring-ai/reference/getting-started.html),
[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html),
[OpenAI 연동 설정](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html),
[dotenv-java](https://github.com/cdimascio/dotenv-java).
