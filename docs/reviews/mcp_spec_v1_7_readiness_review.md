# 기능명세 v1.7·DB v0.4·수리비 v3 대비 MCP 작동 가능성 검토

검토일: 2026-10-13 (Asia/Seoul). 검토 대상은 현재 작업 폴더의 수정·미추적 파일을 포함한 구현이다. 이 검토에서 기능 코드와 운영 DB를 변경하거나 배포하지 않았다.

## 결론

**현재 `mcp_server`는 9개 조회 도구를 Spring에 연결하는 기반으로 작동한다. 기능명세 v1.7의 전체 MVP를 수행하기에는 충분하지 않다.** 부족한 부분 대부분은 MCP 전송 계층이 아니라 Spring 상담 흐름, 자료, 화면, DB 계약이다. MCP 서버를 다시 만드는 것보다 현재 어댑터를 유지하고 이 부분을 보완하는 편이 타당하다.

실제 FastMCP HTTP와 Java 클라이언트를 사용하는 개발 통합 테스트는 통과했다. 실 OpenAI의 도구 선택 품질, 실제 MySQL 스키마 적용, 운영 기동은 이번 실행으로 확인하지 않았다. 따라서 “프로토콜 연동 가능”과 “명세 시나리오 완료”를 별도로 판정한다.

## 확인한 원자료

