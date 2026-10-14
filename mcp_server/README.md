# HomeCare AI MCP · 도구 9개

`homecare_mcp` 패키지가 실제 9개 읽기 전용 도구를 제공한다. 데이터와 판정(보증·가격·소유권·상태)의 단일 구현 위치는 Spring이다. 이 서버는 데이터를 갖지 않는 얇은 어댑터이며, 기존 로그인 JWT를 요청별로 전달하고 OpenAI 키는 받지 않는다.

[설계·구현·자료 범위](../docs/mcp/mcp_implementation.md), [보증의 제품/부품 구분](../docs/mcp/warranty_implementation.md), [실제 discovery 계약](../docs/mcp/mcp-tools.contract.json).

## 폴더 구조

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

도구를 추가할 때는 `tools/<tool_name>.py`에 `register(mcp, backend)`를 만들고 `tools/__init__.py`의 `TOOL_MODULES`에 넣는다. Spring 쪽은 `backend/.../mcp/tool/handler/<ToolName>Tool.java`에 `HomeCareTool` 구현을 추가하고 `ToolBackend.NAMES`에 이름을 넣는다. 그다음 계약을 다시 만들고 diff를 검토한다(CI가 불일치를 실패로 처리한다).

## 도구와 공통 결과

| 도구 | 하는 일 |
|---|---|
| `lookup_model` | 고객이 말한 모델 코드를 판매 코드와 정확히 일치할 때만 조회한다. 원문을 보존하고 접미사(예: `RD21ESE.AKOR`)를 자르지 않는다. 등록 전에도 쓴다. |
| `search_knowledge` | 제조사 구조화 자료의 모델/품목 필터·정확 코드/표시·증상 어휘·캐시 임베딩 검색. 관련 안전을 먼저 제공하고, 근거 부족은 NO_DATA/NEEDS_INPUT, 의미 검색 장애는 어휘 대체로 표시한다. |
| `get_product_context` | 본인의 등록 제품과 확정 상태. 같은 품목의 제품이 하나면 상담 시작 시 자동 연결하며, 여러 개면 입력한 모델명·표시 이름으로 구분한다. |
| `get_diagnostic_plan` | 승인된 자가검진 단계만 하나씩. 승인이 없으면 원문 후보만. |
| `check_warranty` | 품질보증(구입일 기준)과 부품보유(제조일 기준)를 나눠 반환. 부품보유기간은 B30 원본 확인 전까지 `UNAVAILABLE`. |
| `get_repair_candidates` | 품목의 부품 코드·이름과 `candidate_set_id`. 등록 제품이 없어도 된다. |
| `estimate_repair_cost` | 참고용 예상 범위(중앙·80% 범위)와 신뢰도. `time_slot`(평일/야간·휴일), `month`(1~12) 출장비 가산. 견적·무상 판정이 아니다. |
| `find_service_centers` | 배송지 기준 센터와 공식 접수 경로. |
| `prepare_service_handoff` | 공식 채널에 붙여 넣을 요약. 전송·예약하지 않는다. |

모든 결과에는 `source`(데이터 버전·파일·fact ID), `confidence`(HIGH/MEDIUM/LOW/UNDETERMINED), `caveats`(고정 해석 고지), `independent_action_allowed: false`가 들어간다. 어댑터는 `independent_action_allowed`가 false가 아니면 `INVALID_RESULT`로 거부한다.

## 로컬 실행

운영 이미지는 Python 3.12, 현재 PC의 기존 venv는 Python 3.13.2이며 Java 21을 사용한다. `.venv`와 요구 패키지가 이미 있으면 생성·설치 명령은 건너뛴다. PowerShell 첫 터미널:

```powershell
cd mcp_server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
$env:HOMECARE_MCP_TRANSPORT='http'
$env:HOMECARE_BACKEND_URL='http://127.0.0.1:8080'
.\.venv\Scripts\python -m homecare_mcp
```

둘째 터미널에서 `backend/.env`의 DB/JWT/OpenAI 설정을 사용한다. 이 PC의 로컬 DB는 `homecare_ai`이며 `.env.example`의 `project_db`와 다르다. 기존 DB 계정·비밀번호와 OpenAI 키는 `.env`에 직접 입력한다. 키가 없으면 `your-openai-api-key` 자리표시 값을 유지하고 실제 OpenAI 검증만 보류한다.

