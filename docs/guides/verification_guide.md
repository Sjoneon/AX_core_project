# HomeCare AI 검증 방법과 다음 작업

작성일: 2026-10-13 · 참고: 핵심프로젝트 설명, 역할 및 만들 문서, MVP 기능명세 v1.7, 2차 멘토링 자료 v3, DB v0.4, B30, 비용 모델 v3

## 현재 판단

보증 규칙과 MCP 9개 도구 실행 경로, 모의 OpenAI E2E 시나리오의 자동 검증은 통과했다(2026-10-13, Java 130개·Python MCP 20개). 이전 실행 기록은 [mcp_warranty_validation.md](../mcp/mcp_warranty_validation.md)에 있다. 실 OpenAI는 호출하지 않았다. 실제 모델 확인은 아래 '실제 OpenAI 수동 E2E'를 따른다.

**첨부 기능명세의 전체 MVP 완료 상태는 아니다.** 명세는 T1~T19가 끝까지 동작하고 금액·보증에 근거가 붙으며 이용 이벤트가 쌓이는 것을 완료 기준으로 삼는다. 핵심프로젝트 설명은 사전 판정 기준, 실제 Agent 평가, 실패 원인 분석, 개선 전·후 비교를 요구한다. 모의 OpenAI를 쓰는 개발 테스트와 실제 모델의 선택 품질 평가를 따로 수행해야 한다.

원문과 현재 개발 범위는 세탁기·건조기·냉장고(김치 포함)·식기세척기 4품목이다. 명세·API·MCP 계약·화면·자료를 같은 범위로 검증한다. 제품 2년/주요 부품 4년은 TEST_ONLY 예시이며 실제 세탁기 정책으로 사용하지 않는다.

## 1. 자동 테스트 재현

아래 명령은 저장소 루트에서 실행한다. 다른 터미널에서 시작할 때는 먼저 실제 체크아웃 폴더로 이동한다(현재 작업 폴더: `C:\Users\Roneon\Desktop\core_project_2`). Java 21을 설치하고 `JAVA_HOME`을 해당 JDK로 설정해야 한다. `java -version`으로 버전을 확인한다. Python은 [MCP README](../../mcp_server/README.md)에 따라 만든 저장소 내 `mcp_server/.venv`를 사용한다. 운영 이미지는 Python 3.12이며, 기존 로컬 검증 기록은 Python 3.13.2 기준이다. OpenAI 키·운영 DB는 필요 없다. JUnit은 H2와 모의 OpenAI, 테스트용 실제 FastMCP HTTP 프로세스를 사용한다.

PowerShell:

```powershell
$env:HOMECARE_TEST_PYTHON=(Resolve-Path '.\mcp_server\.venv\Scripts\python.exe').Path
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

& $env:HOMECARE_TEST_PYTHON -m unittest discover -s .\mcp_server\tests -t .\mcp_server -q

Set-Location .\backend
.\gradlew.bat test bootJar --offline --no-daemon
```

Python은 실패 없는 `OK`, Java는 실패 0과 `BUILD SUCCESSFUL`을 확인한다. 변경이 없으면 Gradle이 이전 성공 결과를 재사용할 수 있다. 새 실행이 필요할 때만 `--rerun-tasks`를 추가한다. Gradle 의존성을 아직 내려받지 않았거나 새로 추가했다면 `--offline`을 빼고 다운로드한다. `GRADLE_USER_HOME`은 기본 사용자 캐시를 사용하며 별도 캐시가 필요한 환경에서만 지정한다.

2026-10-13 기록은 Java 130개(MySQL 전용 `mysqlSchemaTest` 1개 제외)·Python MCP 20개다. 현재 실행의 테스트 수와 결과는 새 보고서에서 확인한다. Java 보고서는 `backend/build/reports/tests/test/index.html`에 생성된다. `--tests '*McpIntegrationTests'`로 실행하면 해당 클래스만 실행하므로 전체 검증으로 기록하지 않는다.

화면 코드 검사:

```powershell
Set-Location .\frontend
npm run lint
npm run build
```

화면 검사 블록도 저장소 루트에서 시작한다. 앞의 테스트 블록을 같은 터미널에서 실행했다면 `Set-Location ..`로 루트에 돌아온 뒤 실행한다. `node_modules`가 없으면 먼저 `npm ci`를 실행한다. lint와 build 통과는 브라우저의 실제 상호작용·고객 이해도까지 검증한 것은 아니다. 다른 PC에서 MCP 가상환경을 만드는 방법은 [MCP README](../../mcp_server/README.md)에 있다.