Drive의 [LGAX_Core_project → data → 기능 명세 및 DB](https://drive.google.com/drive/folders/1tQYLeZCMrAdp7WbKX_6KpWRQhiat5R9Q)에서 기능명세·DB 설계서·SQL·시퀀스 4개 원본을 직접 내려받아 읽었다. 4개 모두 최초 검토에 사용한 바탕화면 로컬 파일과 바이트 단위 및 SHA-256이 일치한다. 수리비 자료와 B30 안내도 Drive에서 직접 확인했다. 멘토링 v3는 로컬 문서로 검토했다.

| 자료 | 읽은 원본 |
|---|---|
| 기능명세 | [Drive 기능명세 v1.7](https://drive.google.com/file/d/1_hoit_nbHjbbdvYwvF4k4PJC7c3uZoOM/view) |
| 사용자 흐름·시퀀스 | [Drive 시퀀스 v1.7](https://drive.google.com/file/d/1JhU_IcB0ARC608vwlqlXvod8BuDndrBZ/view) |
| DB 설계·SQL | [Drive DB 설계서 v0.4](https://drive.google.com/file/d/1KYTbxaJe6evQEpAf0-tqAlDpRIdRy-gK/view), [Drive homecare_ai_schema.sql](https://drive.google.com/file/d/1DmiKQHnKGIEvyliFcHs2Dx8FVemJLhLe/view) |
| 멘토링 | [2차 멘토링 자료 v3](<C:/Users/Roneon/Desktop/최종 기능 명세v0.4/HomeCare_AI_2차_멘토링_자료_피드백반영_v3.docx>) |
| 수리비 v3 | [Drive README](https://drive.google.com/file/d/1ZafZOEs7gdn6dlzQ76ngF6aSxwvHhiyH/view), [최종 CSV](https://drive.google.com/file/d/1-5zS1cbxQX39O-2MBGufl9HEAw0kJBzl/view), [실행 로그](https://drive.google.com/file/d/1MjZl28Vzg1dj9f1YtCwTfVJbLfY88ATE/view), [패키지 폴더](https://drive.google.com/drive/folders/1Yuk7WZoF3theT-b8qwrC-iqw2IG-DxbO) |
| 제품·지식 자료 | [Drive README_B30](https://drive.google.com/file/d/1sUE4Whd7bUXue8fW9bJ4F4VOSavzTgxw/view), 현재 `homecare/*.json`·매니페스트·변환 코드 |

## 실제 검증 결과

| 검사 | 결과·범위 |
|---|---|
| Drive 명세 원본 대조 | 기능명세·DB 설계서·SQL·시퀀스 4개 모두 최초 로컬 원본과 바이트 단위 일치. 문서 변경이 없으므로 이전 테스트 결과를 재사용 |
| Python MCP | `unittest discover -s tests -t . -q`: 20개 통과 |
| Java | JDK 21로 `gradlew.bat test bootJar --offline --no-daemon --rerun-tasks`: 130개, 실패 0, 오류 0, 건너뜀 0, JAR 빌드 성공 |
| 실제 discovery 계약 | 도구 9개, 저장된 `mcp-tools.contract.json`의 `tools`와 일치, 모두 `readOnlyHint=true` |
| Java↔MCP↔Spring | `McpIntegrationTests` 포함. 실제 HTTP 전송·소유권·상태 버전·후보 묶음·모의 OpenAI 흐름 검사 통과 |
| v3 CSV 대조 | Drive와 로컬 CSV의 헤더 및 33개 자료 행 전체 일치. Java `CostTableSourceTests`도 건너뛰지 않고 통과 |
| 자료 건수 | 판매 코드 52개, 지식 1,542개, 수리 부품 그룹 32개, 매핑된 그룹 0개, 승인 계획 0개, 후기 0개, 센터 3개 |
| 실 OpenAI | 미실행. 현재 도구 실행 환경에 `OPENAI_API_KEY`, `OPENAI_MODEL`, `JWT_SECRET`이 설정되지 않았고 backend에는 `.env.example`만 확인됨. 다른 IDE·배포 환경의 설정 유무는 미확인 |
| MySQL 원본 SQL·운영 DB | 미실행. 일반 Java 검증은 H2 사용, MySQL 전용 `mysqlSchemaTest`는 기본 테스트 집합에서 제외됨 |
| 프론트 lint/build | 이번 환경에서 `npm`을 찾지 못해 실행하지 못함. 화면 코드는 정적으로 검토 |

Java의 Spring AI deprecated API 경고는 있었지만 빌드 실패는 없었다. 기존 자동 테스트가 통과해도 명세 T1~T19 전체를 실행했다는 뜻은 아니다.

Drive 원본 대조 SHA-256(로컬과 동일):

| 파일 | SHA-256 |
|---|---|
| 기능명세 v1.7 | `be1ea7360a082cb7fb8157abf758e56d07315d39a8d072b07d2a921b7a2cbfe3` |
| DB 설계서 v0.4 | `d80514656eeb3dcaa3af2a254e0336e8bb8471f5e58d402da48c98b8a1ee077f` |
| homecare_ai_schema.sql | `79f94254b7cf40e727c9a6925e6b5ce67092c349d025ca0e3d276f5cf9554cf5` |
| 시퀀스 v1.7 | `7300fb636489dc8fbe9d83e7db4bbb337ed6850bf632e2976b4f4c6b108b7808` |

## 현재 구현으로 가능한 범위

구조는 `React → Spring Agent → Java MCP Client → FastMCP → Spring /api/tools/{name}`다. Python은 요청별 JWT와 상태 버전을 전달하고 결과를 검증하며, 실제 자료 조회와 보증·비용 계산은 Spring에 있다. `mcp_server` 단독으로 DB·보증·비용 기능을 제공하지 않는다.

- 모델 정확 일치 조회, 지식 후보 검색, 본인 제품 조회, 보증 계산, 참고 수리비 범위, 지역 센터 조회, 접수용 요약 준비가 구현돼 있다.
- 인증·소유권·상태 버전·허용 인자·후보 집합 검사와 오류 마스킹을 자동 테스트로 확인했다.
- 승인되지 않은 자가검진은 `NOT_VERIFIED`, 자료가 부족한 비용은 `NO_DATA` 등으로 반환한다. 이 반환 자체는 정상 동작이지만 해당 사용자 과업을 완료하지는 못한다.
- 센터는 시연 지역 3곳이다. 전국 전체 자료가 아닌 것은 명세의 시연 지역 제한과 양립한다. 지역 변경 화면은 별도 부족 사항이다.

## 주요 부족 사항

| 우선순위 | 명세·시퀀스 요구 | 현재 구현과 영향 | 코드 근거 |
|---|---|---|---|
| 높음 | F-03/F-06/F-08, SD-03~05: 9개 상담 상태, 품목 불일치 확인, 제품 확인 뒤 분기, 미분류 질문 | 상태는 위험·오류·단계·닫힘 일부만 보유한다. 자연어 품목·증상·이미 한 조치를 구조화하는 MessageInterpreter가 없고, `symptomCode`를 실제로 채우는 경로가 없다. 비용은 미등록 제품에도 허용되어 제품 확정 선행 조건도 강제되지 않는다 | `ConsultationState.java`, `ConsultationService.begin`, `AgentChatService`, `GetRepairCandidatesTool`, `EstimateRepairCostTool` |
| 높음 | F-09, T1~T3: 실제 모델에 승인된 단계 진행·이미 한 조치 건너뜀 | `approved-plans.json=[]`. 실제 자가검진 시작 경로가 자료 부족으로 막힌다. 버튼 결과를 처리할 기반은 있지만 자연어에서 이미 한 조치·해결 여부를 추출하지 않는다 | `GetDiagnosticPlanTool.java`, `ConsultationService.java`, `ToolBackend.answerStep` |
| 높음 | F-10, SD-08: 매핑→AI→후기 3건 이상→기본 안내 | 32개 그룹 모두 `targetCodes`가 비었고 `repair-cases.json=[]`. 현재 실질 경로는 AI 후보 선택 또는 자료 부족이다. 모델이 필요한 도구를 생략하면 비용·센터 호출을 강제하는 흐름도 없다 | `GetRepairCandidatesTool.java`, `EstimateRepairCostTool.java`, `AgentChatService.policyFallback` |
| 높음 | F-10/F-12, T1/T5: 구성 금액·부품별 보증·조건별 다음 행동 | 비용 도구는 v3 총액 범위와 보증 설명 문장을 반환한다. `next_action`은 항상 `VISIT_DIAGNOSIS`. `FREE_WARRANTY`·`CORE_CONDITIONAL`·`PAID_ESTIMATE` 분기와 출장비/부품비/기술료 구성 카드가 없다 | `EstimateRepairCostTool.java`, `frontend/src/components/ToolResults.jsx` |
| 높음 | F-05, T6/T11/T18/T19: 위험의 문맥 판단·해제 후 복귀·사례별 경고 | 위험 판단은 LLM 전에 로컬 규칙 14종으로 끝낸다. 위험 상태에서는 Agent/MCP를 거치지 않는 안전 경로를 쓴다. 점수·`return_state`가 없고 위험 답 버튼은 상태 갱신만 한다. 확정 뒤 자동 2차 카드 흐름과 사람 갇힘 등의 사례별 긴급 문구를 충족하지 않는다 | `RiskRules.java`, `ConsultationService.riskAnswer`, `AgentChatService.safety`, `ConsultationControls.jsx` |
| 높음 | F-03/F-13, T17: 동일 요청 ID 응답 재사용·중복 저장 방지 | 요청에 `clientMessageId`가 없고 DB 고유 키·처리 결과 재사용도 없다. 상태 버전 검사와 전송 버튼 잠금은 재전송 멱등성을 대신하지 못한다 | `ChatRequest.java`, `Chat.jsx`, `chat_messages` 관련 SQL |
| 높음 | DB v0.4의 원본 SQL로 앱 연결 | 원본 SQL은 `homecare_ai` DB와 단수 이름 17개 테이블이다. 현재 JPA는 복수 이름 5개 테이블을 읽고 기본 DB도 `project_db`다. 원본만 실행하면 JPA가 요구하는 테이블이 없다 | 원본 SQL, `ops/rds/sql/V001~V005`, JPA `@Table` |
| 중간 | F-01/F-02, T9/T16: 가입 주소·동의·배송지 기본값·제품 수정 | 가입은 이메일/비밀번호만 받는다. 제품은 구매일·시도/시군구를 저장하지만 주소·동의·계정 주소 기본값이 없다. 제품 수정 API/UI가 없다 | `SignupRequest.java`, `AppUser.java`, `UserProductService.java`, `UserProductController.java` |
| 중간 | F-11, T10: 센터 지역 변경·지역 목록 | MCP 도구의 지역 override는 있으나 명세의 지역 목록 API와 지역 변경 화면은 없다 | `FindServiceCentersTool.java`, `McpGateway.callback`, `ToolResults.jsx` |
| 중간 | F-03/F-13: 300자·25초 후 재시도·상담 종료 | 현재 입력은 4,000자이고 chat 화면 timeout은 120초다. 이력 조회/이어 하기가 제공되며 비용 카드 표시로 상담을 닫지 않는다. 명세의 1회 상담 종료 정책과 다르다 | `ChatRequest.java`, `Chat.jsx`, `ConsultationState.java` |
| 중간 | F-14: 이용 이벤트 15종·H1~H4 측정 | `/api/events`와 `event_log`가 없다. `chat_tool_calls`는 도구 감사 기록이므로 A/S 클릭·근거 열기·이탈·과업 시간 등의 이벤트를 대체하지 못한다 | 현재 controller·SQL·frontend 검색, `ToolCallLog.java` |

추가로 Agent prompt는 현재 질문·품목·제품 확정 여부만 전달한다. 과거 대화와 구조화된 해석 결과를 전달하지 않으므로, 미등록 상태에서 앞 질문에 말한 모델을 다음 질문에서 생략하면 모델 근거를 이어 쓰기 어렵다. `ToolSupport.modelArgument`도 최신 문장 또는 등록 모델에만 조회를 허용한다. 이 제한은 보안상의 이유가 있으므로 상담 모델을 서버 상태에 검증해서 보존하는 방식으로 보완해야 한다.

## 수리비 v3는 연결됐지만 명세의 계산 전체를 대체하지 않는다

현재 `parts.json`은 최종 v3 CSV에서 국내 적용 제외 D02를 뺀 32개 그룹을 사용한다. 중앙값·80% 하한/상한·출장비 가산은 원본과 일치한다. 기본 출장비 28,000원은 이미 총액에 포함되며 야간/휴일 +5,000원, 여름 평일 +5,000원, 여름 야간/휴일 +10,000원을 범위 전체에 더하는 구현이다.

예를 들어 W01은 평일 중앙 120,120원, 범위 88,373~163,272원이다. 7월 야간/휴일이면 +10,000원을 적용해 중앙 130,120원, 범위 98,373~173,272원이 된다. 28,000원을 다시 더하면 중복 계산이다.

v3는 국내 총액으로 보정하므로 부품비 중앙+기술료 중앙+출장비가 v3 중앙 총액과 같지 않을 수 있다. W01의 구성 합계는 126,743원이지만 보정 총액은 120,120원이다. 최종 CSV에는 구성별 상하한도 없어서 총액의 80% 범위를 각 항목의 범위처럼 나눠 표시하면 근거가 없다.

따라서 명세의 구성 카드·핵심부품 무상 조건을 구현하려면 구성 항목의 별도 근거와 계산 계약이 필요하다. 총액에서 부품비 중앙을 임의로 빼서 보증 적용 비용을 확정하지 않는다. 현재 구현의 “참고 범위와 조건 문장” 정책을 유지한다면 기능명세의 T1/T5와 비용 카드 요구를 변경한 것으로 명시해야 한다.

패키지 재계산도 현재 그대로 실행 가능하다고 볼 수 없다. `normalize.py`와 모델 파일들은 `/home/claude/...` 절대 경로와 국내 후기·중간 CSV에 의존하며, 확인한 패키지 폴더에는 전체 재계산 입력이 없다. 현 MCP는 이 Python 모델을 실행하지 않고 이미 생성된 CSV의 변환 결과를 읽으므로 참고 범위 조회에는 문제가 없다.

## 원자료끼리 먼저 정리해야 할 차이

1. 기능명세·시퀀스 v1.7은 AI 해석 8초와 확인 질문 최대 2개, 하나라도 예/모름이면 위험 확정이다. DB v0.4는 AI 해석 12초와 확인 질문 최대 3개·점수 합산으로 변경했다. D1~D4는 일부 결정 필요로 표시되어 있으므로 어느 쪽이 최종 기준인지 확정해야 한다.
2. 현재 MCP RPC 5초·도구 호출 전 확인하는 세션 deadline 20초·OpenAI 설정 30초는 위의 MessageInterpreter/PartEstimator 제한과 다르다. 20초 설정은 진행 중인 전체 LLM 흐름을 강제로 끝내는 요청 deadline이 아니다.
3. 기능명세는 조건부 무상 구성·금액을 요구한다. B30은 개인별 무상·공임/출장비 면제·정확한 총액을 판정한 자료가 아니라고 명시한다. 조건이 확인되지 않은 항목은 미확인으로 남겨야 하며 B30만으로 0원을 확정할 수 없다.
4. DB v0.4의 `part_no`는 실물 부품 번호 관계를 전제로 한다. 비용 v3의 W01/DW01 등은 추정 그룹 코드다. 그룹 코드를 국내 SKU나 실제 호환 확정 번호로 취급하지 않도록 별도 식별자가 필요하다.

## 완료를 위한 권장 순서

1. 위험 질문 방식·AI 제한 시간·비용 카드 정책·DB 유지 방식의 최종 계약을 정한다. 기존 SQL을 현재 DB에 그대로 적용하지 않는다. 현재 구현을 기준으로 설계 변경표를 만들거나 필요한 migration과 repository를 작성한다.
2. Spring에 명세 상태 전이와 구조화된 메시지 해석을 구현한다. 품목 충돌·제품 확인·미분류·위험 복귀·끝 상태를 처리하고 현재의 안전 선행 검사도 유지할 수 있도록 정책 차이를 명시한다.
3. 세탁기 OE 한 경로부터 실제 승인 계획·증상/오류 매핑·독립 검증용 후기를 채워 단계 실패→보증→비용→센터가 자동으로 이어지게 한다. 빈 자료를 추측으로 채우지 않는다.
4. 구성 비용/조건부 보증 분기, 제품 수정, 지역 변경, `clientMessageId` 멱등성, 이용 이벤트와 카드 순서를 보완한다.
5. 실제 OpenAI로 T1~T19와 별도 평가 문장을 실행한다. API 성공률·모의 도구 선택·v3의 기존 20건 오차를 실제 Agent 성공률로 집계하지 않는다. MySQL V001~V005와 `ddl-auto=validate`도 개발용 DB에서 별도로 검증한다.

현재 어댑터는 유지할 수 있다. 이 단계들을 완료하기 전의 적절한 상태 표기는 **“MCP 연동 및 참고 정보 조회 검증 완료, 기능명세 전체 MVP 미완료”**다.
