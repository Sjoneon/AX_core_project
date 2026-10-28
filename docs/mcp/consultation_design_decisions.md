# 상담 설계 결정 기록

<!-- 개발자 직접 검토·수정 필요: "이유" 칸의 초안 문장과 [개발자 작성] 빈칸은 개발자가 확정한다. -->

작성 기준: 2026-10-15~16 상담 흐름 개편 때 정한 내용을 옮겼다. "반영 위치"는 커밋 `0dbb709`의 코드·테스트에서 찾았다. "이유"는 저장소 문서에 근거가 있을 때만 출처와 함께 초안으로 적었고, 근거가 없으면 `[개발자 작성]`으로 남겼다. 구현 내용은 [상담 흐름·카드 개편 구현 기록](consultation_card_flow.md)에, 남은 작업은 [남은 작업](remaining_tasks.md#2-상담-흐름카드-개편-2026-10-16-기준)에 있다.

## 1. 목표 상담 흐름

개발자가 제시한 흐름도([consultation_flow_target.png](consultation_flow_target.png))를 기준으로 한다. 응답마다 카드 하나만 쓰고, 한눈에 보이게 배치한다.

```text
품목 버튼 + 질문 ─► AI 해석·위험 판단 ─► 위험 신호?
  ├─ 예 ─► 위험 확인 질문(예/아니요 1~2개, 모르겠어요=예, 긴급이면 건너뜀)
  │         ├─ 위험 확정 ─► 안전 경고(즉시: 사용 중지·사례별 문구·[상담원 연결]) ─► 경고 후 계속 ─┐
  │         └─ 아니요 ─► 위험 해제 ──────────────────────────────────────────────────┤
  └─ 아니오 ────────────────────────────────────────────────────────────────────────┤
                                                                                     ▼
제품 확정·보증 판정 ('○○가 맞나요?' 예/아니요, 구매일 기준 판정 — 표시는 2차 카드)
  ─► 자가검진 대상?
       ├─ 예 ─► 1차 카드(제품 정보 · 자가검진 1단계부터, 매 단계 '해결됐나요?' 예/아니요)
       │          ├─ 해결 ─► 해결·종료
       │          ├─ 안 되면 다음 단계 ─► 1차 카드
       │          └─ 마지막 단계도 안 됨 ─► 2차 카드
       └─ 아니오(위험 확정 · 자가조치 없음) ─► 2차 카드
2차 카드(보증 판정 · 예상 수리비 · 근거, 위험이면 맨 위에 안전 경고) ─► A/S 연결(배송지 기준 가까운 센터 · 출장 수리 접수 · 상담원 연결)
```

- 위험 확인 질문 중에도 [상담원 연결]을 함께 보여 준다. 확인하는 동안 제품 사용을 멈추라는 문구를 먼저 띄운다.

## 2. 결정 / 이유 / 반영 위치

| 항목 | 결정 | 이유 | 반영 위치 (코드·테스트) |
|---|---|---|---|
| 카드 | 응답마다 카드 하나. 자가검진·보증·A/S·요약·근거 카드를 여러 개 쌓지 않는다. | 초안: 개편 전에는 보증 설명과 상담 요약이 말풍선과 아래 카드에 두 번씩 나왔다([answer_quality_backlog.md](answer_quality_backlog.md) "배경"). 답변이 난잡하다는 지적도 있었다([구현 기록 1.3](consultation_card_flow.md#13-자가검진-단계와-요약-카드-2026-10-15)). | 코드: [AgentChatService.java](../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java)(`tools.card`의 `PRODUCT_CHOICE`·`SELF_CHECK`·`SUMMARY`), [ConsultationMessage.jsx](../../frontend/src/components/ConsultationMessage.jsx), [ConsultationCard.jsx](../../frontend/src/components/ConsultationCard.jsx). 테스트: [consultationRendering.test.mjs](../../frontend/src/consultationRendering.test.mjs) "summary response shows one card with warranty, cost and A/S instead of separate tool cards", [McpIntegrationTests](../../backend/src/test/java/com/project/backend/mcp/McpIntegrationTests.java) `registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard` |
| 불필요한 문구 | [answer_rework_requirements.md](answer_rework_requirements.md) 3절 목록을 삭제한다. "이 설명은 … 승인이 아닙니다", 연결 문구, 설명서 링크, "검토된 절차 없음" 카드 등이 대상이다. | 초안: 요구사항 문서 1절의 "고객이 알 필요 없는 문구와 카드는 표시하지 않는다". 개편 전 답변은 면책 문구 위주라 고객이 할 수 있는 일이 없었다([answer_quality_backlog.md](answer_quality_backlog.md) "배경"). | 등록 제품 안내 흐름(`AgentChatService.guided`)에 적용. 테스트: `McpIntegrationTests.registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard`(답변에 "제조사 자료"·"승인" 문구가 없음을 확인). 참고: [AnswerContext.java](../../backend/src/main/java/com/project/backend/mcp/agent/AnswerContext.java)에는 "이 설명은 고장 확정이나 직접 실행할 조치의 승인이 아닙니다." 문구가 남아 있다. 안내 흐름 밖의 경로로 보이며 [개발자 확인] |
| 자가 점검 | 설명서 확인 항목(예: 정전 여부와 전원 플러그 연결 / 보관 온도 / 문 닫힘 / 식품 배치 / 주위 설치 환경)은 자가 조치다. 단계별로 안내하고 설명서 링크는 주지 않는다. 자가 점검이 없으면 텍스트로 "점검 방법이 없다"고 말하고 수리비로 넘어간다. | 초안: 설명서 확인 항목이 구조화 자료(`actionDescriptions`)에 있는데도 최종 답변에서 빠졌다([manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 4.1절). 설명서 링크를 주지 않는 이유: [개발자 작성] | 코드: [ManualCheckItems.java](../../backend/src/main/java/com/project/backend/mcp/agent/ManualCheckItems.java), `AgentChatService.checkSteps`, "이 증상은 직접 해 볼 수 있는 점검 방법이 없어요" 문구(`AgentChatService`). 테스트: [ManualCheckItemsTests](../../backend/src/test/java/com/project/backend/mcp/agent/ManualCheckItemsTests.java), `McpIntegrationTests.manualConditionsBecomeSelfCheckStepsAndOneOutcomeClosesThem`, `McpIntegrationTests.registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard` |
| 수리비 표시 | 후보 부품의 기본 범위를 보여 준다. 상한이 10만 원 이상 차이 나는 비싼 부품(예: 컴프레서, 보증이 안 될 때)은 따로 알리고, 그 부품을 포함한 전체 범위도 보여 준다. 사례 3건 미만 부품도 포함한다. | 초안: 개편 전에는 "비용을 물었을 때만", "증상·부품 연결 근거", "국내 같은 부품 사례 3건 이상"이라는 세 조건이 겹쳐 수리비가 나오지 않았다([answer_quality_backlog.md](answer_quality_backlog.md) "수리비가 나오지 않는 이유", [answer_rework_requirements.md](answer_rework_requirements.md) 4절). 10만 원 기준과 3건 미만 포함의 이유: [개발자 작성] | 코드: [SymptomRepairEstimate.java](../../backend/src/main/java/com/project/backend/mcp/tool/SymptomRepairEstimate.java)(`SEPARATE_GAP=100_000`). 테스트: [SymptomRepairEstimateTests](../../backend/src/test/java/com/project/backend/mcp/tool/SymptomRepairEstimateTests.java) `coolingShowsTheCommonRangeAndTheCompressorSeparately`, `closeRangesStayInOneBaseRangeAndUnknownSymptomsHaveNoAmount` |
| 증상→부품 근거 | 근거 자료를 조사해 증상·오류·관찰 답과 부품을 잇는 근거 표를 구축했다([구현 기록 1.2](consultation_card_flow.md#12-증상별-수리비와-근거-자료-2026-10-15)·1.4·1.5). 수리업체 가이드도 유효한 근거로 쓴다(국내 사례·공식 자료와 구분 표기). | 초안: 개편 전에는 증상과 부품 그룹을 잇는 자료가 없었고, "증상별 후보 부품 그룹을 누가, 어떤 근거로 정할지"가 결정 대상이었다([answer_rework_requirements.md](answer_rework_requirements.md) 4·6절). 수리업체 가이드를 근거로 인정한 이유: [개발자 작성] | 자료: [증상_부품_근거_v1.csv](../../수리비_정규화_과정/증상_부품_근거_v1.csv), [관찰_부품_근거_v1.csv](../../수리비_정규화_과정/관찰_부품_근거_v1.csv), [symptom-parts.json](../../backend/src/main/resources/homecare/symptom-parts.json). 코드: [build_symptom_parts.py](../../backend/scripts/build_symptom_parts.py), [ToolDataCatalog.java](../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java)(`symptomParts`). 테스트: `SymptomRepairEstimateTests.everyLinkKeepsItsEvidenceType`, [DataIntegrityTests](../../backend/src/test/java/com/project/backend/data/DataIntegrityTests.java), `python -I backend/scripts/build_symptom_parts.py --check` |
| 가까운 센터 | LG 공식 센터 상세 페이지로 전국 센터 자료를 직접 구축해, 설치 지역 기준 센터를 보여 준다. 공식 센터 찾기 링크도 함께 둔다. | 초안: 결정 당시 적힌 이유는 "LG 센터 찾기에는 지역을 지정하는 링크가 없다"였다. 기존 `centers.json`은 3곳뿐이라 다른 지역은 결과가 없었다([manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 4.4절). | **미구현.** 현재 [FindServiceCentersTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/FindServiceCentersTool.java)는 `directory_status=NOT_INTEGRATED`와 공식 센터 찾기 연결만 반환한다. 테스트: [ToolBackendTests](../../backend/src/test/java/com/project/backend/mcp/tool/ToolBackendTests.java) `serviceConnectionReportsTheStoredRegionAndNeverListsSeedCenters`. 설계: [구현 기록 2.1](consultation_card_flow.md#21-전국-센터-자료) |
| 링크 관리 파일 | 수집 링크를 관리하는 `collected_sources.json`(저장소 최상위)은 GitHub에 올리지 않는다. | [개발자 작성] | [.gitignore](../../.gitignore)의 `collected_sources.json`, [source_registry.py](../../backend/scripts/source_registry.py) |
| 위험 확정 후 수리비 (2026-10-16) | 위험이 확정돼도 자가조치만 빼고 수리비는 알려 준다. 보증 카드의 부품비·기술료·출장비는 금액을 보여 줄 수 없으면 빼고 수리비를 보여 준다. | 초안(비용 칸 제거): 실제 데이터에서 출장비는 항상, 나머지도 대부분 "추가 확인 필요"였다([구현 기록 1.6](consultation_card_flow.md#16-위험-확정-후-요약-카드-보증-카드의-비용-칸-제거-2026-10-16)). 위험 확정 상담에도 수리비를 보여 주는 이유: [개발자 작성] | 코드: `AgentChatService.safety()`, `SymptomRepairEstimate.orCategory`, [WarrantyResult.jsx](../../frontend/src/components/WarrantyResult.jsx), `WarrantyService.summarize`. 테스트: `McpIntegrationTests.yesAndUnsureConfirmRiskAndConfirmedRiskCannotBeDismissedByAi`, `SymptomRepairEstimateTests.withoutAPartLinkTheWholeCategoryRangeIsLabelledAsSuch`, [WarrantyServiceTests](../../backend/src/test/java/com/project/backend/warranty/WarrantyServiceTests.java) `chatSummaryStatesPeriodsAndTheOfficialPolicyWithoutFeeStatuses`, `consultationRendering.test.mjs` "a confirmed risk shows the category range in the summary card instead of the warranty fee card" |

### 2.1 다른 문서에 기록된 결정

개편 인계 문서 밖에서 "사용자 요구·요청·범위 확정"으로 기록된 결정이다. 이 표에 넣을지는 개발자가 판단한다. [개발자 확인]

| 항목 | 결정 | 이유 | 반영 위치 (코드·테스트) |
|---|---|---|---|
| 구매일 기준 보증 | 등록 구매일을 보증 시작일로 계산한다. 구입 증빙·국내 가정용 확인이 비어 있어도 제품 전체 보증의 만료일과 기간 상태를 제공하고, 실제 무상 적용·비용 조건은 따로 표시한다. | 초안: 개편 전에는 구매일이 있어도 증빙·가정용 조건이 미확인이면 만료일 계산이 막혀, 등록 구매일은 보이는데 보증 카드는 모두 미확인으로 나왔다([manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 4.3절). | 코드: [WarrantyService.java](../../backend/src/main/java/com/project/backend/warranty/WarrantyService.java), [CheckWarrantyTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/CheckWarrantyTool.java), [WarrantyResult.jsx](../../frontend/src/components/WarrantyResult.jsx). 테스트: `WarrantyServiceTests.purchaseDateStartsWarrantyEvenWhenDeliveryDiffers`, `WarrantyServiceTests.registeredRefrigeratorPurchaseCalculatesExpiryWithoutProofOrHouseholdFlags`, `McpIntegrationTests.registeredRefrigeratorPurchaseReachesMcpChatAndSavedHistory`. 기록: [warranty_implementation.md](warranty_implementation.md), [purchase_date_warranty_verification.md](purchase_date_warranty_verification.md) |
| 답변 적합성 기준 분리 | 분류·도구 호출의 `OK`만으로 상담 성공을 판정하지 않고, 실제 질문과 최종 문장을 대조하는 답변 적합성 기준을 따로 둔다. | 초안: 개발 세트 `final` 실행은 제어 검사 24개를 모두 통과했지만, 답변 검토에서 무관한 비용 부속 설명과 부정 문장의 재질문이 남았다([consultation_validation.md](consultation_validation.md) "결과 읽는 방법"). | 기준: [consultation_answer_rubric.json](consultation_answer_rubric.json), [consultation_evaluation.json](consultation_evaluation.json). 코드: [ConsultationEvaluationTests.java](../../backend/src/test/java/com/project/backend/mcp/ConsultationEvaluationTests.java)(`real-agent` 태그), [evaluate_consultation_answers.py](../../backend/scripts/evaluate_consultation_answers.py) |
| 지원 범위 4품목 | 세탁기·건조기·냉장고(김치 포함)·식기세척기로 코드·계약·자료·문서를 통일한다. 에어컨은 새 상담과 등록에서 제외한다. | [개발자 작성] (기록: [repair_development_guide.md](../guides/repair_development_guide.md) 서두 "사용자의 범위 확정에 따라") | [ProductCategory.java](../../backend/src/main/java/com/project/backend/conversation/ProductCategory.java), [V004__four_category_scope.sql](../../ops/rds/sql/V004__four_category_scope.sql) |

## 3. 검수에서 찾은 문제

실제 상담 출력과 화면을 확인하며 찾은 문제와 조치를 다른 기록에서 모았다. "발견 경위"는 기록에 "사용자가 지적·제시"로 적힌 경우만 채웠다(이 기록들의 "사용자"는 개발 지시를 한 개발자다). 나머지는 `[개발자 작성]`이다.

| 문제 | 확인한 원인·내용 | 조치 | 발견 경위 | 기록 |
|---|---|---|---|---|
| 등록 제품 상담에서 선택 전에 "검토를 마친 절차가 없어요"가 먼저 나옴, 설명서 내용이 자가검진으로 안내되지 않음, 답변이 난잡함 | 승인 계획이 없는 제품은 `NOT_VERIFIED` 안내와 여러 카드가 함께 나옴 | 등록 제품 증상 상담을 LLM 없이 정해진 순서(제품 선택 → 질문 → 자가검진 단계 → 요약 카드)로 진행 | 개발자 지적 | [구현 기록 1.3](consultation_card_flow.md#13-자가검진-단계와-요약-카드-2026-10-15) |
| S836MEE022 "냉장고가 시원하지 않아요." 답변이 대부분 "확인 필요" | 설명 필드 전달 누락, 실행 승인 없음이 보증·센터·요약 전체를 불러옴, 보증 계산 조건 결합, 주소-지역 미연결 | 관찰 항목 설명, 자동 확장 축소, 구매일 기준 보증, 주소 검색 지역 저장 | 개발자 제시 사례 | [manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 4절·8절 |
| 무관한 근거: 냉각 질문에 냄새 자료가 연결됨 | [KnowledgeTextMatcher.java](../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeTextMatcher.java)의 `COOLING` 정규식이 "냉장고 안에서"를 부정으로 해석하고 "정지·저하"를 놓침 | 정규식 교체, [KnowledgeRetrievalTests](../../backend/src/test/java/com/project/backend/mcp/retrieval/KnowledgeRetrievalTests.java) `coolingComplaintsSelectCoolingFactsNotInteriorFacts` | [개발자 작성] | [answer_quality_backlog.md](answer_quality_backlog.md) "배경"의 버그 표, [manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 3절 |
| 무관한 근거: 세탁기 진동 질문에 침수·급수 문서가 선택됨 | "세탁기"처럼 넓은 단어만 일치한 문서가 상위에 나옴 | 모델·품목·제외 필터와 증상 어휘·임베딩 검색으로 보완 | [개발자 작성] | [mcp_completion_plan.md](mcp_completion_plan.md) "확인된 재현 사례"·2단계 |
| 무관한 근거: 비용 문의에 무관한 모터별 보증과 금액 없는 '위 금액' 고지 | 실제 상담 평가의 답변 대조에서 발견 | 비용 근거·부족 사유·전체 보증 요지만 제공 | 실제 상담 평가 | [consultation_validation.md](consultation_validation.md) "발견한 문제와 수정" (표 전체 10건) |
| JSON 노출: 고객 화면에 원본 JSON·해시·내부 QA 이력·파일 경로가 나올 수 있음 | 과거 B30 자료의 `content/text`가 JSON 래퍼였음 | 표시 시점에 고객용 필드만 투영하고 원본은 DEV 영역에서만 표시([customerDisplay.js](../../frontend/src/customerDisplay.js)). 테스트: `consultationRendering.test.mjs` "rendered response shows one structured document and keeps file paths, raw JSON and internal evidence out" | [개발자 작성] | [consultation_display.md](consultation_display.md), [mcp_completion_plan.md](mcp_completion_plan.md) 7단계 |
| 링크가 `http참고 자료/...`로 깨짐 | `customerDisplay.js`의 드라이브 문자 정규식이 `https://`의 `s:/`를 경로로 치환 | 앞 글자가 영문이면 제외, 말풍선의 링크를 앵커로 렌더링 | [개발자 작성] | [answer_quality_backlog.md](answer_quality_backlog.md) 버그 표 |
| 제품 선택: 냉장고 2대 계정에서 묻지 않고 첫 번째 냉장고가 선택됨 | 닉네임 "냉장고"가 문장에 들어 있다는 이유로 자동 선택 | 품목 이름과 같은 닉네임으로는 자동 선택하지 않고 `PRODUCT_CHOICE` 카드로 묻기(`ConsultationService.automaticProduct`). 테스트: `McpIntegrationTests.withTwoRegisteredRefrigeratorsTheCustomerPicksOneBeforeTheQuestionIsAnswered` | [개발자 작성] | [구현 기록 1.3](consultation_card_flow.md#13-자가검진-단계와-요약-카드-2026-10-15) 0번 |
| 제품 선택 반복 | [개발자 작성] — 저장소 문서에서 이 문제를 직접 적은 기록을 찾지 못했다. | [개발자 작성] | [개발자 작성] | — |
| 센터 하드코딩: 센터 3곳을 전국 조회처럼 사용 | `centers.json`이 대치·분당·북수원 3곳뿐이라 제주 등 다른 지역은 결과가 없음. 등록 주소가 센터 검색용 지역으로 연결되지 않음 | `find_service_centers`가 센터 목록 대신 공식 출장 접수·센터 찾기·전화를 반환(`directory_status=NOT_INTEGRATED`). 주소 검색 결과의 시·도/시·군·구 저장. 전국 센터 자료는 미구현 | [개발자 작성] | [manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 4.4절·8절, [service_channel_operating_plan.md](service_channel_operating_plan.md) |