## 2. 화면과 실제 Agent 확인

개발용 MySQL과 `backend/.env`의 DB/JWT/OpenAI 설정을 준비한다. 이 PC는 로컬 DB `homecare_ai`를 사용한다(`.env.example`·backend README의 `project_db`와 다름). `.env`의 `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `OPENAI_API_KEY`를 직접 입력한다. JWT 서명 키는 최초 생성값을 유지한다. 키가 없으면 `your-openai-api-key` 자리표시 값으로 실행하고 실제 OpenAI E2E만 보류한다. 운영 DB에 시험 데이터를 넣지 않는다. 개발 테스트용 제품·계정을 따로 사용한다.

각 터미널은 저장소 루트에서 시작한다. backend 터미널에는 Java 21의 `JAVA_HOME`과 기존 실행 환경 설정을 준비한다.

터미널 A — MCP:

```powershell
Set-Location .\mcp_server
$env:HOMECARE_MCP_TRANSPORT='http'
$env:HOMECARE_BACKEND_URL='http://127.0.0.1:8080'
& .\.venv\Scripts\python.exe -m homecare_mcp
```

터미널 B — backend:

```powershell
Set-Location .\backend
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_AGENT_ENABLED='true'
$env:HOMECARE_MCP_URL='http://127.0.0.1:8000'
.\gradlew.bat bootRun --offline --no-daemon
```

터미널 C — React:

```powershell
Set-Location .\frontend
node -v # Node 24 확인 (설치 후 새 터미널 사용)
npm ci
npm run dev
```

`http://127.0.0.1:5173`에 접속한다. 로그인 → DB에 저장된 등록 제품 확인 → 품목·상담 제품 선택 → 질문 순으로 진행한다. 추가 수동 등록·수정은 기존 마이페이지에서 한다. 상담 화면에는 별도 보증 입력·등록 양식을 두지 않는다. 서버 실행 확인은 `http://127.0.0.1:8000/health`와 `http://127.0.0.1:8080/actuator/health`로 한다. UP만으로 도구 선택·실 OpenAI 호출 성공을 판정하지 않는다.

위험 의심 문장은 AI가 현재 제품의 위험 징후인지 문맥을 판정한 뒤 확인 질문을 제공한다. AI 응답 오류·8초 초과는 키워드 대체 판정을 사용하고 긴급 신호는 AI를 기다리지 않는다. 답변 안의 ‘예·아니요·모르겠어요’ 버튼을 선택하면 `POST /api/conversations/{id}/risk-answer`로 `answer`와 `context_version`을 전달한다. 선택한 답과 다음 질문·안내가 상담 이력에 저장된다. 이전 질문에 대한 중복 선택은 409로 거부한다. 후속 선택은 AI를 다시 호출하지 않는다.

Java 설정은 해당 터미널에만 적용하며 `.env.local` 없이 Vite의 `/api` 프록시를 쓴다. 기동 후 `ddl-auto=update`가 `chat_tool_calls`와 제품 프로필 컬럼을 만들었는지 확인한다. 기존 17개 테이블 설계와 현재 JPA 테이블은 이름·필드가 다르므로 기존 테이블의 존재만으로 통과를 판정하지 않는다. SQL 마이그레이션을 임의로 실행하지 않는다.

현재 Flyway는 `flyway-core`·`flyway-mysql` 의존성과 `DatabaseMigration.main()`의 별도 `migrateDatabase` 작업으로 사용한다. Spring Boot 4의 Flyway 자동 설정 모듈은 포함하지 않으므로 `bootRun`은 V001~V006 SQL을 자동 실행하지 않는다. 로컬에서 테이블·컬럼이 생겨도 `flyway_schema_history`가 없으면 Hibernate 변경과 Flyway 적용을 구분해 기록한다. 기존 DB에 SQL을 적용하려면 현재 스키마와 마이그레이션 이력을 먼저 대조한다.

