# HomeCare AI MCP · 도구 9개

> `homecare_mcp` 패키지가 제공하는 읽기 전용 MCP 도구 9개의 구조, 실행, 보안 설정
> 데이터와 판정(보증·가격·소유권·상태)의 단일 구현 위치: Spring

**서버의 역할**

- 데이터를 갖지 않는 얇은 어댑터
- 기존 로그인 JWT를 요청별로 전달
- OpenAI 키는 받지 않음
- 관련 문서: [설계·구현·자료 범위](../docs/mcp/mcp_implementation.md), [보증의 제품/부품 구분](../docs/mcp/warranty_implementation.md), [실제 discovery 계약](../docs/mcp/mcp-tools.contract.json)

**단계별 연결**

| 단계 | 내용 | 기록 |
| --- | --- | --- |
| 5단계 | `get_diagnostic_plan`은 조건 확인 전 `NEEDS_INPUT` 질문 1개와 빈 단계를 반환하고, 확인 후 현재 승인 단계만 조회. 등록 계획은 TR15WV5/UE/세탁물 뭉침 1건이며 진동은 미승인. 사용자 응답과 이력 저장은 Spring 상담 API가 처리 | [조건 확인과 승인된 자가조치](../docs/mcp/approved_self_care.md) |
| 6단계 | 비용 입력은 후보 묶음·연결 근거·확신도·국내 사례 수를 확인하고 자료 부족은 NO_DATA로 반환. 새 연결 사유 NOT_VERIFIED·NO_SELF_CARE·PRECONDITION_UNCONFIRMED를 보존하며 실제 예약·전송은 수행하지 않음 | [원인 후보·비용·보증·공식 서비스 연결](../docs/mcp/repair_followup.md) |
| 8단계 | 별도 포트의 실제 FastMCP·Spring HTTP/JWT와 격리 H2 사용. 모델은 Spring에서만 호출하며 이 서버에 키를 전달하지 않음. 분류·호출 성공과 실제 질문에 대한 설명·다음 행동을 구분해 평가, 도구 9개의 입력/출력 계약 유지 | [실제 상담·답변 검증](../docs/mcp/consultation_validation.md) |

- MCP 도구는 계속 읽기 전용

**목차**

