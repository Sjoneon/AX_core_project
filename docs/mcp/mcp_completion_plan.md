# HomeCare AI 미완성 기능과 단계별 작업 계획

- 작성일: 2026-10-14
- 기준: 현재 작업 폴더의 코드·데이터와 로컬 상담 재현 결과
- 목적: 도구 연결 이후 남아 있는 검색, 상담, 답변 표시 기능의 작업 순서 정리
- 상태: 1~4단계 구현·검증 완료 (로컬 제조사 지식 1,542건, 검색 평가 33개, 실제 메시지 해석 38개, 실제 최종 답변 구성 14개 범위). 5~8단계는 후속 작업. [구조화 기록](manufacturer_data_structure.md), [검색 연결·평가 기록](manufacturer_retrieval.md), [증상·대화 상태 기록](symptom_interpretation.md), [최종 답변 기록](grounded_answer_generation.md)

## 현재 상태와 작업 범위

MCP 연결과 도구 호출은 구현되어 있다. 일반 증상을 분류하고, 관련 근거를 찾아 추가 질문·자가조치·비용 안내로 이어지는 상담 흐름은 아직 완성되지 않았다.

| 구분 | 현재 상태 | 남은 부분 |
|---|---|---|
| MCP 연결 | Java MCP Client → FastMCP → Spring 도구 API 호출, 인증·소유권·상태 버전 검사 구현 | 연결 성공과 검색·상담 품질을 분리해 평가 |
| 등록 제품 | 같은 품목의 제품이 하나면 자동 연결, 여러 개면 입력한 모델명·표시 이름으로 구분 | 여러 제품·정보 부족·제품 수정 후 상담 재개 시나리오 검증 |
| 위험 확인 | 키워드 후보에 대한 AI 문맥 판정, 긴급 신호 즉시 안내, 대화 안의 응답 버튼과 이력 저장 구현 | 기존 범위의 회귀 검증 유지 |
| 검색 | 제조사 전용 검색기: 모델·품목·제외 필터, 정확 코드/표시·증상 어휘·캐시 임베딩, 상담 상태·최종 답변 연결 | 평가 품목·표현 확대 |
| 기존 RAG | 가상 매뉴얼 RAG는 별도 유지. 제조사 1,542건의 `SimpleVectorStore`를 MCP 경로에 연결 | 운영 캐시 경로 설정·규모/표현 확대 평가 |
| 증상 분류 | 품목별 5개 증상, 의도·품목 해석, `symptomCode`·확인 질문·답 저장, 최근 대화·최종 답변 연결 구현 | 평가 표현 확대, 실제 승인 조치 연결 |
| 최종 답변 | AI가 검증된 설명의 참조·문단·연결 문장을 구성하고 서버가 본문을 복원. 실패 시 고정 안내, 생성 모드·사유 저장 | 실제 승인 조치와 전체 Agent 품질·상담 지연 평가 확대 |
| 자가조치 | 승인 계획 조회·단계 응답 API, 승인 단계와 정확히 연결된 자연어 완료·결과 기록 구현 | 실제 승인 계획 없음, 실제 모델의 선행 조건·다음 단계·실패 후속 경로 검증 |
| 수리비 | 부품 후보·참고 비용 계산 도구와 비용 카드 구현 | 자연어 증상에서 원인 후보로 연결, 상담 단계에 따른 호출·후속 안내 |
| 사용자 화면 | 새 도구 결과의 `content`는 구조화한 화면용 본문. 카드·근거·개발용 호출 이력 구현 | 필드별 조건·출처 표시, 과거 저장 JSON 처리, 중복 표시 제거 |

작업 시 유지할 사항:

- 세탁기 흔들림은 일반 고장 상담으로 다룬다. 이 작업을 이유로 불·전기 위험 사례에 추가하지 않는다.
- 기존 위험 판정과 위험 상태에서의 조치 차단은 유지한다.
- 등록 제품은 DB 정보를 사용한다. 추가 수동 등록·수정은 기존 마이페이지에서 진행한다.
- 제거한 상담 제품 선택 영역과 별도 보증 입력 폼을 다시 추가하지 않는다. 마이페이지와 전체 화면 디자인은 유지한다.
- 실제 제품 조치의 승인 조건과 모델 적용 조건을 유지한다. 자료가 있다는 이유만으로 실행 가능한 조치로 바꾸지 않는다.
- DB 계정·비밀번호·API 키, 기존 사용자·제품 데이터는 기능 보완과 별개로 보존한다.

