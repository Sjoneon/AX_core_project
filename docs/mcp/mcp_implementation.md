# HomeCare AI MCP 도구 9개 구현 · v3.0

2026-10-13 · `core_project` · 냉장고(김치냉장고 포함), 세탁기, 건조기, 식기세척기

직접 실행할 검증 명령, 첨부 T1~T19와의 차이, 실제 Agent 평가 방법과 다음 작업은 [verification_guide.md](../guides/verification_guide.md)에 정리했다.

## 구현 결과

기본 상담은 Spring AI 단일 Agent가 실제 MCP discovery로 받은 도구를 고르고, Java MCP SDK의 Streamable HTTP로 FastMCP를 호출한다. FastMCP는 기존 로그인 JWT를 요청별로 전달하는 얇은 어댑터이며 데이터를 갖지 않는다. 데이터와 모든 판정(소유권·상태·보증·비용)은 Spring 한 곳에 있다. MCP 서버에는 OpenAI 키나 별도 LLM이 없다. 모든 도구는 읽기 전용이며 쓰기·예약·결제 도구는 없다.

| MCP 도구 | 하는 일 | 현재 자료의 제한 |
|---|---|---|
| `lookup_model` | 고객이 말한 모델 코드를 판매 코드 52개와 정확히 일치할 때만 조회. 원문 보존, 접미사 미절단, 대표 모델은 공통 자료용 | 상담 품목과 다르면 경고만. 비슷한 코드를 제안하지 않음 |
| `search_knowledge` | 제조사 전용 검색: 적용 필터·정확 코드/표시·증상 어휘·캐시 임베딩. 관련 안전 최대 2건 우선, 보완은 별도 연결 | 모델 미판정은 검증된 품목 공통 범위만. 근거 부족은 NO_DATA/NEEDS_INPUT, 의미 검색 장애는 어휘 대체 |
| `get_product_context` | 본인 등록 제품 목록과 상담에서 직접 선택한 제품 조회 | 자동 선택하지 않음 |
| `get_diagnostic_plan` | 위험 차단, 승인된 모델·오류의 자가검진을 한 단계씩 조회 | TR15WV5/UE/세탁물 뭉침 1건. 선행 조건 6개 확인 후 현재 단계 1개, 나머지는 `NOT_VERIFIED` |
| `check_warranty` | 품질보증(구입일 기준)과 주요 부품 보증을 분리, 부품보유기간(제조일 기준)을 별도 블록으로 | 구입일이 없으면 `NEEDS_INPUT`. 부품보유기간은 B30 원본 확인 전까지 `UNAVAILABLE` |
| `get_repair_candidates` | 품목의 부품 코드·이름과 `candidate_set_id` | 4품목 32개 그룹. 등록 제품 없이도 동작. 국내 SKU 호환을 확정하지 않음 |
| `estimate_repair_cost` | 참고용 예상 범위(중앙·80% 범위), 신뢰도, 시간대·월 출장비 가산, 고정 고지문 | 견적·무상 판정 아님. 보증은 문장으로만 안내하고 0원으로 바꾸지 않음 |
| `find_service_centers` | 제품 배송지 시·군·구 → 시·도 → NONE, 공식 경로 조회 | 공식 확인한 센터 3곳만 시드. 거리순·전국 전체 자료가 아님 |
| `prepare_service_handoff` | 확인한 사실을 복사할 요약과 공식 접수 경로로 구성 | `PREPARED_ONLY`. 외부 전송·예약·전화 발신 없음 |

모든 결과의 공통 필드:

| 필드 | 의미 |
|---|---|
| `status`, `data`, `evidence`, `missing_fields`, `warnings`, `error`, `meta` | 기존 봉투. `warnings`는 상황별 경고, `meta`는 계약 버전 3.0.0·데이터 버전·상담 버전·trace_id |
| `source` | 데이터 버전, 읽은 파일(`homecare/parts.json` 등), fact ID(근거 ID) |
| `confidence` | HIGH·MEDIUM·LOW·UNDETERMINED. 비용은 수리비 표 등급(중간→MEDIUM, 낮음→LOW) |
| `caveats` | 고정 해석 고지. 첫 줄은 항상 "도구 결과는 후보 정보이며 조작·무상 보증·부품 장착을 승인하지 않습니다" |
| `independent_action_allowed` | 항상 false. Java 레코드가 true를 거부하고 Python 어댑터도 `Literal[False]`로 검사 |