1. [폴더 구조](#1-폴더-구조)
2. [도구와 공통 결과](#2-도구와-공통-결과)
3. [로컬 실행](#3-로컬-실행)
4. [LLM 없는 HTTP MCP 스모크 확인](#4-llm-없는-http-mcp-스모크-확인)
5. [보안 설정](#5-보안-설정)
6. [MCP Inspector 수동 확인](#6-mcp-inspector-수동-확인)
7. [테스트와 계약](#7-테스트와-계약)
8. [Docker와 운영](#8-docker와-운영)

---

## 1. 폴더 구조

```text
mcp_server/
├─ homecare_mcp/                 # MCP 서버 패키지 (snake_case)
│  ├─ __main__.py                # 실행 진입점: python -m homecare_mcp (HTTP Host/Origin 검증 설정)
│  ├─ server.py                  # create_server: FastMCP 생성·/health·도구 등록
│  ├─ auth.py                    # 요청별 Bearer 토큰
│  ├─ backend_client.py          # Spring /api/tools 호출·오류 마스킹·호출 로그
│  ├─ log_privacy.py             # 거부된 인자 값·트레이스백을 로그에서 제거
│  ├─ models/                    # envelope.py · inputs.py · region.py · warranty.py
│  ├─ tools/                     # 도구 1개당 파일 1개 (파일명 = 도구 이름)
│  │  ├─ lookup_model.py
│  │  ├─ search_knowledge.py
│  │  ├─ get_product_context.py
│  │  ├─ get_diagnostic_plan.py
│  │  ├─ check_warranty.py
│  │  ├─ get_repair_candidates.py
│  │  ├─ estimate_repair_cost.py
│  │  ├─ find_service_centers.py
│  │  ├─ prepare_service_handoff.py
│  │  └─ read_only.py            # 공통 읽기 전용 annotations
│  └─ legacy/warranty_server.py  # 이전 보증 단독 MCP 호환 엔트리
├─ scripts/export_contract.py    # discovery 계약 → docs/mcp/mcp-tools.contract.json
├─ scripts/smoke_client.py       # 실제 HTTP MCP 도구·인증·소유권 확인 (LLM 제외)
└─ tests/                        # test_server.py · test_warranty_server.py
```

도구 추가 순서

1. `tools/<tool_name>.py`에 `register(mcp, backend)` 작성
2. `tools/__init__.py`의 `TOOL_MODULES`에 추가
3. Spring 쪽 `backend/.../mcp/tool/handler/<ToolName>Tool.java`에 `HomeCareTool` 구현 추가
4. `ToolBackend.NAMES`에 이름 추가
5. 계약 재생성 후 diff 검토(CI가 불일치를 실패로 처리)

---

## 2. 도구와 공통 결과

| 도구 | 하는 일 |
| --- | --- |
| `lookup_model` | 고객이 말한 모델 코드를 판매 코드와 정확히 일치할 때만 조회. 원문을 보존하고 접미사(예: `RD21ESE.AKOR`)를 자르지 않음. 등록 전에도 사용 |
| `search_knowledge` | 제조사 구조화 자료의 모델/품목 필터·정확 코드/표시·증상 어휘·캐시 임베딩 검색. 관련 안전을 먼저 제공하고, 근거 부족은 NO_DATA/NEEDS_INPUT, 의미 검색 장애는 어휘 대체로 표시 |
| `get_product_context` | 본인의 등록 제품과 확정 상태. 같은 품목의 제품이 하나면 상담 시작 시 자동 연결, 여러 개면 입력한 모델명·표시 이름으로 구분 |
| `get_diagnostic_plan` | 승인된 자가검진 단계만 하나씩. 승인이 없으면 원문 후보만 |
| `check_warranty` | 품질보증(구입일 기준)과 부품보유(제조일 기준)를 나눠 반환. 부품보유기간은 B30 원본 확인 전까지 `UNAVAILABLE` |
| `get_repair_candidates` | 품목의 부품 코드·이름과 `candidate_set_id`. 등록 제품이 없어도 사용 가능 |
| `estimate_repair_cost` | 참고용 예상 범위(중앙·80% 범위)와 신뢰도. `time_slot`(평일/야간·휴일), `month`(1~12) 출장비 가산. 견적·무상 판정이 아님 |
| `find_service_centers` | 배송지 기준 센터와 공식 접수 경로 |
| `prepare_service_handoff` | 공식 채널에 붙여 넣을 요약. 전송·예약하지 않음 |

모든 결과에 포함되는 필드

- `source`: 데이터 버전, 파일, fact ID
- `confidence`: HIGH/MEDIUM/LOW/UNDETERMINED
- `caveats`: 고정 해석 고지
- `independent_action_allowed: false`
    - 어댑터는 `independent_action_allowed`가 false가 아니면 `INVALID_RESULT`로 거부

---

## 3. 로컬 실행

### 3.1 실행 환경

- 운영 이미지: Python 3.12
- 개발 PC의 기존 venv: Python 3.13.2
- Java 21
- `.venv`와 요구 패키지가 이미 있으면 생성·설치 명령은 건너뜀

### 3.2 첫째 터미널: MCP 서버

```powershell
cd mcp_server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
$env:HOMECARE_MCP_TRANSPORT='http'
$env:HOMECARE_BACKEND_URL='http://127.0.0.1:8080'
.\.venv\Scripts\python -m homecare_mcp
```

### 3.3 둘째 터미널: 백엔드

- 설정: `backend/.env`의 DB/JWT/OpenAI 설정 사용
- 개발 PC의 로컬 DB: `homecare_ai`(`.env.example`의 `project_db`와 다름)
- 기존 DB 계정·비밀번호와 OpenAI 키는 `.env`에 직접 입력
- 키가 없으면 `your-openai-api-key` 자리표시 값을 유지하고 실제 OpenAI 검증만 보류

```powershell
cd backend
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_AGENT_ENABLED='true'
$env:HOMECARE_MCP_URL='http://127.0.0.1:8000'
.\gradlew.bat bootRun --offline --no-daemon
```

- Java 설정은 해당 터미널에만 적용
- 캐시가 없으면 `--offline` 제거

### 3.4 셋째 터미널: 프론트엔드

1. `frontend`에서 Node 24의 `node -v` 확인
2. `npm ci`
3. `npm run dev`

- `.env.local` 없이 Vite의 `/api` 프록시 사용

### 3.5 사용 방법

- 프론트엔드에서 로그인하고 DB에 저장된 제품을 선택해 모델명·증상·비용·A/S 문의 진행
- 추가 수동 제품 등록·수정: 기존 마이페이지
- 상담 화면의 별도 보증 입력·등록 양식은 사용하지 않음
- MCP 주소: 서버 설정이며 사용자 인자로 받지 않음
- `/health`: 서버 프로세스 준비 여부이며 DB/실 OpenAI 접속 검증이 아님

---

## 4. LLM 없는 HTTP MCP 스모크 확인

### 4.1 준비

1. 테스트 계정 2개와 각 계정의 상담 준비
2. 첫 계정: 구매일·배송지역이 있는 세탁기 `FX24ENER`를 등록하고 상담에 연결
3. MCP를 중지한 상태로 `/api/chat`에 아래 문장 전송
    - `FX24ENER 세탁기 OE 배수 오류 수리비를 확인하고 비교용 모델 코드 RD21ESE.AKOR도 확인해 주세요.`
    - 결과: 모델 호출 없이 `MCP_UNAVAILABLE` 응답과 상담 저장
4. 두 번째 계정: 위험 신호가 없는 문장으로 상담 생성
5. MCP 재실행

- 두 모델 코드는 첫 계정의 최신 문장에 있어야 모델 출처 검증 통과

### 4.2 실행

- 로그인 응답과 상담 응답은 셸 변수로만 보관
- JWT·상담 ID를 출력하거나 파일에 저장하지 않음

```powershell
Set-Location .\mcp_server
$env:HOMECARE_SMOKE_TOKEN=$login.accessToken
$env:HOMECARE_SMOKE_CONVERSATION_ID=[string]$ownChat.conversationId
$env:HOMECARE_SMOKE_FOREIGN_CONVERSATION_ID=[string]$foreignChat.conversationId
try {
    & .\.venv\Scripts\python.exe -X utf8 -m scripts.smoke_client
} finally {
    Remove-Item Env:HOMECARE_SMOKE_TOKEN, Env:HOMECARE_SMOKE_CONVERSATION_ID, Env:HOMECARE_SMOKE_FOREIGN_CONVERSATION_ID -ErrorAction SilentlyContinue
}
```

### 4.3 출력과 종료 코드

- 기본 출력: 도구, status, confidence, 오류 코드 요약
- `--verbose`: 인증값·상담 ID를 지운 결과 본문도 표시
- 확인 항목
    - 도구 9개와 `readOnlyHint`
    - 정확한 모델·접미사 보존
    - 실제 후보 묶음으로 계산한 비용
    - 보증·센터·전달 요약
    - 무인증·타 사용자 상담 거부
- 자료 부족의 `NO_DATA`·`NOT_VERIFIED`는 계약상 정상일 수 있음

| 종료 코드 | 의미 |
| --- | --- |
| 1 | 비용 후보가 없어 계산을 건너뜀(미완료) |
| 2 | 환경 변수 설정 오류 |

---

## 5. 보안 설정

### 5.1 인증

- HTTP: **호출별 Authorization Bearer HomeCare JWT**만 사용, 전역 사용자 토큰을 재사용하지 않음
- 기본 stdio: 외부 MCP Client가 실행할 때만 해당 사용자의 `HOMECARE_USER_TOKEN`을 프로세스 환경으로 전달 가능
- 상담 ID: 본인 소유여야 함

### 5.2 Host/Origin 검증

- HTTP 모드: FastMCP 3.4.7의 엄격한 Host/Origin 검증(`host_origin_protection=True`) 사용
- 허용 Host: `127.0.0.1`, `localhost`, `HOMECARE_MCP_ALLOWED_HOSTS`(쉼표 구분, 컨테이너에서는 `mcp`)

| 요청 | 응답 |
| --- | --- |
| 다른 Host | 421 |
| 허용되지 않은 브라우저 Origin | 403 |

- Origin을 허용해야 할 때만 `HOMECARE_MCP_ALLOWED_ORIGINS` 사용

### 5.3 바인딩과 로그

- 로컬 실행: 기본 `127.0.0.1`에 바인딩
- 컨테이너: 내부 네트워크에서만 수신, 호스트 포트와 Nginx 경로 없음
- 로그: trace_id, 도구 이름, 상태, 소요 시간만
- 인자 검증 실패 로그: 오류 유형과 위치만 남기고 입력 값은 제거
- 사용자 응답에 넣지 않는 것: 백엔드 원문, 내부 주소, 스택 트레이스(`mask_error_details`, `backend_client.py`)

---

## 6. MCP Inspector 수동 확인

- 테스트 계정의 JWT만 사용, 운영 사용자 토큰은 쓰지 않음

1. 로컬 실행으로 backend와 MCP를 띄우고, 테스트 계정으로 로그인해 `accessToken`과 본인 상담 ID 준비
2. `npx @modelcontextprotocol/inspector` 실행 후 연결
    - Transport: `Streamable HTTP`
    - URL: `http://127.0.0.1:8000/mcp`
    - Header: `Authorization: Bearer <테스트 JWT>`
3. Tools 목록에 9개 도구와 `readOnlyHint: true`가 보이는지 확인
4. `lookup_model`(`conversation_id`, 고객 문장에 있는 `model_code`)과 `estimate_repair_cost` 등을 호출해 `confidence`·`caveats`·`independent_action_allowed:false` 확인
5. 토큰을 빼거나 다른 사용자 상담 ID를 넣으면 `AUTH_REQUIRED`·`OWNERSHIP_NOT_FOUND`로 거부되는지 확인

- 브라우저에서 Inspector가 MCP에 직접 붙어 Origin 검증에 막히면 로컬에서만 `HOMECARE_MCP_ALLOWED_ORIGINS=http://localhost:6274` 설정

---

## 7. 테스트와 계약

```powershell
cd mcp_server
.\.venv\Scripts\python -m unittest discover -s tests -t . -q
.\.venv\Scripts\python -m scripts.export_contract
```

backend 전체 테스트에는 위 Python 경로가 필요합니다.

```powershell
# 프로젝트 루트에서 실행
$env:HOMECARE_TEST_PYTHON=(Resolve-Path 'mcp_server/.venv/Scripts/python.exe').Path
cd backend
.\gradlew.bat test bootJar
```

- JUnit: 이 Python으로 테스트용 실제 HTTP MCP 서버를 자동 실행·종료
- 모의 OpenAI가 정해진 tool call을 돌려주는 방식의 E2E 시나리오
    - 모델 문장→조회→근거→부품→비용
    - 안전 우선
    - 구입일 질문
    - MCP 장애 대체 응답
- 실 OpenAI 키는 쓰지 않음
- 실제 모델의 선택 정확도는 수동 절차로 따로 확인

---

## 8. Docker와 운영

- 로컬 `backend/compose.yaml`: backend와 MCP를 함께 실행
- 운영 배포(`ops/deploy/ci-release.py`)
    - backend·nginx·mcp 이미지 3개를 digest로 게시
    - `compose.prod.yaml`과 `compose.mcp.yaml`을 함께 적용
    - 실패하면 세 컨테이너를 이전 릴리스로 복원
- 운영 DB: 배포 전에 V002 → V003 → V004 → V005 적용 필요(운영은 `ddl-auto=validate`)
- `compose.mcp.yaml`을 배포해도 에이전트는 `HOMECARE_AGENT_ENABLED=true`를 따로 정하기 전까지 꺼져 있음(MCP 컨테이너만 내부에서 실행)
- 베이스 이미지: `python:3.12.15-slim-bookworm@sha256:…`로 고정
    - 전이 의존성 해시 고정은 Linux에서 lock 파일 생성 필요(Windows pip는 플랫폼 표지를 현재 OS로 평가)
- 필요한 설정: ECR 저장소 `homecare-prod/mcp`(Terraform), GitHub 변수 `ECR_MCP_REPOSITORY`

**자료 제한**

- 자가검진 실행 승인, 후기 원문: 현재 빈 목록
- 센터: 3개 시드
- 도구는 미확인 결과를 안전하게 반환하며 없는 자료를 만들어 채우지 않음