### 확인된 재현 사례

입력: `탈수할때 세탁기 본체가 많이 흔들리면서 원 위치에서 크게 벗어나요.`

- `search_knowledge: OK`, `get_diagnostic_plan: NOT_VERIFIED`가 반환됐다.
- 입력 문장으로 현재 검색 점수를 재현하면 침수 문서 `T4-016`, `T4-036`은 `세탁기`만 일치해 선택된다.
- 급수구 거름망 청소 자료는 `세탁기`, `위치에`가 일치해 상위에 나온다. 원인·증상이 같다는 뜻은 아니다.
- 실제로 `FX24ENER`의 `소음 또는 진동이 심해요` 자료인 `B17-MFL71792818-topic_66-009`가 존재한다.
- 안전 문서는 `NOT_LISTED` 상태도 현재 선택 대상에 들어간다. 검색된 안전 문서와 위험 AI 판정은 서로 다른 경로다.
- `approved-plans.json`은 0건, `repair-cases.json`은 0건이다. 비용 부품 그룹은 32개, 센터 자료는 3건이다.
- 2026-10-14 14:16 로컬 확인 당시 기존 백엔드는 정상 응답했다. 14:13의 실행 실패는 두 번째 백엔드의 8080 포트 충돌이었다. 이 실행 실패는 위 상담 결과의 원인이 아니다.

## 작업 순서

| 단계 | 작업 | 선행 조건 | 완료 결과 |
|---|---|---|---|
| 1 | 제조사 자료 구조화 | 원본·검토 조건 확인 | 검색용 내용과 화면용 내용 분리 |
| 2 | 실제 자료 RAG 연결·검색 보완 | 1단계 | 증상·모델에 맞는 근거 검색 |
| 3 | 자연어 증상 분류·대화 상태 연결 | 1단계, 분류 체계 정의 | 추가 질문과 사용자 응답의 상태 반영 |
| 4 | 근거 기반 최종 답변 생성 | 2·3단계 | 도구 결과를 사용하는 상담 문장 |
| 5 | 승인된 자가조치·단계 진행 완성 | 2·3·4단계, 승인 자료 | 조건 확인 후 한 단계씩 안내 |
| 6 | 원인 후보·보증·비용·A/S 연결 | 3·4·5단계 | 미해결 상담의 후속 안내 |
| 7 | 상담 화면의 결과 표시 정리 | 1·4·5·6단계 | 사용자용 안내와 근거 표시 |
| 8 | 실제 상담 평가·회귀 검증·문서 갱신 | 1~7단계 | 완료·부분 완료·자료 부족을 구분한 검증 기록 |

1단계 이후 검색과 증상 분류를 각각 진행할 수 있다. 화면용 데이터 계약은 먼저 정하고, 실제 표시 검증은 상담 흐름 연결 후 수행한다.

## 1단계 — 제조사 자료 구조화

원본 `knowledge.json`의 `text`에는 원본 레코드·QA 검토·해시·사용 정책을 포함한 JSON 문자열이 들어 있다. 2026-10-14에 원본 바이트를 보존하고 `knowledge-structured.json` 1,542건으로 증상·오류·원인·조치 후보·조건·주의·출처를 추출했다. 검색은 `searchText`, 도구 결과의 `content`는 `displayText`를 사용한다. 구조화 대상과 원문 대조 사례는 [1단계 기록](manufacturer_data_structure.md)에 있다.

작업:

- [x] 증상, 오류 코드, 원인, 조치 설명, 적용 모델, 선행 조건, 주의사항, 공식 출처를 구분해 추출한다.
- [x] 검색용 텍스트에 해시·수집 이력·내부 QA 메타데이터를 섞지 않는다.
- [x] 사용자에게 표시할 설명과 내부 검토용 원본을 분리한다. 원본 보존과 화면 노출은 별도로 관리한다.
- [x] 원래 절차, 절차 보완, 조건부 검토 자료의 관계를 유지한다. 서로 다른 원인·모델의 절차를 합치지 않는다.
- [x] 데이터 파일을 변경하면 생성 경로와 `source-manifest.json`의 해시·버전·건수를 함께 갱신한다.