```powershell
cd backend
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_AGENT_ENABLED='true'
$env:HOMECARE_MCP_URL='http://127.0.0.1:8000'
.\gradlew.bat bootRun --offline --no-daemon
```

Java 설정은 해당 터미널에만 적용한다. 캐시가 없으면 `--offline`을 뺀다. 세 번째 터미널은 `frontend`에서 Node 24의 `node -v`를 확인하고 `npm ci` → `npm run dev`를 실행한다. `.env.local` 없이 Vite의 `/api` 프록시를 사용한다.

프론트엔드에서 로그인하고 DB에 저장된 제품을 선택해 모델명·증상·비용·A/S 문의를 진행한다. 추가 수동 제품 등록·수정은 기존 마이페이지에서 한다. 상담 화면의 별도 보증 입력·등록 양식은 사용하지 않는다. MCP 주소는 서버 설정이며 사용자 인자로 받지 않는다. `/health`는 서버 프로세스 준비 여부이고 DB/실 OpenAI 접속 검증이 아니다.

## LLM 없는 HTTP MCP 스모크 확인

테스트 계정 2개와 각 계정의 상담을 준비한다. 첫 계정에는 구매일·배송지역이 있는 세탁기 `FX24ENER`를 등록하고 상담에 연결한다. MCP를 중지한 상태로 `/api/chat`에 `FX24ENER 세탁기 OE 배수 오류 수리비를 확인하고 비교용 모델 코드 RD21ESE.AKOR도 확인해 주세요.`를 보내면 모델 호출 없이 `MCP_UNAVAILABLE` 응답과 상담이 저장된다. 두 번째 계정도 위험 신호가 없는 문장으로 상담을 만든 뒤 MCP를 다시 실행한다. 두 모델 코드는 첫 계정의 최신 문장에 있어야 모델 출처 검증을 통과한다. 자세한 준비 순서는 [검증 가이드](../docs/guides/verification_guide.md)의 2장을 따른다.

로그인 응답과 상담 응답을 셸 변수로만 보관하고 다음 환경 변수를 설정한다. JWT·상담 ID를 출력하거나 파일에 저장하지 않는다.

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

기본 출력은 도구·status·confidence·오류 코드 요약이다. `--verbose`는 인증값·상담 ID를 지운 결과 본문도 표시한다. 도구 9개와 `readOnlyHint`, 정확한 모델·접미사 보존, 실제 후보 묶음으로 계산한 비용, 보증·센터·전달 요약, 무인증·타 사용자 상담 거부를 확인한다. 자료 부족의 `NO_DATA`·`NOT_VERIFIED`는 계약상 정상일 수 있으며 비용 후보가 없어 계산을 건너뛰면 종료 코드 1로 미완료를 알린다. 종료 코드 2는 환경 변수 설정 오류다.

## 보안 설정

- HTTP는 **호출별 Authorization Bearer HomeCare JWT**만 사용한다. 전역 사용자 토큰을 재사용하지 않는다. 기본 stdio로 외부 MCP Client가 실행할 때만 해당 사용자의 `HOMECARE_USER_TOKEN`을 프로세스 환경에 줄 수 있다. 상담 ID는 본인 소유여야 한다.
- HTTP 모드는 FastMCP 3.4.7의 엄격한 Host/Origin 검증(`host_origin_protection=True`)을 켠다. 허용 Host는 `127.0.0.1`·`localhost`와 `HOMECARE_MCP_ALLOWED_HOSTS`(쉼표 구분, 컨테이너에서는 `mcp`)다. 다른 Host는 421, 허용되지 않은 브라우저 Origin은 403이다. Origin을 허용해야 할 때만 `HOMECARE_MCP_ALLOWED_ORIGINS`를 쓴다.
- 로컬 실행은 기본 `127.0.0.1`에 바인딩한다. 컨테이너는 내부 네트워크에서만 받고 호스트 포트와 Nginx 경로가 없다.
- 로그에는 trace_id·도구 이름·상태·소요 시간만 남긴다. 인자 검증 실패 로그는 오류 유형과 위치만 남기고 입력 값은 지운다.
- 사용자 응답에는 백엔드 원문·내부 주소·스택 트레이스를 넣지 않는다(`mask_error_details`, `backend_client.py`).