실제 계약은 [mcp-tools.contract.json](mcp-tools.contract.json)이며 `python -m scripts.export_contract`로 discovery에서 생성한다. CI가 계약과 코드의 불일치를 실패로 처리한다.

## 구조와 인증

```text
React → /api/chat → Spring Agent → Java MCP Client → FastMCP /mcp (내부 전용, Host/Origin 엄격 검증)
                                                    ↓ 요청별 JWT + context version
                                         Spring /api/tools/{tool} → 소유권/상태/자료/규칙 계산
```

- LLM에 공개하는 인자에서 `conversation_id`, 수동 보증 `request`, `region_override`를 제거하고 서버가 인증된 상담 ID와 버전을 주입한다. 사용자 ID·이메일·토큰·가격·보증기간·기준일은 인자가 없고, 허용되지 않은 인자는 Python(Pydantic `extra=forbid`)과 Spring 양쪽에서 거부한다.
- `model_code`는 바깥 공백 제거·대문자 변환 후 `[A-Z0-9./-]` 1~40자만 허용한다. 또 최근 고객 문장이나 등록 제품에 실제로 있는 코드만 조회한다. 문서·도구 결과에 섞인 지시가 다른 모델을 조회시키지 못한다.
- `month`는 정수 1~12, `time_slot`·`product_category`는 열거값만, `query`는 1~300자, `top_k`는 1~5다.
- 상담·제품은 본인 소유만 조회한다(`OWNERSHIP_NOT_FOUND`). `X-HomeCare-Context-Version`이 다르면 409(`CONTEXT_STALE`)다.
- 한도는 `homecare.agent.*` 한 곳에서 정한다. 한 질문에 도구 5회, 도구당 2회이며 서버 정책 호출도 포함한다. MCP RPC별 5초, 세션 20초다. 한도에 닿으면 그때까지의 결과로 답한다.
- 인증·소유권·상태 오류는 요청을 끝낸다. 인자 오류·도구 장애는 코드만 모델에 돌려준다(`{"error":"INVALID_ARGUMENT"}`).
- `/api/tools/`는 Nginx에서 404로 막는다. Spring도 `X-Forwarded-For`가 있는(=Nginx를 거친) 요청을 404로 막는다.
- 로그에는 trace_id·도구·상태·소요 시간만 남긴다. FastMCP의 인자 검증 실패 로그에서도 입력 값을 지운다.

## 답변 생성과 장애 대체

- LLM은 메시지 구조화·도구 선택과 최종 문단 구성에 쓴다. 4단계의 `AnswerContext/GroundedAnswerGenerator`가 검증된 설명의 참조·문단·연결 문장을 생성하고 서버가 본문을 복원한다. 금액·기간·날짜·연락처는 서버의 도구 안내 문장 그대로 유지한다. [구성·검증·14개 실제 모델 평가](grounded_answer_generation.md)를 참고한다.
- 생성 결과는 `tools.answer_generation`의 `MODEL_GROUNDED`와 `FIXED_FALLBACK`, 사유·근거 ID·생성 시간으로 구분한다. 생성·근거 검증 실패와 시간 초과에는 기존 고정 안내를 사용한다. 최종 생성은 도구를 호출하지 않고 기존 MCP 세션의 남은 예산과 기본 5초 제한을 사용한다.
- 안전 문장은 항상 맨 앞에 둔다. 모델 코드를 찾지 못하면 라벨 확인을 요청하고, 구입일이 없으면 구입일을 묻는다. 품질보증(구입일)과 부품보유(제조일)는 다른 기간이라고 설명한다.
- 수리비는 "참고용 예상 범위(견적 아님)", 신뢰도, 평일 출장비 28,000원 기준, 고지문을 함께 말한다.
- MCP 연결·초기화 실패와 OpenAI 장애 때는 LLM 없이 고정 안내, LG전자 고객센터, 출장 예약 링크로 답하고 상담은 저장한다(`agent_fallback`). 로그에는 trace_id·사유·예외 클래스만 남긴다. 비에이전트 `ChatService`는 가상 테스트 매뉴얼 기반이라 대체 경로로 쓰지 않는다.