범위: 로컬 원본 1,542건을 1:1로 구조화했다. 전체 B30 1,659건 중 기존 누락 자료의 복원·공식 페이지 재수집은 포함하지 않았다. `is_action_approved=false`, 빈 승인 계획과 `NOT_VERIFIED`를 유지한다. 임베딩 RAG, 진동 검색 품질, 기존 저장 상담 표시를 이 단계의 완료로 계산하지 않는다.

관련 파일: [ToolDataCatalog.java](../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java), [KnowledgeOverlay.java](../../backend/src/main/java/com/project/backend/mcp/tool/KnowledgeOverlay.java), [SearchKnowledgeTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/SearchKnowledgeTool.java), [자료 디렉터리](../../backend/src/main/resources/homecare/).

완료 기준: 대표 문서의 증상·조건·출처를 원본과 대조할 수 있고, 검색 본문과 사용자 표시 본문에 원본 JSON 전체가 들어가지 않는다. 모델 목록에 포함됐다는 이유로 조치 승인 상태가 바뀌지 않는다.

## 2단계 — 실제 자료 RAG 연결과 검색 보완

2026-10-14에 `KnowledgeRetriever/KnowledgeVectorIndex`를 MCP `search_knowledge`에 연결했다. 기존 `ManualRetriever`의 가상 매뉴얼 경로와 분리하고, 제조사 1,542개 검색 단위의 증상/코드·모델 필터·임베딩 검색을 사용한다. 준비/장애 시 제한된 어휘 검색으로 대체하며 해당 모드를 결과에 기록한다. [검색 방식·캐시·비용·평가 기록](manufacturer_retrieval.md)에 상세 결과가 있다.

작업:

- [x] 1단계의 실제 제조사 자료를 검색 단위로 구성하고, MCP 검색 도구가 사용할 검색기를 연결한다.
- [x] 임베딩 검색의 저장·캐시·데이터 버전 갱신 방식을 정한다. 기존 `SimpleVectorStore` 재사용 여부는 실제 자료 크기와 실행 환경을 기준으로 결정한다.
- [x] 모델·품목·제외 조건을 반영한 검색을 구성하고, 오류 코드의 정확한 일치와 의미 기반 검색을 함께 평가한다.
- [x] `흔들림`, `진동`, `원래 위치에서 이동` 같은 표현 차이를 검색에 반영한다. `세탁기`처럼 넓은 단어만 일치하는 자료의 우선순위를 낮춘다.
- [x] 안전 문서도 증상과 적용 범위를 확인한다. 특정 모델용 자료와 검증된 품목 공통 자료를 구분한다.
- [x] 관련 근거가 부족하면 추가 확인 또는 자료 부족으로 반환한다. 결과 개수를 채우려고 무관한 문서를 제공하지 않는다.
- [x] 실제 제조사 자료와 가상 테스트 매뉴얼을 분리하고, 임베딩 비용·초기화 시간·재검색 시간을 기록한다.

검증 범위: 실제 임베딩 평가 33개, 캐시/적용/장애 단위 검사와 실제 FastMCP HTTP 통합 검사. 재현 입력은 `B17-MFL71792818-topic_66-009`를 찾고 무관한 침수·급수·배수 문서를 선택하지 않는다. 조치 승인·최종 상담 답변·누락 원자료 복원은 이 단계의 완료로 계산하지 않는다.

관련 파일: [ChatController.java](../../backend/src/main/java/com/project/backend/chat/ChatController.java), [ManualCatalog.java](../../backend/src/main/java/com/project/backend/rag/ManualCatalog.java), [ManualRetriever.java](../../backend/src/main/java/com/project/backend/rag/ManualRetriever.java), [SearchKnowledgeTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/SearchKnowledgeTool.java).

완료 기준: 재현 문장의 검색 결과에 해당 모델의 진동 자료가 포함되고, 침수·급수·배수 자료가 단순 공통 단어 때문에 안내 근거로 선택되지 않는다. 관련 자료가 없는 경우도 별도 평가한다. 벡터 검색 도입 자체를 검색 품질 통과로 보지 않는다.