## MCP Inspector 수동 확인

테스트 계정의 JWT만 쓴다. 운영 사용자 토큰을 쓰지 않는다.

1. 위 로컬 실행으로 backend와 MCP를 띄우고 테스트 계정으로 로그인해 `accessToken`과 본인 상담 ID를 준비한다.
2. `npx @modelcontextprotocol/inspector`를 실행하고 Transport `Streamable HTTP`, URL `http://127.0.0.1:8000/mcp`, Header `Authorization: Bearer <테스트 JWT>`로 연결한다.
3. Tools 목록에 9개 도구와 `readOnlyHint: true`가 보이는지 확인한다.
4. `lookup_model`(`conversation_id`, 고객 문장에 있는 `model_code`)과 `estimate_repair_cost` 등을 호출해 결과의 `confidence`·`caveats`·`independent_action_allowed:false`를 확인한다.
5. 토큰을 빼거나 다른 사용자 상담 ID를 넣으면 `AUTH_REQUIRED`·`OWNERSHIP_NOT_FOUND`로 거부되는지 확인한다.

브라우저에서 Inspector가 MCP에 직접 붙어 Origin 검증에 막히면 로컬에서만 `HOMECARE_MCP_ALLOWED_ORIGINS=http://localhost:6274`를 설정한다.

## 테스트와 계약

```powershell
cd mcp_server
.\.venv\Scripts\python -m unittest discover -s tests -t . -q
.\.venv\Scripts\python -m scripts.export_contract
```

backend 전체 테스트에는 위 Python 경로가 필요하다:

```powershell
# 프로젝트 루트에서 실행
$env:HOMECARE_TEST_PYTHON=(Resolve-Path 'mcp_server/.venv/Scripts/python.exe').Path
cd backend
.\gradlew.bat test bootJar
```

JUnit은 이 Python으로 테스트용 실제 HTTP MCP 서버를 자동 실행·종료한다. 모의 OpenAI가 정해진 tool call을 돌려주는 방식으로 E2E 시나리오(모델 문장→조회→근거→부품→비용, 안전 우선, 구입일 질문, MCP 장애 대체 응답)를 검사하며 실 OpenAI 키를 쓰지 않는다. 실제 모델의 선택 정확도는 [검증 가이드](../docs/guides/verification_guide.md)의 수동 절차로 따로 확인한다.

## Docker와 운영

로컬 `backend/compose.yaml`은 backend와 MCP를 함께 실행한다. 운영 배포(`ops/deploy/ci-release.py`)는 backend·nginx·mcp 이미지 3개를 digest로 게시하고 `compose.prod.yaml`과 `compose.mcp.yaml`을 함께 적용한다. 실패하면 세 컨테이너를 이전 릴리스로 되돌린다.

- 운영 DB에는 배포 전에 V002 → V003 → V004 → V005가 적용돼 있어야 한다(운영은 `ddl-auto=validate`).
- `compose.mcp.yaml`을 배포해도 에이전트는 `HOMECARE_AGENT_ENABLED=true`를 따로 정하기 전까지 꺼져 있다(MCP 컨테이너만 내부에서 실행).
- 베이스 이미지는 `python:3.12.15-slim-bookworm@sha256:…`로 고정했다. 전이 의존성 해시 고정은 Linux에서 lock 파일을 만들어야 한다(Windows pip는 플랫폼 표지를 현재 OS로 평가한다).
- ECR 저장소 `homecare-prod/mcp`(Terraform)와 GitHub 변수 `ECR_MCP_REPOSITORY`가 필요하다.

자료 제한: 자가검진 실행 승인, 후기 원문은 현재 빈 목록이다. 센터는 3개 시드다. 도구는 미확인 결과를 안전하게 반환하며 없는 자료를 만들어 채우지 않는다.