LLM 없는 확인은 테스트 계정 2개로 진행한다. 첫 계정에 세탁기 `FX24ENER`와 구매일·배송지역을 등록한다. MCP를 끄고 기존 `/api/chat`에 `category: WASHER`, 직접 등록한 `productId` 및 `FX24ENER 세탁기 OE 배수 오류 수리비를 확인하고 비교용 모델 코드 RD21ESE.AKOR도 확인해 주세요.` 문장을 보내 `MCP_UNAVAILABLE` 응답과 본인 상담을 만든다. 다른 계정도 위험 신호가 없는 문장으로 상담을 만든다. JWT·상담 ID는 셸 변수로만 보관한다. MCP 재기동 후 Bearer로 `POST /api/tools/lookup_model`의 공통 envelope를 먼저 확인하고, [MCP 스모크 스크립트](../../mcp_server/README.md#llm-없는-http-mcp-스모크-확인)로 실제 HTTP MCP 경로·도구 9개·거부 조건을 확인한다. `/health` 통과와 도구 호출 통과를 따로 기록한다.

개발 화면은 답변을 받은 뒤와 이전 상담을 열 때 `GET /api/conversations/{id}/tool-calls`를 자동 조회한다. ‘개발용 MCP 도구 호출 확인’ 패널은 상담 전체의 `messageId`, `callOrder`, `toolName`, `selectionSource`, `status`, `errorCode`, `elapsedMs`를 표시한다. 접힌 ‘원본 tools JSON’은 마지막 답변의 결과이며 상담 전체 호출 목록과 범위가 다르다. 패널은 `import.meta.env.DEV`에서만 렌더링한다. `npm run lint`·`npm run build` 후 운영 빌드 자산에 패널 문구와 호출 이력 조회 경로가 없는지도 확인한다.

브라우저 F12 → Network → `/api/chat` → Response에서 다음을 기록한다:

- `reply`: 고객 설명이 근거와 일치하는가.
- `warranty.productWarranty`, `warranty.partWarranties`: 일반/주요 부품 결과가 분리되는가.
- `tools`: 질문에 필요한 도구 결과가 있는가.
- `tools.<도구>.evidence`: 출처·기준일이 있는가.
- `tools.<도구>.meta.selection_source`: `POLICY_FALLBACK`이면 서버가 보완한 호출이다. LLM 선택 성공으로 집계하지 않는다.
- `tools.<도구>.meta.context_version`: 현재 제품·상담 상태와 맞는가.

`tools`는 이름별 마지막 결과(화면용)다. 호출 순서·횟수·인자·선택 주체(MODEL/POLICY_FALLBACK/SAFETY_POLICY)·상태·소요 시간은 `GET /api/conversations/{id}/tool-calls`(V005 `chat_tool_calls`)로 확인한다. 이 기록에는 고객 원문·이메일·토큰이 없고 검색어는 글자 수만 있다. 키·JWT·이메일·상세 주소는 평가 기록에 넣지 않는다.

## 3. 먼저 해 볼 화면 시험

날짜에 따라 판정이 바뀌므로 평가 기준일과 정책 버전을 함께 기록한다. 아래 첫 보증 예시는 기준일 2026-10-13에서 확인하는 테스트 데이터다. 후일에는 고정 Clock 단위 테스트로 재현하거나 날짜를 다시 정한다.

| 시험 | 입력/조작 | 기대 결과 |
|---|---|---|
| 전체 만료·주요 부품 유지 | 세탁기, 구매 2023-10-13, 생산 2023-09-01, DD모터, 구입 증빙·국내 신품 가정용 조건 확인 | 전체 보증 만료 / DD모터 별도 보증기간 안. 다른 부품 전체 무상으로 설명하지 않음. 출장비 미확인은 추가 확인 |
| 조건 미확인 | 위 예시에서 증빙 체크 해제, 부품 유형 또는 생산일 미입력 | 미확인 결과. 만료일·0원·최종 무상 승인 생성 금지 |
| 직접 조회 | 등록 제품으로 `check_warranty` 도구 직접 호출 | LLM 없이 DB 제품 정보 기준 계산 |
| 구매일 미입력 | 마이페이지에서 구매일 없이 제품 등록 후 보증 문의 | 제품 등록은 허용하고 보증 조회는 NEEDS_INPUT. 날짜 추측 금지 |
| 의심→해제 | 건조기 ‘뒤에서 연기 같은 게 나와’ → 질문에 모두 아니요 | 질문 하나씩, 모두 아니요 이후 DISMISSED. 확인 중 사용 중지·공식 상담 경로 제공 |
| 의심→확정 | 같은 입력 → 예 또는 모르겠어요 | CONFIRMED. 자가검진 차단, 경고 유지 |
| 긴급 | ‘콘센트에서 스파크가 튀어’ | 확인 질문 없이 사용 중지 안내. 위험 사례별 최종 문구 일치 여부는 별도 검토 |
| 미승인 자가검진 | 등록 세탁기 ‘OE가 떠요’ | 현재 실제 승인 목록이 비어 있어 NOT_VERIFIED가 정상. 작업 절차를 만들어 주면 실패 |
| 수리비 | 등록 세탁기 ‘OE 배수 오류 수리비와 근거를 알려줘’ | 후보 조회 후 비용 계산. 낮은 확신도·자료 부족은 NO_DATA도 가능. 후보별 비용을 합산하지 않고 ‘참고용 예상 범위(견적 아님)’·신뢰도·평일 출장비 28,000원 기준·고지문을 표시 |
| 모델 문장 | 제품 등록 없이 ‘FX24ENER 세탁기 배수가 안 돼요. 수리비 얼마나 나와요?’ | lookup_model → search_knowledge → get_repair_candidates → estimate_repair_cost. 대표 모델 FX24KNTR은 공통 자료용이라고만 안내 |
| 접미사 | 건조기 ‘RD21ESE.AKOR 모델이에요’ | NOT_FOUND와 라벨 재확인 요청. RD21ESE로 바꿔 조회하면 실패 |
| 시간대·월 | ‘야간에 8월 방문이면 W04 수리비는?’ 계열 | 6~8월 야간·휴일 가산 10,000원(5,000원과 중복 합산 아님) |
| 타는 냄새 | 건조기 ‘건조기에서 타는 냄새가 나요’ | AI 문맥 판정 후 답변 안에 확인 질문·선택 버튼. 사용 중지·1544-7777 안내. MCP 호출 없이 tool-calls는 SAFETY_POLICY |
| 구입일 없음 | 제품 없이 ‘보증기간 남았나요?’ | 구입일 질문, 품질보증(구입일)·부품보유(제조일) 구분 설명. 부품보유기간 값은 아직 제공하지 않음 |
| MCP 중지 | 터미널 A를 끄고 질문 | 일반 안내와 고객센터 문구, 상담 저장, backend 로그 `homecare_agent_fallback`(주소·원문 없음) |
| 지역 센터 | 배송지 서울 강남구 / 경기 성남시 분당구 / 시드 없는 지역 | 같은 지역 센터 또는 시·도 fallback. 전체 자료 없으면 공식 전국 센터 찾기. 가장 가까운 거리순이라는 주장 금지 |
| 이력 | 상담 뒤 이전 상담을 열기 | 저장한 보증·도구 카드와 근거가 보임. 이후 정책으로 과거 결과를 바꾸지 않음 |

보증 UI의 무상은 ‘조건 충족 시 무상’이다. 실제 고장 원인과 엔지니어 승인까지 확정한 것으로 채점하지 않는다. ‘A/S 연결 성공’도 이 프로젝트에서는 공식 접수 경로·요약을 준비한 것을 뜻하며 실제 예약 완료와 구분한다.

## 3-1. 실제 OpenAI 수동 E2E

자동 E2E는 모의 OpenAI가 정해진 tool call을 돌려주므로 실제 모델의 도구 선택은 검증하지 않는다. 개발 환경의 기존 키로 아래를 수동 확인하고 결과를 평가표에 기록한다. 운영 데이터·운영 사용자 토큰은 쓰지 않는다.

1. 위 2장처럼 MCP·backend·React를 띄운다(`HOMECARE_AGENT_ENABLED=true`). 테스트 계정으로 로그인한다.
2. 3장의 ‘모델 문장’, ‘접미사’, ‘등록 세탁기 수리비’, ‘타는 냄새’, ‘구입일 없음’, ‘MCP 중지’를 각각 새 상담으로 입력한다. 유료 호출은 시나리오당 1회로 제한하고 사용자당 분당 5회 요청 한도를 고려한다. OpenAI 키가 자리표시 값이면 이 단계는 미실행으로 기록한다.
3. 각 상담에서 `GET /api/conversations/{id}/tool-calls`로 실제 호출 순서·선택 주체·상태·소요 시간을 기록한다. `/api/chat` 응답의 `reply`에 도구 결과에 없는 금액·기간이 없는지, 수리비가 ‘참고용 예상 범위’·신뢰도와 함께 나오는지 본다.
4. 모델이 기대 순서와 다르게 호출하면 실패 유형(TOOL_SELECTION 등)으로 분류한다. 서버가 보완한 호출(POLICY_FALLBACK)은 LLM 선택 성공으로 세지 않는다.
5. 실행 일시, OpenAI 모델명, 데이터 버전(`meta.data_version`), 커밋을 함께 남긴다.

## 4. 첨부 T1~T19와 현재의 차이

아래는 코드·자료 검토에 따른 상태이며 T1~T19를 실제로 모두 실행한 결과표가 아니다. 현재 94개 Java 테스트 번호와 첨부 T1~T19는 다른 집합이다.

| 첨부 기준 | 현재 준비된 것 | 완료를 위해 필요한 것 |
|---|---|---|
| T1~T3: 단계 진행·이미 해 본 조치 건너뜀 | 승인 계획의 한 단계 조회·버튼 결과 기록을 TEST_ONLY로 검증 | 실제 모델별 승인 계획, 자연어에서 이미 한 조치 추출, 끝까지 실패하면 2차 카드로 연결 |
| T4: 조치 없는 오류 | 미승인 조치 차단·원문 후보 반환 | NOT_VERIFIED와 원래 NO_SELF_CARE 사유 구분, 보증·비용·센터까지 일관된 연결 |
| T5: 일반/핵심 보증 분리 | 정책 계산·무상 항목 분리 | 실 제품 조건으로 냉장고 전체 흐름·비용 연결 확인 |
| T6/T11/T18/T19: 위험 | 로컬 키워드 14사례(인화성 오염 포함)·예/아니요·모름·단계 차단 | AI 문맥 판단, 부정/수증기/정상 열기 평가, 사례별 원문 경고. T19의 명시적 긴급 연락 문구 등 원문 요구와 대조 |
| T7: 버튼 품목과 문장 품목 충돌 | 서버에 명시한 품목·제품 불일치 입력 거부 | 자연어의 다른 품목 인식·사용자 확인 분기 |
| T8/T9/T15: 다른 제품/새 제품 | 직접 선택·등록·배송지역 연결 | 원문 확인 질문 흐름·기본 배송지 정책과 차이 정리, 실제 전환 시나리오 실행 |
| T10: 센터 없을 때·지역 변경 | 시·군·구→시·도→NONE, 서버 region_override 지원 | 지역 변경 UI, 시연 지역 자료 완성 |
| T12: TV 등 대상 외 | 4품목 API enum | 자연어 대상 외 판단·연결·상담 종료 |
| T13: 미분류 | 비용 추정 실패/자료 부족의 안전한 반환 | 증상 확인 버튼, UNCLASSIFIED 상태, 실제 REFERENCE 후기 또는 기본 안내까지 연결 |
| T14: AI 8초 초과 | MCP RPC timeout·로컬 위험 경고 | 원문 MessageInterpreter의 8초 제한과 오류 fallback·증상 버튼. MCP 5초 timeout과 다른 요구 |
| T16: 내 제품 수정 | 등록·조회 API | 제품 수정 API/UI, 구매일 필수 검증, 후보 묶음·보증 재계산 |
| T17: 같은 요청 재전송 | 상태 버전 검사·단계 버전 검사 | clientMessageId 기반 처리/결과 재사용·중복 저장 방지. 버전 검사가 멱등 처리를 대신하지 않음 |
| F-14/H1~H4: 효과 측정 | 도구 결과·대화 저장 | 이용 이벤트 15종, 과업 질문지·시간·이탈 측정 |

추가로 첨부의 입력 길이·상담 종료/이어 하기 정책과 현재 프로젝트의 차이를 변경 명세로 정리한다. 기술 구조는 원문 Node.js/FastAPI이고 현재 Spring AI이므로 제출 기술 요건에서 대체 허용 여부를 확인한다. 확인 전 전체 서버를 재작성할 필요는 없다.

## 5. 실제 LLM 평가와 개선 리포트

`역할 및 만들 문서.docx`의 본인 산출물은 Agent 설계서·평가 시나리오·평가 개선 리포트다. 다음 순서로 작성한다:

1. 현재 버전을 기준선으로 고정: commit/작업 버전, OpenAI 모델·설정, 프롬프트, 정책·지식·비용 데이터 버전과 기준일.
2. 먼저 판정 기준 고정: 해결/부분해결/실패, 필요한 도구·근거·추가 질문·금지 행동. 평가 후 성공 기준을 바꾸지 않음.
3. T1~T19와 보안·NULL·만료일·자료 없음 경계를 점검. 미구현/자료 미확보는 제외해서 성공률을 높이지 않고 별도 표시.
4. 초기 실제 Agent 평가는 50건 정도로 시작: 정상 30, 애매 10, 위험/범위 밖 10. 이는 프로젝트 설명의 권장 60/20/20 비율을 적용한 초기 제안이다.
5. 멘토링 자료의 최종 AI 평가 규모는 240개 이상. 구성 제안은 증상 100·오류코드 40·자가검진 응답 40·위험 40·비위험 20=240. 위험 13사례마다 최소 3문장, 부정 표현·수증기·정상 열기를 별도로 포함.
6. 실패를 PROMPT / RETRIEVAL / TOOL_SELECTION / TOOL_RUNTIME / DATA_LIMIT / CONTEXT / SAFETY / HALLUCINATION으로 분류.
7. 실패를 수정하고 같은 평가셋·같은 판정 기준으로 다시 실행. 개선에 직접 사용한 문장 외에 별도 검증 문장도 남겨 일반화 여부를 확인.

평가 한 행에 남길 내용:

```text
case_id, 시나리오 유형, 품목, 입력/버튼 순서, 사전 제품/상담 상태,
기대 도구, 실제 호출 순서, 선택 주체(LLM/POLICY_FALLBACK/SAFETY_POLICY),
근거 ID, 기대 보증/금액 상태, 실제 결과, 해결/부분해결/실패,
실패 유형, 지연 시간, 모델/정책/데이터/버전, 개선 내용
```

고정 위험 세트에서 위험 미탐이나 위험 상태의 자가조치가 나오면 해당 버전은 실패로 판정하고 수정한다. 근거 없는 기간·금액, 미확인을 0원으로 바꾸는 결과, 타 사용자 데이터 노출도 실패다. 목표 선택률·검색 적합률·응답 지연의 합격선은 실제 평가 전에 팀이 정하고 평가 후 유리하게 변경하지 않는다.

비용 패키지의 기존 20건 LOO 결과를 새 Agent 개선 성과로 사용하지 않는다. REFERENCE는 프롬프트/참고용, VALIDATION은 독립 검증용으로 분리하고 같은 사례로 보정·검증하지 않는다. v3 참고 총액과 구성 합계는 다른 계산 결과이므로 한쪽의 정확도를 다른 쪽에 적용하지 않는다.

## 6. 다음 작업의 우선순위

| 순서 | 할 일 | 완료 증거 |
|---|---|---|
| 1 | 자동 테스트 재현, 수동 보증·MCP 연결 확인, 위 표를 평가 시나리오로 고정 | 테스트 보고서, 화면/API 결과, 기대값이 먼저 작성된 평가표 |
| 2 | 개발 환경의 기존 키로 소규모 실제 Agent 기준선 평가 + 호출 기록 | 어떤 도구를 LLM이 선택했는지 확인할 trace와 실패 목록 |
| 3 | MessageInterpreter/상태 분기 보완 | 품목 충돌·증상·이미 한 조치·대상 외·AI timeout 대응. 안전 선행 검사를 약화시키지 않고 AI 문맥 판정 추가 |
| 4 | 자료 담당과 실제 승인 계획·증상/오류-부품 매핑·후기 split·시연 지역 센터 준비 | provenance/모델 조건/승인/REFERENCE·VALIDATION이 확인된 스냅샷. 우선 세탁기 OE 한 경로를 끝까지 완성 |
| 5 | 제품 수정·지역 변경·재전송 멱등성·1차/2차 카드 흐름·이벤트 구현 | T1~T19의 미완료 항목 해소, 로그·질문지 수집 |
| 6 | 같은 평가셋으로 개선 후 평가 + H1~H4 사용자 과업 | 전/후 비교표, 실패 원인별 개선, 사용자 결과 |
| 7 | 제출·시연 정리 | 설계서·평가 시나리오·개선 리포트·시연 영상 |

H1은 제품 전체/주요 부품/무상 조건 판단 오류율, H2는 출장비·부품비·기술료 이해 정답률과 확신도, H3는 공식 접수 경로 찾기 완료율/시간, H4는 승인된 단계형 자가검진 해결률/시간/이탈률로 측정한다. API 호출 성공률로 고객 효과를 대신하지 않는다. 소규모 사용자 시험은 초기 탐색으로 기록하며 표본이 작으면 효과를 확정적으로 일반화하지 않는다.

클라우드 배포는 핵심프로젝트 설명상 핵심 평가 대상이 아니므로 위 작업보다 먼저 할 필요가 없다. 운영 검증이 필요한 단계에서 V002~V005를 개발 DB에 먼저 적용하고, Linux 환경에서 기존 운영 스크립트 시험·MCP 이미지 실행·3서비스 구성 및 key/JWT 전달을 확인한다. 실제 운영 DB 변경과 배포는 별도 절차로 수행한다.