## 보증의 구분

[warranty_implementation.md](warranty_implementation.md)에 정책·날짜 계산·4품목별 기간을 정리했다. 정책은 [LG 공식 요금·보증 안내](https://www.lge.co.kr/support/rates-warranty-guide)를 확인했다.

제품 전체 보증이 끝나도 해당 부품의 별도 보증은 남을 수 있다. 핵심 부품의 별도 보증을 다른 부품에 적용하지 않는다. `check_warranty`는 부품비·기술료·출장비 항목을 조건부(`CONDITIONAL_FREE`)·유상·확인 필요로 표시하지만, 개인별 무상 여부나 면제를 판정하지 않는다. 비용 도구는 보증을 금액 0원으로 바꾸지 않고 "무상 수리 대상일 수 있음, 점검 후 결정" 문장만 붙인다.

## 상담 상태와 안전

같은 품목의 등록 제품이 하나면 상담 시작 시 자동 연결한다. 여러 제품이 있으면 입력한 표시 이름·정확한 모델명으로 구분하며, 다른 모델을 입력하면 보유 제품으로 대체하지 않는다. 상담 화면에는 별도 제품 선택 패널을 두지 않는다. 자가검진은 `actionApproved=true`이고 정확한 모델·현재 오류 또는 증상이 일치하는 계획만 쓰며 한 단계씩 진행한다.

위험 후보는 `RiskRules`의 14개 사례 키워드로 찾고, 의심 문장은 `RiskAssessmentService`가 최대 8초 안에 AI 문맥으로 판정한다. 실패하면 키워드 후보를 유지하고, 긴급 신호는 AI나 MCP를 기다리지 않고 즉시 안내한다. 인화성 오염은 RC14다. 위험 확인 버튼·이력·조치 차단은 일반 증상 해석과 별도다.

2026-10-14에 `MessageInterpreter/DialogueRouter`로 품목별 5개 증상, 비용·보증·A/S 문의, 확인 질문·응답과 후속 대화를 연결했다. 상태는 기존 상담 JSON에 저장하며 최근 대화 12개를 전달한다. 해석 실패·8초 초과 시 증상 선택을 제공하고 질문 뒤에도 모르면 `UNCLASSIFIED`로 안내한다. 완료 조치는 정확한 모델의 승인 단계와 대조하며 3단계 당시 `approved-plans.json=[]`이었다. 5단계의 조건부 계획 등록은 아래 기록과 분리한다. `tools.consultation`은 화면용 서버 상태 결과이고 MCP 도구가 아니다. [분류·상태·38개 실제 모델 평가](symptom_interpretation.md)를 참고한다.

5단계는 [원문 검토·조건·이력 계약](approved_self_care.md)에 정리했다. 조건 6개를 모두 확인하기 전에는 조치를 반환하지 않으며 제품 수정·위험 감지는 확인 값을 초기화한다. 현재 승인 단계만 안내하고 결과와 다음 안내를 같은 트랜잭션에 저장한다. `precondition-answer`와 `step-answer`는 `ChatResponse`를 반환한다. 실행 경로 검토는 제조사 승인·실물 시험을 의미하지 않으며 진동은 미승인 상태를 유지한다.

## 연결한 원자료와 무결성

`backend/src/main/resources/homecare/source-manifest.json`(버전 `B30-COST-V3-20261014-R5`)에 원본·생성 자료의 출처·해시·제약을 저장했다. Spring은 기동 시 데이터 파일 9개(homecare 8개 + `warranty/policies.json`)의 SHA-256을 매니페스트와 대조하고 건수도 확인한다. 하나라도 다르면 기동하지 않는다. 데이터 JSON은 `.gitattributes`로 줄바꿈 변환을 막는다.

- LG B30 전체 사실 1,659건 중 현재 로컬에 들어온 1,542건을 사용한다. 기존 검수에서 발견한 누락 117건(김치냉장고 97건 포함)은 구조화 작업으로 복원되지 않았다. 각 기록의 `effective_model_lists`·`current_QA_review`·`latest_applicability_update`·`procedure_supplements`·`use_policy`는 원본 파일에 그대로 남아 있다. Spring이 기동 시 읽어 적용 판정에 쓴다(최신 적용 목록 우선, 제외 모델 제거).
- 2026-10-14에 기존 원본 1,542건을 변경 없이 보존하고 `structure_knowledge.py`로 `knowledge-structured.json` 1,542건을 생성했다. 검색 본문 `searchText`와 화면 본문 `displayText`를 분리하고 원인별 분기·선행 조건·그림/실물 제약·보완 관계를 보존한다. `search_knowledge`와 자가검진 `references`는 구조화 결과를 사용하며 원본 래퍼 JSON을 반환하지 않는다. 기존 저장 상담 표시 정리는 후속 작업이다. [대상 자료·원문 대조·생성/검증 기록](manufacturer_data_structure.md)을 참고한다.
- 같은 날 2단계에서 제조사 전용 `KnowledgeRetriever/KnowledgeVectorIndex`를 MCP 경로에 연결했다. 1,542개 사실을 임베딩하고 `SimpleVectorStore`의 모델 후보 ID 필터·버전별 디스크 캐시·2초 대체 경로를 사용한다. 실제 임베딩 평가 33개와 구현 검사를 통과했다. [검색·캐시·비용·평가 기록](manufacturer_retrieval.md)을 참고한다. 자연어 상담 상태는 3단계, 검증된 근거를 사용하는 최종 답변 구성은 4단계에서 연결했다. 기존 저장 상담 표시 정리는 후속 단계다.
- 수리비는 `한국기준_예상수리비_표_v3.csv`(SHA-256이 매니페스트 `costSha256`과 일치)를 `backend/scripts/build_homecare_data.py`로 변환했다. 33개 중 D02 '히터(미국 전기식)'는 CSV 신뢰도 열에 "제외(미국형 전기식 건조기 전용 부품)"로 표시돼 32개를 쓴다.
- 출장비 가산은 CSV 열을 그대로 쓴다. 야간·휴일 +5,000원, 6~8월 평일 +5,000원, 6~8월 야간·휴일 +10,000원(야간 가산과 중복 합산하지 않음)이다. 패키지 `model_v3.py`의 `SURCHARGE`와 `normalize.py`의 출장비 28,000/33,000/33,000/38,000원으로 해석을 확인했다. 고정 가산이므로 중앙·하한·상한에 같은 금액을 더한다.
- v3는 국내 LG 유상 20건 검증에서 중앙 절대오차 11.2%, 80% 범위 적중 80%였다. 같은 20건으로 등급·범위를 골랐으므로 새 사례 오차는 더 클 수 있다(비용 결과 `caveats`에 포함).
- 부품보유기간: 변환본 T5 기록에 `parts_holding_period` 문장이 있으나 식기세척기 품목 공통 5년(T5-004)과 모델별 7년(T5-009 등)이 충돌한다. B30 원본(`knowledge_effective_B30.jsonl`, `checksums_B30.json`)으로 확인하기 전까지 값을 제공하지 않는다.
- `repair-cases.json=[]`이고 `approved-plans.json`에는 TR15WV5/UE/세탁물 뭉침 조건부 계획 1건이 있다. 센터는 대치·분당·북수원 3곳이다.

## 웹 API

기존 JWT 인증을 사용한다.

| API | 용도 |
|---|---|
| `GET/POST /api/me/products` | 내 제품 조회/등록 |
| `GET /api/warranty/policies`, `POST /api/warranty/check` | 정책 조회, LLM 없이 직접 보증 계산 |
| `POST /api/chat` | 상담. 사용자별 분당 5회(연속 3회)·하루 100회, 초과 시 429 + `Retry-After` |
| `GET /api/conversations/{id}/context` | 현재 제품·위험·조치 상태 |
| `GET /api/conversations/{id}/tool-calls` | 본인 상담의 도구 호출 기록(V005) |
| `POST /api/conversations/{id}/product`, `/risk-answer` | 제품 확정, 위험 확인 |
| `POST /api/conversations/{id}/precondition-answer`, `/step-answer` | 현재 조건·조치 결과를 상태와 이력에 저장하고 다음 안내의 `ChatResponse` 반환 |
| `POST /api/tools/{name}` | MCP 컨테이너 전용(외부 차단) |

Nginx는 IP별로 `POST /api/chat` 분당 30회(burst 10), `/api/auth/` 분당 10회(burst 5), 나머지 `/api/` 초당 10회(burst 30)를 넘으면 429를 준다.

## 도구 호출 기록

`chat_tool_calls`(V005)에 답변별 호출 순서, 도구 이름, 허용 목록 인자, 서버 주입 필드, 선택 주체, 상태·오류 코드, 결과 요약, 소요 시간, trace_id를 저장한다. 고객 원문·이메일·토큰은 저장하지 않으며 검색어는 글자 수만 남긴다. `chat_messages.tool_results`는 화면용 전체 결과로 유지한다. 상담 삭제 시 함께 지우고 90일 뒤 자동 삭제한다. 적용 절차는 [rds_initialization.md](../../ops/docs/rds_initialization.md)에 있다.

## 실행·배포

1. 로컬은 [mcp_server/README.md](../../mcp_server/README.md)를 따른다.
2. 운영 DB에는 배포 전 V002 → V003 → V004 → V005를 1회 적용해야 한다(운영 `ddl-auto=validate`). 이번 작업에서 운영 DB에 적용하지 않았다.
3. CI/CD는 backend·nginx·mcp 이미지 3개를 게시하고 `compose.prod.yaml` + `compose.mcp.yaml`로 배포한다. 실패 시 이전 릴리스로 복원한다. Terraform ECR `mcp`·로그 그룹 생성, GitHub 변수 `ECR_MCP_REPOSITORY` 추가가 먼저 필요하다.
4. MCP 컨테이너를 배포해도 상담 에이전트는 `HOMECARE_AGENT_ENABLED=true`를 따로 정할 때까지 꺼져 있다. 켜는 시점과 방법은 사용자 승인 후 정한다.

## 검증 범위

백엔드 JUnit은 다음을 검사한다.

- 실제 Java SDK → FastMCP HTTP → Spring의 9개 도구 호출과 호출 한도.
- 모의 OpenAI의 정해진 tool call로 E2E 4개 시나리오(모델 문장→조회→근거→부품→비용, 타는 냄새 안전 우선, 구입일 없는 보증, MCP 장애 대체 응답).
- 답변 숫자와 도구 결과의 대조, 주입된 인자 거부, 호출 기록·로그의 개인정보, 상담 삭제 시 기록 삭제.
- 데이터 변조 시 기동 실패, 수리비 CSV 대조(원본이 있을 때), 속도 제한.

Python은 FastMCP discovery/call, 인자 거부, 원문 전달, 토큰 없는 호출 거부, 로그 마스킹, Host/Origin 검증을 검사한다. MySQL 8.4 V001~V005 + validate 검사, Docker 이미지, Nginx 프록시 시험, 프론트엔드 lint/build는 CI에서 실행한다. 실 OpenAI의 도구 선택 정확도는 [검증 가이드](../guides/verification_guide.md)의 수동 절차로 별도 확인한다.