## 3단계 — 자연어 증상 분류와 대화 상태 연결

2026-10-14에 `MessageInterpreter/DialogueRouter`를 연결했다. 지원 품목별 5개 증상과 일반 증상·비용·보증·A/S 문의를 해석하고 `symptomCode`, 질문·응답, 해석 출처를 기존 상담 JSON에 저장한다. 해석기와 Agent에 최근 대화 12개를 전달하고 후속 검색의 기본값은 저장된 증상 설명을 사용한다. [분류 체계·실패 정책·검증 기록](symptom_interpretation.md)에 상세 결과가 있다.

작업:

- [x] 지원 품목별 증상 분류 체계를 정하고, 자연어 분류 결과를 `symptomCode`와 필요한 상담 상태에 저장한다.
- [x] 일반 고장 증상, 보증·비용 문의, 대상 외 품목, 정보 부족을 구분한다. 일반 증상 분류를 위험 판정과 분리한다.
- [x] 분류가 애매하면 필요한 추가 질문을 대화 안에 표시하고, 답변을 받아 상태를 갱신한다.
- [x] `그건 이미 확인했어요`, `그래도 안 돼요` 같은 후속 응답이 이전 질문·조치와 연결되도록 필요한 대화 이력을 전달한다.
- [x] 사용자가 이미 수행한 조치는 확인된 단계와 연결해 기록한다. 표현이 비슷하다는 이유로 다른 조치를 완료 처리하지 않는다.
- [x] 분류 실패·시간 초과·정보 부족의 대체 응답과 재질문 한도를 명세에 맞춰 정의한다.

검증 범위: 실제 `gpt-4o-mini` 메시지 해석 38개와 단위·FastMCP HTTP 통합 검사. 8초 제한·JSON 오류는 증상 선택으로 대체하고, 증상 질문 뒤에도 모르면 `UNCLASSIFIED`로 공식 점검을 안내한다. 실제 `approved-plans.json=[]`은 유지하며 완료 조치의 양성 검사는 격리된 `TEST_ONLY` 자료로 수행했다. 전체 명세의 증상 100문장·오류 표현 40개·조치 응답 40개 평가, 실제 승인 조치와 생성 답변의 끝까지 상담은 후속 범위다.

관련 파일: [ConsultationService.java](../../backend/src/main/java/com/project/backend/mcp/consultation/ConsultationService.java), [ConsultationState.java](../../backend/src/main/java/com/project/backend/mcp/consultation/ConsultationState.java), [AgentChatService.java](../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java), [ConversationService.java](../../backend/src/main/java/com/project/backend/conversation/ConversationService.java).

완료 기준: 오류 코드가 없는 진동 문장도 증상으로 분류되고, 추가 질문의 답이 저장돼 다음 안내에 사용된다. 같은 상담의 후속 응답에서 모델명과 이미 확인한 정보를 반복해서 묻지 않는다.

## 4단계 — 근거 기반 최종 답변 생성

2026-10-14에 `AnswerContext/GroundedAnswerGenerator`를 연결했다. 도구 선택 후 별도 모델 호출이 검증된 설명의 참조 ID·문단 순서·허용된 연결 문장을 구성하고, 서버가 증상·원인 후보·조건·미확인 정보와 도구 안내 본문을 복원한다. 금액·보증·날짜·연락처를 자유롭게 다시 쓰는 방식은 허용하지 않는다. 생성 결과가 정상 답변에 사용되며, 생성·검증 실패는 기존 `format()` 안내로 대체한다. [구성 방식·검증·시간 제한 기록](grounded_answer_generation.md)을 참고한다.

작업:

- [x] 검증된 검색 근거·도구 결과·상담 상태로 최종 상담 문장을 생성하는 경로를 연결한다.
- [x] 실제로 확인한 정보, 불확실한 원인 후보, 추가로 필요한 정보를 구분해 설명한다.
- [x] 금액·보증기간·날짜·연락처는 검증된 도구 값만 사용한다. 기존 계산과 검증을 생성 모델로 대체하지 않는다.
- [x] 근거 ID·모델 조건·승인 상태를 확인하고, 문서 안의 지시문은 데이터로 취급한다.
- [x] 답변 생성 실패나 검증 실패 시 기존 고정 안내를 대체 경로로 사용한다.
- [x] 기존 도구 호출 수·시간 제한 안에서 동작하도록 생성 경로와 지연을 평가한다.

