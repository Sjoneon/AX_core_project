# 상담 답변 품질 개선 백로그 — 참고 수리비·확인 사항·말풍선 정리

작성일: 2026-10-15 (Asia/Seoul). 데이터 버전: `B30-COST-V3-20261014-R5`. 상태: A 미착수. B는 관찰 항목만, C는 보증·센터·요약 축소까지 반영(2026-10-15, [점검 문서 8절](manufacturer_data_usage_audit.md#8-4절-해결-반영)).

## 배경

등록 모델 S836MEE022(냉장고, 2025-06 출시)에서 "냉장고가 시원하지 않아요."로 상담하면 다음 문제가 있었다.

- 답변이 면책 문구 위주라서 고객이 할 수 있는 일이 없다("실행 승인 미확인", "추가 확인 필요" 반복).
- 수리비가 나오지 않는다.
- 보증 설명과 상담 요약이 말풍선과 아래 카드에 두 번씩 나온다.

같은 날 아래 두 버그는 수정했다. 이 문서의 범위가 아니다.

| 버그 | 원인 | 수정 |
|---|---|---|
| 냉각 증상에 "냄새" 자료가 연결됨 | `KnowledgeTextMatcher`의 `COOLING` 정규식이 "냉장고 **안**에서"를 부정으로 해석하고 "정지·저하·되지 않아요"는 놓침 | 정규식 교체, `KnowledgeRetrievalTests`에 회귀 테스트 추가 |
| 링크가 `http참고 자료/...`로 깨짐 | `customerDisplay.js`의 드라이브 문자 정규식이 `https://`의 `s:/`를 경로로 치환 | 앞 글자가 영문이면 제외, 말풍선의 `[라벨](https://…)`을 링크로 렌더링 |

## 원인: 현재 정책과 데이터

### 자가조치 안내가 비는 이유

- [approved-plans.json](../../backend/src/main/resources/homecare/approved-plans.json)의 승인 계획은 **1건**(세탁기 TR15WV5 · UE)뿐이다. 나머지 모델·증상은 `get_diagnostic_plan`이 `NOT_VERIFIED`를 반환한다.
- 제조사 자료에는 조치 설명이 있다. 예를 들어 `B15-MFL71401523-weak_cooling`에는 "보관 온도, 문 닫힘, 식품 배치와 주위 설치 환경을 확인한다."가 있다. 그러나 [AnswerContext.java:63](../../backend/src/main/java/com/project/backend/mcp/agent/AnswerContext.java#L63)에서 `action_summary`를 제외한다. 명령형 문장도 `ACTION` 정규식([AnswerContext.java:18](../../backend/src/main/java/com/project/backend/mcp/agent/AnswerContext.java#L18))으로 걸러진다.
- 조치 설명이 있는 MANUAL 레코드 수는 다음과 같다. 세탁기 364, 건조기 182, 냉장고 125, 식기세척기 201건이며, 이 중 `stop_selfcare_and_contact_service=true`는 각각 27, 20, 8, 28건이다.

### 수리비가 나오지 않는 이유 (조건 3개가 겹침)

1. **질문 조건:** 비용 조회는 대화 의도가 `COST`일 때만 실행된다([RepairFollowupPolicy.java:16](../../backend/src/main/java/com/project/backend/mcp/agent/RepairFollowupPolicy.java#L16)). 증상 문의(`SYMPTOM`)에서는 호출하지 않는다.
2. **연결 근거 조건:** 후보 부품은 세 경우에만 생긴다([RepairCandidatePolicy.java](../../backend/src/main/java/com/project/backend/mcp/tool/RepairCandidatePolicy.java)).
   - 고객이 부품명을 직접 말한 경우
   - 제조사 원인 문장에 부품명이 나온 경우
   - 오류 코드가 `targetCodes`에 매핑된 경우

   현재 32개 그룹 모두 `targetCodes`가 없고, 냉각 계열 B15 레코드는 `causes`가 비어 있다. 그래서 일반 증상은 `NO_DATA`로 끝난다.
3. **사례 수 조건:** 국내 같은 부품 사례가 `MINIMUM_CASES=3`건 이상이어야 한다([EstimateRepairCostTool.java:50](../../backend/src/main/java/com/project/backend/mcp/tool/handler/EstimateRepairCostTool.java#L50)). 이 조건을 통과하는 그룹은 32개 중 5개뿐이다.

| 품목 | 3건 이상 | 3건 미만 |
|---|---|---|
| 세탁기 | W04 메인 PCB(4) | W01 배수펌프(1), W07 댐퍼(1), 나머지 0 |
| 건조기 | D06 메인 PCB(5), D07 드럼 롤러(6) | 나머지 0 |
| 냉장고 | R02 메인 PCB(3), R04 응축기 팬모터(4) | R03 증발기 팬모터(1), 컴프레서 등 0 |
| 식기세척기 | 없음 | DW05 메인 PCB(2), DW01 배수펌프(1), 나머지 0 |

### 말풍선과 카드가 중복되는 이유

[AgentChatService.format()](../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java#L282)이 보증 설명(`warranty.explain`), 센터 안내, 상담 요약 전문(`summary_text`)을 고정 문단으로 만든다. 이 문단이 `TOOL_GUIDANCE` 필수 fact로 말풍선 끝에 들어간다. 프론트엔드는 같은 결과를 `WarrantyResult`·`ToolResults` 카드로 다시 그린다.

## C. 말풍선 정리 (권장 1순위)

**목표:** 카드로 표시되는 결과는 말풍선에서 한 줄 안내로 줄인다.

- `format()`의 `check_warranty`, `find_service_centers`, `prepare_service_handoff` 문단을 짧은 안내로 바꾼다. 예: "보증 내용은 아래 보증 카드에서 확인해 주세요."
- 카드가 없는 경로(MCP 장애 `unavailable`, 안전 안내 `safety`)의 문구는 그대로 둔다.
- 이미 저장된 과거 답변은 다시 쓰지 않는다. 새 답변부터 적용한다.

**확인할 것**

- `GroundedAnswerGenerator`의 `TOOL_GUIDANCE` 필수 규칙은 유지된다. 문구가 짧아질 뿐이다.
- `answer_evaluation.json`의 `expected_text`와 `consultationRendering.test.mjs`의 문구 기대값을 갱신해야 한다.

## B. 제조사 확인 사항을 참고로 노출 (권장 2순위)

**목표:** 승인된 계획이 없어도 "제조사 설명서에서 먼저 확인하라고 안내하는 항목"을 참고 정보로 보여 준다. 실행 단계나 승인으로 표시하지 않는다.

**표시 규칙(안)**

- 출처는 검색으로 선택된 MANUAL 레코드의 `actionDescriptions`와 `branches.action_branches[].action_summary`(`feature_gate`가 없는 것만)로 제한한다.
- 고정 머리말: "제조사 설명서의 확인 사항(참고)이며 이 제품에서 실행이 승인된 절차는 아닙니다."
- `stop_selfcare_and_contact_service=true` 레코드는 확인 사항 대신 "서비스 점검이 필요한 증상"으로 표시한다.
- 위험 상태(`SUSPECT`/`CONFIRMED`)와 안전 문서가 있으면 지금처럼 노출하지 않는다.
- 분해·부품 교체·전기 작업 문구는 계속 제외한다. 현재 `ACTION` 정규식 중 "분해|교체|제거하|설치하"는 유지하고, "확인한다/청소한다" 같은 확인형 문장만 허용한다.
- 레코드당 최대 3개, 전체 최대 5개로 제한한다.

**구현 위치**

- `AnswerContext.Builder.build()`에 `kind="REFERENCE_CHECK"` fact를 추가한다(required=false).
- `GroundedAnswerGenerator.SYSTEM`의 kind 설명을 갱신하고, 검증 로직이 새 kind를 허용하도록 한다.
- 프론트엔드 근거 카드(`KnowledgeSources`)에 같은 항목이 이미 보이는지 확인해 중복을 피한다.

**충돌하는 기존 원칙:** 아래 두 문서의 규칙과 충돌하므로 함께 고친다.
- [grounded_answer_generation.md:48](grounded_answer_generation.md): "`action_descriptions`, `action_summary`와 기능 확인이 필요한 분기 주장은 제외한다."
- [consultation_display.md:30](consultation_display.md): 원문 조치·`action_summary`를 실행 단계로 복원하지 않는다는 규칙. 이 규칙은 유지하고, 참고 표시는 실행 단계·버튼과 분리한다.

## A. 증상 기반 참고 수리비 (별도 데이터 작업 필요)

**목표:** 증상이 확인되면(또는 비용을 물으면) 관련 부품 그룹의 참고 범위를 보여 준다. 고장 진단이 아님을 명시한다.

**필요한 데이터:** 증상 코드와 부품 그룹을 연결하는 새 매핑 파일(예: `homecare/symptom-parts.json`)이 필요하다. 각 연결에는 근거(제조사 원인 문장, 서비스 사례, 검토자·검토일)가 있어야 한다. 근거 없는 연결은 넣지 않는다.

예시 형식(아래 내용은 **근거 검토 전 예시**이며 확정이 아니다):

```json
{ "category": "REFRIGERATOR", "symptom_code": "COOLING",
  "part_ids": ["R04", "R03", "R01"],
  "evidence": [{ "type": "SERVICE_REVIEW", "note": "검토 필요", "reviewed_at": null }] }
```

**결정이 필요한 정책**

| 항목 | 현재 | 선택지 |
|---|---|---|
| 노출 시점 | 비용 질문(`COST`)일 때만 | 증상 확인 시 자동 / 비용 질문일 때만 유지 |
| 사례 수 기준 | 3건 미만이면 금액 숨김 | 기준 유지 / 3건 미만도 "신뢰도 낮음"으로 표시 / 기준 2건으로 완화 |
| 표시 개수 | 대안 최대 2개, 합산 금지 | 유지 권장 |
| 위험 상태 | 가격 조회 차단 | 유지 |

**구현 위치**

- `ToolDataCatalog`가 새 파일을 읽게 하고, `build_homecare_data.py`의 `VERIFIED_FILES`와 매니페스트 해시에 추가한다.
- `RepairCandidatePolicy.select()`에 `basis="SYMPTOM_MAPPING"` 후보를 추가한다. `USER_PART_REQUEST`와 구분해 표시한다.
- 자동 노출을 택하면 `RepairFollowupPolicy.complete()`에서 `SYMPTOM` 의도에도 `get_repair_candidates`와 `estimate_repair_cost`를 호출한다. 현재 호출 상한(최대 5회, 도구당 2회, 20초)을 넘지 않는지 확인해야 한다. 지금도 증상 상담 한 번에 5회를 다 쓰고 있다.
- 금액 문구는 `cost()` 출력을 재사용한다. "견적 아님·신뢰도"는 그대로 유지한다.

**충돌하는 기존 원칙:** [repair_followup.md](repair_followup.md)의 다음 두 문장을 개정해야 한다. "일반 증상만으로 특정 부품의 고장을 확정하지 않는다", "비용을 묻지 않은 실패 응답에서 금액을 자동 생성하지 않는다".

## 권장 순서

1. **C:** 코드 변경만으로 바로 체감된다. 정책 충돌이 없다.
2. **B:** 답변에 실제 내용이 생긴다. 기존 문서 두 개를 개정해야 한다.
3. **A:** 매핑 데이터를 만들고 검토하는 일이 선행되어야 한다. 호출 예산도 재설계해야 한다.

## 미결정 사항

- A의 노출 시점과 사례 수 기준(위 표).
- B에서 "확인한다"류 문장을 고객용 존댓말("확인해 주세요")로 바꿔 보여 줄지, 원문을 그대로 인용할지.
- C 적용 후 말풍선에 남길 최소 문구(보증 상태 한 줄 요약을 남길지).