검증 범위: 실제 `gpt-4o-mini` 최종 답변 구성 14/14, 단위 검사와 FastMCP HTTP 통합 검사. 생성은 기본 최대 5초 또는 기존 MCP 세션 20초 예산의 남은 시간 중 작은 값으로 제한하며 추가 도구는 호출하지 않는다. 최종 실제 모델 평가의 생성 시간은 최대 3,074ms였다. 위험 안내·확인 질문은 기존 정책 경로를 유지한다. 실제 승인 조치, 전체 실제 Agent의 도구 선택·끝까지 상담, 자유로운 사실 재서술의 품질 평가는 이 단계의 완료로 계산하지 않는다.

관련 파일: [AgentChatService.java](../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java), [기존 ChatService.java](../../backend/src/main/java/com/project/backend/chat/ChatService.java), [McpGateway.java](../../backend/src/main/java/com/project/backend/mcp/client/McpGateway.java).

완료 기준: 답변이 현재 증상을 설명하거나 필요한 확인 질문을 제시하고, 근거 없는 조치·금액·기간을 만들지 않는다. 최종 AI 문장이 정상 경로에서 사용되는지 확인하며, 대체 응답과 구분해 기록한다.

## 5단계 — 승인된 자가조치와 단계 진행 완성

승인 계획 조회와 단계 응답 API는 구현되어 있으나 `approved-plans.json`은 비어 있다. `NOT_VERIFIED`는 실행 승인 미확인이고, 실제로 자가조치가 없다는 `NO_SELF_CARE`와 다르다.

작업:

- [ ] 실제 모델·증상·오류에 해당하는 조치를 원문과 검토 조건으로 확인하고 승인 계획에 등록한다.
- [ ] 실물·기능·현재 상태·원문 그림 확인 등 필요한 선행 조건을 질문하고, 미확인 조건을 건너뛰지 않는다.
- [ ] 승인된 계획만 한 단계씩 안내하고, 이미 수행한 단계는 건너뛴다.
- [ ] 예·아니요 응답과 다음 안내를 상담 상태·대화 이력에 함께 기록한다. 현재 단계 응답은 상태 갱신과 다음 계획 반환까지 연결되어 있다.
- [ ] 해결, 다음 단계, 모든 단계 실패, 승인 미확인, 실제 조치 없음의 후속 경로를 구분한다.
- [ ] 자료의 `approved_model_codes`만 보고 일괄 실행 승인하지 않는다. 기존 실행·안전 제약을 유지한다.

관련 파일: [GetDiagnosticPlanTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/GetDiagnosticPlanTool.java), [ToolBackend.java](../../backend/src/main/java/com/project/backend/mcp/tool/ToolBackend.java), [ConsultationService.java](../../backend/src/main/java/com/project/backend/mcp/consultation/ConsultationService.java), [approved-plans.json](../../backend/src/main/resources/homecare/approved-plans.json).

완료 기준: 검토된 실제 자료로 최소 한 상담 경로의 조건 확인→단계 안내→결과 응답→해결 또는 후속 안내가 끝까지 동작한다. 승인되지 않은 진동 자료를 실행 절차로 제공하지 않는다.

## 6단계 — 원인 후보·보증·비용·A/S 연결

비용 도구와 계산 데이터는 존재한다. 현재 부품의 명시적 매핑은 오류 코드 중심이며, 자연어 증상과 상담 단계에 따른 후속 호출이 충분히 연결되지 않았다. 비용 데이터 전체가 없는 상태는 아니다.

작업:

- [ ] 일반 증상에서 근거가 있는 원인 후보로 연결하고, 불확실하면 추가 질문을 진행한다.
- [ ] 비용 문의, 자가조치 실패, 조치 불가 등 단계별로 필요한 도구 호출과 다음 안내를 정의한다. `NOT_VERIFIED`를 자동으로 자가조치 실패로 처리하지 않는다.
- [ ] `get_repair_candidates`의 허용 목록과 `candidate_set_id`를 확인한 뒤 `estimate_repair_cost`를 호출한다.
- [ ] 원인 후보를 최대 2개로 제한하고, 확신도·사례 기준이 부족하면 금액을 만들지 않는다. 서로 다른 후보의 비용을 합산하지 않는다.
- [ ] 등록 제품의 구매일·조건으로 보증을 조회하고, 제품 수정 후 보증·후보 묶음이 다시 계산되는지 검증한다.
- [ ] 수리비는 참고용 예상 범위·신뢰도·출장비 조건·공식 점검 후 결정이라는 설명과 함께 제공한다.
- [ ] 미해결 상태에서 지역 센터·공식 접수 경로·상담 요약으로 이어지도록 연결한다. 실제 예약·외부 전송 완료로 표시하지 않는다.

관련 파일: [GetRepairCandidatesTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/GetRepairCandidatesTool.java), [EstimateRepairCostTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/EstimateRepairCostTool.java), [CheckWarrantyTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/CheckWarrantyTool.java), [FindServiceCentersTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/FindServiceCentersTool.java), [PrepareServiceHandoffTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/PrepareServiceHandoffTool.java).

완료 기준: 비용 문의에 실제 부품 후보·비용 도구가 호출되고, 자료가 충분한 경우 참고 범위가 나온다. 불충분한 경우 부족한 정보와 다음 행동을 안내한다. 서버 보완 호출을 AI의 도구 선택 성공으로 계산하지 않는다.

## 7단계 — 상담 화면의 결과 표시 정리

1단계 이후 새 자가검진 참조와 검색 근거의 `content`는 화면용 설명이다. 기존 저장 상담의 원본 JSON 처리, 조건·출처의 필드별 표시와 중복 제거는 남아 있다. 최종 답변 생성과 별도로 프론트엔드 표시 흐름도 정리해야 한다.

작업:

- [ ] 1단계에서 정한 화면용 필드로 제목·설명·조건·공식 출처를 표시한다.
- [ ] 사용자 화면에서 원본 JSON, 해시, 내부 QA 이력, 코드 파일 경로를 제거한다. 필요한 개발 정보는 기존 DEV 전용 영역으로 분리한다.
- [ ] 같은 근거가 자가검진 카드와 검색 근거에 반복 노출되지 않도록 역할을 정한다.
- [ ] 일반 증상의 확인 질문, 자가조치 응답, 비용·보증·A/S 결과를 대화 안에서 이어서 표시한다.
- [ ] 저장된 과거 상담의 원본 JSON도 사용자용 필드로 표시할 수 있는지 검토한다. 기존 대화 내용을 무단 수정하지 않는다.
- [ ] 마이페이지·제품 등록 폼·전체 디자인을 유지하고, 제거한 별도 제품 선택·보증 입력 영역을 복원하지 않는다.

관련 파일: [Chat.jsx](../../frontend/src/Chat.jsx), [ToolResults.jsx](../../frontend/src/components/ToolResults.jsx), [ToolCallTrace.jsx](../../frontend/src/components/ToolCallTrace.jsx), [WarrantyResult.jsx](../../frontend/src/components/WarrantyResult.jsx).

완료 기준: 새 상담과 이력 조회에서 원본 JSON이 고객 화면에 나타나지 않고, 증상 설명·확인 질문·필요한 근거만 표시된다. 개발용 원본 JSON은 DEV 영역에서만 확인한다.

## 8단계 — 실제 상담 평가와 회귀 검증

기존 자동 검사는 도구 연결·계약·소유권·계산·상태 제약 등을 검증한다. 통과한 테스트 수와 실제 검색·상담 품질은 별도로 기록한다.

작업:

- [ ] 위 진동 재현 문장과 표현이 다른 동등 증상 문장을 평가 세트에 넣는다.
- [ ] 정답 근거, 제외할 문서, 기대 질문, 허용 조치, 기대 도구·상태를 실제 평가 전에 정한다.
- [ ] 같은 모델의 진동과 침수, 다른 모델, 자료 없음, 여러 등록 제품, 이미 한 조치, 후속 응답을 평가한다.
- [ ] 비용 문의·보증 문의·자가조치 실패·해결·A/S 연결을 포함해 끝까지 실행한다.
- [ ] 기존 위험 질문·긴급 안내, 인증·소유권, 버전 충돌, 프롬프트 주입, 장애 대체 응답을 회귀 검증한다.
- [ ] 호출 주체 `MODEL`, `POLICY_FALLBACK`, `SAFETY_POLICY`를 구분하고, 검색 적합성·근거 없는 내용·누락된 질문·지연을 기록한다.
- [ ] 수정 범위에 맞는 백엔드·Python·프론트엔드 검사를 수행한다. MCP 입력·출력 계약을 변경하면 관련 계약과 소비 코드를 함께 검증한다.
- [ ] `mcp_implementation.md`, `verification_guide.md`, `followup_prompt.md`, README의 오래된 상태 설명을 실제 결과에 맞춰 갱신한다.

관련 파일: [McpIntegrationTests.java](../../backend/src/test/java/com/project/backend/mcp/McpIntegrationTests.java), [ToolBackendTests.java](../../backend/src/test/java/com/project/backend/mcp/tool/ToolBackendTests.java), [검증 가이드](../guides/verification_guide.md), [MCP 구현 문서](mcp_implementation.md), [이전 후속 작업 문서](followup_prompt.md).

완료 기준: 단계별 완료 조건과 실제 상담 결과가 대응되는 기록이 있고, 완료·부분 완료·자료 부족·미검증을 구분한다. `OK`나 도구 호출 성공만으로 상담 완료 처리하지 않는다.

## 별도 후속 항목과 범위 확인

다음 항목은 현재 제한 또는 구현이 확인되지 않은 기능이다. 핵심 상담 1~8단계와 함께 전체 명세의 범위를 대조한다.

| 항목 | 현재 확인된 상태 | 후속 작업 |
|---|---|---|
| 부품보유기간 | `check_warranty`에서 `UNAVAILABLE`로 반환 | 원본 자료·제조일 기준·충돌 값을 확인해 구조화. 품질보증기간과 혼합하지 않음 |
| 수리 후기 기반 비용 | `repair-cases.json` 0건 | 이 경로를 사용할 경우 검증된 `REFERENCE` 자료 확보. `VALIDATION`은 추정 근거에서 제외 |
| 지역 센터 | 센터 자료 3건, 지역 순차 조회·공식 경로 구현 | 시연 지역 자료와 지역 변경 흐름 검증. 전체 지역·거리순 지원으로 표시하지 않음 |
| 요청 재전송 중복 방지 | `ChatRequest`에 `clientMessageId` 없음, 상태 버전 검사는 구현 | 재전송 식별·기존 결과 재사용·중복 저장 방지 설계와 구현. 버전 검사와 구분 |
| 이용 이벤트·효과 측정 | 상담·도구 이력은 저장하지만 이벤트 처리 코드는 현재 확인되지 않음 | 명세의 이벤트 범위를 대조하고 수집·집계·과업 평가 구현 |
| 구매 시 자동 제품 등록 | 제품 CRUD와 상담 제품 자동 연결은 확인. 외부 구매 이벤트 연동은 현재 확인되지 않음 | 구매 시스템·연동 경로·프로젝트 범위를 확인한 뒤 별도 작업 정의. 자동 등록과 상담 제품 자동 연결을 구분 |

운영 배포·RDS 마이그레이션·프로세스 재시작은 이 문서의 상담 기능 완료 작업과 분리한다. 현재 포트 충돌을 검색·RAG 문제의 원인으로 처리하지 않는다.

## 우선 완료할 상담 경로

1. 등록된 `FX24ENER`의 진동 증상 입력 → 해당 모델의 진동 근거 검색 → 필요한 일반 증상 확인 질문.
2. 검토된 자가조치 자료가 있으면 선행 조건 확인 후 한 단계씩 안내. 자료가 미승인이면 이유와 후속 경로 안내.
3. 해결되지 않거나 비용을 물으면 원인 후보 확인 → 보증·참고 비용 조회 또는 정보 부족 안내 → 공식 A/S 경로·요약 제공.
4. 새 상담·후속 응답·이력 조회까지 사용자 화면과 저장 상태 검증.

이 한 경로를 끝까지 확인한 뒤 같은 방식으로 나머지 품목·증상을 확대한다. 한 경로의 완료를 전체 MVP 완료로 표시하지 않는다.
