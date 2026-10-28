# 상담 설계 결정 기록

> 상담 흐름 개편(2026-10-15~16)의 설계 결정, 이유, 구현 위치 기록
> 기준 커밋: `0dbb709` · 범위: 등록 제품 상담의 카드, 자가 점검, 수리비, 보증, A/S 연결

**정리 기준**

- 반영 위치: 커밋 `0dbb709`의 코드와 테스트에서 확인
- 이유: 개발 기록에 근거가 있는 내용만 기재
- 발견 시각: 개발 대화 기록 기준(KST)

**목차**

1. [요약](#1-요약)
2. [목표 상담 흐름](#2-목표-상담-흐름)
3. [설계 결정](#3-설계-결정)
4. [검수에서 찾은 문제](#4-검수에서-찾은-문제)
5. [관련 문서](#5-관련-문서)

---

## 1. 요약

| # | 결정 | 상태 |
| --- | --- | --- |
| [3.1](#31-응답마다-카드-하나) | 응답마다 카드 하나 | 구현 |
| [3.2](#32-고객이-알-필요-없는-문구-삭제) | 고객이 알 필요 없는 문구 삭제 | 구현 |
| [3.3](#33-설명서-확인-항목을-자가-점검으로-안내) | 설명서 확인 항목을 자가 점검으로 안내 | 구현 |
| [3.4](#34-후보-부품-기준-수리비-범위-표시) | 후보 부품 기준 수리비 범위 표시 | 구현 |
| [3.5](#35-증상부품-근거-표-구축) | 증상·부품 근거 표 구축 | 구현 |
| [3.6](#36-전국-센터-자료로-가까운-센터-안내) | 전국 센터 자료로 가까운 센터 안내 | **미구현** |
| [3.7](#37-수집-링크-관리-파일은-github에서-제외) | 수집 링크 관리 파일은 GitHub에서 제외 | 구현 |
| [3.8](#38-위험-확정-후에도-수리비-안내) | 위험 확정 후에도 수리비 안내 | 구현 |
| [3.9](#39-구매일-기준-보증-계산) | 구매일 기준 보증 계산 | 구현 |
| [3.10](#310-답변-적합성-기준-분리) | 답변 적합성 기준 분리 | 구현 |
| [3.11](#311-지원-범위-4품목) | 지원 범위 4품목 | 구현 |

---

## 2. 목표 상담 흐름

- 기준: [목표 흐름도][flow-png]
- 원칙: 응답마다 카드 하나, 한눈에 보이는 배치

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

- 위험 확인 질문 중에도 [상담원 연결] 버튼을 함께 표시
- 확인하는 동안 제품 사용을 멈추라는 문구를 먼저 표시

---

## 3. 설계 결정

- 구성: 결정 → 이유 → 반영 위치
- 3.1~3.8: 상담 흐름 개편에서 정한 결정
- 3.9~3.11: 개편 밖의 문서에 요구·범위 결정으로 기록된 항목

### 3.1 응답마다 카드 하나

**결정**

- 응답마다 카드 하나만 사용
- 자가검진·보증·A/S·요약·근거 카드를 여러 개 쌓지 않음

**이유**

- 개편 전: 보증 설명과 상담 요약이 말풍선과 아래 카드에 중복 표시
- 검수 지적: 답변 구성이 난잡함

**반영 위치**

- 코드
    - [AgentChatService.java][agent-chat]: `tools.card`의 `PRODUCT_CHOICE`·`SELF_CHECK`·`SUMMARY`
    - [ConsultationMessage.jsx][consultation-message], [ConsultationCard.jsx][consultation-card]
- 테스트
    - [consultationRendering.test.mjs][rendering-test]: "summary response shows one card with warranty, cost and A/S instead of separate tool cards"
    - [McpIntegrationTests][mcp-it]: `registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard`

### 3.2 고객이 알 필요 없는 문구 삭제

**결정**

- [답변 개편 요구사항][rework-req] 3절 목록의 문구와 카드 삭제
- 대상: "이 설명은 … 승인이 아닙니다", 연결 문구, 설명서 링크, "검토된 절차 없음" 카드 등

**이유**

- 요구사항 1절 기준: "고객이 알 필요 없는 문구와 카드는 표시하지 않음"
- 개편 전 답변: 면책 문구 위주, 고객이 할 수 있는 행동 없음

**반영 위치**

- 코드: 등록 제품 안내 흐름(`AgentChatService.guided`)
- 테스트: `McpIntegrationTests.registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard`
    - 답변에 "제조사 자료"·"승인" 문구가 없는지 확인
- 남은 문구
    - [AnswerContext.java][answer-context]의 "이 설명은 고장 확정이나 직접 실행할 조치의 승인이 아닙니다."
    - 안내 흐름을 타지 않는 상담의 근거 답변 경로(`AnswerContext` → `GroundedAnswerGenerator`)에서 사용

### 3.3 설명서 확인 항목을 자가 점검으로 안내

**결정**

- 설명서 확인 항목을 자가 조치로 분류
    - 예: 정전 여부와 전원 플러그 연결, 보관 온도, 문 닫힘, 식품 배치, 주위 설치 환경
- 단계별로 안내하고 설명서 링크는 제공하지 않음
- 자가 점검이 없으면 "점검 방법이 없다"고 텍스트로 알린 뒤 수리비 안내로 이동

**이유**

- 구조화 자료(`actionDescriptions`)에 있는 설명서 확인 항목이 최종 답변에서 누락
- 참고: [제조사 자료 사용 점검][data-audit] 4.1절

**반영 위치**

- 코드
    - [ManualCheckItems.java][manual-check-items], `AgentChatService.checkSteps`
    - "이 증상은 직접 해 볼 수 있는 점검 방법이 없어요" 문구(`AgentChatService`)
- 테스트
    - [ManualCheckItemsTests][manual-check-items-test]
    - `McpIntegrationTests.manualConditionsBecomeSelfCheckStepsAndOneOutcomeClosesThem`
    - `McpIntegrationTests.registeredSymptomWithoutManualStepsGoesStraightToOneSummaryCard`

### 3.4 후보 부품 기준 수리비 범위 표시

**결정**

- 후보 부품의 기본 범위 표시
- 상한이 10만 원 이상 차이 나는 비싼 부품은 따로 알리고, 그 부품을 포함한 전체 범위도 표시
    - 예: 보증이 안 될 때의 컴프레서
- 사례가 3건 미만인 부품도 포함

**이유**

- 개편 전: 아래 세 조건이 겹쳐 수리비 미표시
    - "비용을 물었을 때만"
    - "증상·부품 연결 근거"
    - "국내 같은 부품 사례 3건 이상"
- 참고: [답변 개편 요구사항][rework-req] 4절

**반영 위치**

- 코드: [SymptomRepairEstimate.java][symptom-estimate] (`SEPARATE_GAP=100_000`)
- 테스트: [SymptomRepairEstimateTests][symptom-estimate-test]
    - `coolingShowsTheCommonRangeAndTheCompressorSeparately`
    - `closeRangesStayInOneBaseRangeAndUnknownSymptomsHaveNoAmount`

### 3.5 증상·부품 근거 표 구축

**결정**

- 근거 자료를 조사해 증상·오류·관찰 답과 부품을 잇는 근거 표 구축
- 수리업체 가이드도 유효한 근거로 사용하되, 국내 사례·공식 자료와 구분해 표기

**이유**

- 개편 전: 증상과 부품 그룹을 잇는 자료 없음
- 미결정 항목: "증상별 후보 부품 그룹을 누가, 어떤 근거로 정할지"
- 참고: [답변 개편 요구사항][rework-req] 4·6절

**반영 위치**

- 자료: [증상_부품_근거_v1.csv][symptom-parts-csv], [관찰_부품_근거_v1.csv][observation-parts-csv], [symptom-parts.json][symptom-parts-json]
- 코드: [build_symptom_parts.py][build-symptom-parts], [ToolDataCatalog.java][tool-data-catalog] (`symptomParts`)
- 테스트
    - `SymptomRepairEstimateTests.everyLinkKeepsItsEvidenceType`
    - [DataIntegrityTests][data-integrity-test]
    - `python -I backend/scripts/build_symptom_parts.py --check`

### 3.6 전국 센터 자료로 가까운 센터 안내

> 상태: **미구현** (현재는 공식 센터 찾기 연결만 반환)

**결정**

- LG 공식 센터 상세 페이지로 전국 센터 자료를 직접 구축
- 설치 지역 기준으로 가까운 센터를 표시하고, 공식 센터 찾기 링크도 함께 제공

**이유**

- 결정 당시 기록: "LG 센터 찾기에는 지역을 지정하는 링크가 없다"
- 기존 `centers.json`: 3곳뿐이라 다른 지역은 결과 없음
- 참고: [제조사 자료 사용 점검][data-audit] 4.4절

**반영 위치**

- 코드: [FindServiceCentersTool.java][find-centers-tool]
    - `directory_status=NOT_INTEGRATED`와 공식 센터 찾기 연결만 반환
- 테스트: [ToolBackendTests][tool-backend-test] `serviceConnectionReportsTheStoredRegionAndNeverListsSeedCenters`

### 3.7 수집 링크 관리 파일은 GitHub에서 제외

**결정**

- 수집 링크를 관리하는 `collected_sources.json`(저장소 최상위)은 GitHub에 올리지 않음

**반영 위치**

- [.gitignore][gitignore]의 `collected_sources.json`
- [source_registry.py][source-registry]

### 3.8 위험 확정 후에도 수리비 안내

결정일: 2026-10-16

**결정**

- 위험이 확정돼도 자가조치만 빼고 수리비는 안내
- 보증 카드의 부품비·기술료·출장비는 금액을 보여 줄 수 없으면 빼고 수리비를 표시

**이유**

- 실제 데이터의 보증 카드 비용 칸: 출장비는 항상, 나머지도 대부분 "추가 확인 필요"
- 위 이유로 보증 카드의 비용 칸 제거

**반영 위치**

- 코드
    - `AgentChatService.safety()`, `SymptomRepairEstimate.orCategory`
    - [WarrantyResult.jsx][warranty-result], `WarrantyService.summarize`
- 테스트
    - `McpIntegrationTests.yesAndUnsureConfirmRiskAndConfirmedRiskCannotBeDismissedByAi`
    - `SymptomRepairEstimateTests.withoutAPartLinkTheWholeCategoryRangeIsLabelledAsSuch`
    - [WarrantyServiceTests][warranty-service-test] `chatSummaryStatesPeriodsAndTheOfficialPolicyWithoutFeeStatuses`
    - `consultationRendering.test.mjs`: "a confirmed risk shows the category range in the summary card instead of the warranty fee card"

### 3.9 구매일 기준 보증 계산

**결정**

- 등록 구매일을 보증 시작일로 계산
- 구입 증빙·국내 가정용 확인이 비어 있어도 제품 전체 보증의 만료일과 기간 상태 제공
- 실제 무상 적용과 비용 조건은 따로 표시

**이유**

- 개편 전: 증빙·가정용 조건이 미확인이면 구매일이 있어도 만료일 계산 불가
- 결과: 등록 구매일은 보이지만 보증 카드는 모두 미확인으로 표시
- 참고: [제조사 자료 사용 점검][data-audit] 4.3절

**반영 위치**

- 코드: [WarrantyService.java][warranty-service], [CheckWarrantyTool.java][check-warranty-tool], [WarrantyResult.jsx][warranty-result]
- 테스트
    - `WarrantyServiceTests.purchaseDateStartsWarrantyEvenWhenDeliveryDiffers`
    - `WarrantyServiceTests.registeredRefrigeratorPurchaseCalculatesExpiryWithoutProofOrHouseholdFlags`
    - `McpIntegrationTests.registeredRefrigeratorPurchaseReachesMcpChatAndSavedHistory`
- 기록: [보증 구현][warranty-impl], [구매일 기준 보증 검수][purchase-warranty]

### 3.10 답변 적합성 기준 분리

**결정**

- 분류·도구 호출의 `OK`만으로 상담 성공을 판정하지 않음
- 실제 질문과 최종 문장을 대조하는 답변 적합성 기준을 따로 둠

**이유**

- 개발 세트 `final` 실행: 제어 검사 24개 모두 통과
- 같은 실행의 답변 검토: 무관한 비용 부속 설명, 부정 문장의 재질문이 남음
- 참고: [실제 상담 평가][validation] "결과 읽는 방법"

**반영 위치**

- 기준: [consultation_answer_rubric.json][answer-rubric], [consultation_evaluation.json][consultation-eval]
- 코드: [ConsultationEvaluationTests.java][consultation-eval-test] (`real-agent` 태그), [evaluate_consultation_answers.py][evaluate-answers]

### 3.11 지원 범위 4품목

**결정**

- 세탁기·건조기·냉장고(김치 포함)·식기세척기로 코드·계약·자료·문서 통일
- 에어컨은 새 상담과 등록에서 제외

**반영 위치**

- [ProductCategory.java][product-category]
- [V004__four_category_scope.sql][v004-sql]

---

## 4. 검수에서 찾은 문제

- 범위: 실제 상담 출력과 화면 검수에서 찾은 문제와 조치
- 발견 경위: 저장소 기록이나 개발 대화 기록에 남은 경우만 기재

| # | 문제 | 발견 경위 |
| --- | --- | --- |
| [4.1](#41-등록-제품-상담-답변이-난잡함) | 등록 제품 상담 답변이 난잡함 | 개발자 지적 |
| [4.2](#42-냉장고-답변이-대부분-확인-필요로-나옴) | 냉장고 답변이 대부분 "확인 필요"로 나옴 | 개발자 제시 사례 |
| [4.3](#43-냉각-질문에-냄새-자료가-연결됨) | 냉각 질문에 냄새 자료가 연결됨 | - |
| [4.4](#44-세탁기-진동-질문에-침수급수-문서가-나옴) | 세탁기 진동 질문에 침수·급수 문서가 나옴 | 개발자 지적(10-14 14:12~14:24) |
| [4.5](#45-비용-문의에-무관한-보증과-고지가-붙음) | 비용 문의에 무관한 보증과 고지가 붙음 | 실제 상담 평가 |
| [4.6](#46-고객-화면에-원본-json이-나올-수-있음) | 고객 화면에 원본 JSON이 나올 수 있음 | 개발자 지적(10-14 14:12~14:24) |
| [4.7](#47-링크가-http참고-자료로-깨짐) | 링크가 `http참고 자료/...`로 깨짐 | - |
| [4.8](#48-냉장고-2대-계정에서-묻지-않고-첫-번째-냉장고를-고름) | 냉장고 2대 계정에서 묻지 않고 첫 번째 냉장고를 고름 | - |
| [4.9](#49-제거한-제품-선택-영역이-다시-나타남) | 제거한 제품 선택 영역이 다시 나타남 | 개발자 지적(10-14 12:38) |
| [4.10](#410-센터-3곳을-전국-조회처럼-사용함) | 센터 3곳을 전국 조회처럼 사용함 | 개발자 제시(10-15 11:12~11:18) |

### 4.1 등록 제품 상담 답변이 난잡함

- 현상
    - 제품 선택 전에 "검토를 마친 절차가 없어요" 안내가 먼저 나옴
    - 설명서 내용이 자가검진으로 안내되지 않음
- 원인: 승인 계획이 없는 제품은 `NOT_VERIFIED` 안내와 여러 카드가 함께 나옴
- 조치: 등록 제품 증상 상담을 LLM(대규모 언어 모델) 없이 정해진 순서로 진행
    1. 제품 선택
    2. 질문
    3. 자가검진 단계
    4. 요약 카드
- 발견 경위: 개발자 지적

### 4.2 냉장고 답변이 대부분 "확인 필요"로 나옴

- 현상: S836MEE022 "냉장고가 시원하지 않아요." 답변이 대부분 "확인 필요"
- 원인
    - 설명 필드 전달 누락
    - 실행 승인이 없다는 이유로 보증·센터·요약 전체를 불러옴
    - 보증 계산 조건 결합
    - 주소와 지역 미연결
- 조치
    - 관찰 항목 설명
    - 자동 확장 축소
    - 구매일 기준 보증
    - 주소 검색 결과의 지역 저장
- 발견 경위: 개발자 제시 사례
- 기록: [제조사 자료 사용 점검][data-audit] 4절·8절

### 4.3 냉각 질문에 냄새 자료가 연결됨

- 원인: [KnowledgeTextMatcher.java][text-matcher]의 `COOLING` 정규식이 "냉장고 안에서"를 부정으로 해석하고 "정지·저하"를 놓침
- 조치: 정규식 교체
    - 테스트: [KnowledgeRetrievalTests][retrieval-test] `coolingComplaintsSelectCoolingFactsNotInteriorFacts`
- 기록: [제조사 자료 사용 점검][data-audit] 3절

### 4.4 세탁기 진동 질문에 침수·급수 문서가 나옴

- 원인: "세탁기"처럼 넓은 단어만 일치한 문서가 상위에 나옴
- 조치: 모델·품목·제외 필터와 증상 어휘·임베딩 검색으로 보완
- 발견 경위: 2026-10-14 14:12~14:24 개발자가 실제 출력을 제시하며 지적
- 기록: [제조사 자료 검색 연결][retrieval] "구현 결과"

### 4.5 비용 문의에 무관한 보증과 고지가 붙음

- 현상: 비용 문의에 무관한 모터별 보증과, 금액이 없는데도 '위 금액' 고지가 붙음
- 원인: 실제 상담 평가의 답변 대조에서 발견
- 조치: 비용 근거, 부족 사유, 전체 보증 요지만 제공
- 발견 경위: 실제 상담 평가
- 기록: [실제 상담 평가][validation] "발견한 문제와 수정"(표 전체 10건)

### 4.6 고객 화면에 원본 JSON이 나올 수 있음

- 현상: 고객 화면에 원본 JSON·해시·내부 QA 이력·파일 경로가 나올 수 있음
- 원인: 과거 B30 자료의 `content/text`가 JSON 래퍼였음
- 조치: 표시 시점에 고객용 필드만 투영하고, 원본은 DEV 영역에서만 표시
    - 코드: [customerDisplay.js][customer-display]
    - 테스트: `consultationRendering.test.mjs` "rendered response shows one structured document and keeps file paths, raw JSON and internal evidence out"
- 발견 경위: 2026-10-14 14:12~14:24 개발자가 실제 출력을 제시하며 지적
- 기록: [상담 화면의 결과 표시][display]

### 4.7 링크가 `http참고 자료/...`로 깨짐

- 원인: `customerDisplay.js`의 드라이브 문자 정규식이 `https://`의 `s:/`를 경로로 치환
- 조치
    - 앞 글자가 영문이면 치환에서 제외
    - 말풍선의 링크를 앵커로 렌더링

### 4.8 냉장고 2대 계정에서 묻지 않고 첫 번째 냉장고를 고름

- 원인: 닉네임 "냉장고"가 문장에 들어 있다는 이유로 자동 선택
- 조치: 품목 이름과 같은 닉네임으로는 자동 선택하지 않고 `PRODUCT_CHOICE` 카드로 질문
    - 코드: `ConsultationService.automaticProduct`
    - 테스트: `McpIntegrationTests.withTwoRegisteredRefrigeratorsTheCustomerPicksOneBeforeTheQuestionIsAnswered`

### 4.9 제거한 제품 선택 영역이 다시 나타남

- 현상: 제거했던 상담 제품 선택 영역이 화면에 다시 나타남. 같은 지적에서 자가조치·수리비 누락도 제기
- 조치: 상담 화면에 제품 선택 영역을 다시 넣지 않고 등록 제품의 DB 정보를 쓰기로 결정
    - 관련 후속 수정: [Chat.jsx][chat-jsx], `AgentChatService`
- 발견 경위: 2026-10-14 12:38 개발자가 실제 화면을 보고 지적

### 4.10 센터 3곳을 전국 조회처럼 사용함

- 현상
    - `centers.json`이 대치·분당·북수원 3곳뿐이라 제주 등 다른 지역은 결과가 없음
    - 등록 주소가 센터 검색용 지역으로 연결되지 않음
- 조치
    - `find_service_centers`가 센터 목록 대신 공식 출장 접수·센터 찾기·전화를 반환(`directory_status=NOT_INTEGRATED`)
    - 주소 검색 결과의 시·도/시·군·구 저장
    - 전국 센터 자료는 미구현([3.6](#36-전국-센터-자료로-가까운-센터-안내))
- 발견 경위: 2026-10-15 11:12~11:18 개발자가 실제 냉장고 답변과 함께 제시
- 기록: [제조사 자료 사용 점검][data-audit] 4.4절·8절, [A/S 연결과 센터 데이터 운영 설계][service-channel]

---

## 5. 관련 문서

- [답변 개편 요구사항][rework-req]
- [제조사 자료 사용 점검][data-audit]
- [제조사 자료 검색 연결][retrieval]
- [상담 화면의 결과 표시][display]
- [실제 상담 평가와 회귀 검증][validation]
- [보증 구현][warranty-impl], [구매일 기준 보증 검수][purchase-warranty]
- [A/S 연결과 센터 데이터 운영 설계][service-channel]

[flow-png]: consultation_flow_target.png
[rework-req]: answer_rework_requirements.md
[data-audit]: manufacturer_data_usage_audit.md
[retrieval]: manufacturer_retrieval.md
[display]: consultation_display.md
[validation]: consultation_validation.md
[warranty-impl]: warranty_implementation.md
[purchase-warranty]: purchase_date_warranty_verification.md
[service-channel]: service_channel_operating_plan.md
[answer-rubric]: consultation_answer_rubric.json
[consultation-eval]: consultation_evaluation.json

[agent-chat]: ../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java
[answer-context]: ../../backend/src/main/java/com/project/backend/mcp/agent/AnswerContext.java
[manual-check-items]: ../../backend/src/main/java/com/project/backend/mcp/agent/ManualCheckItems.java
[symptom-estimate]: ../../backend/src/main/java/com/project/backend/mcp/tool/SymptomRepairEstimate.java
[tool-data-catalog]: ../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java
[find-centers-tool]: ../../backend/src/main/java/com/project/backend/mcp/tool/handler/FindServiceCentersTool.java
[check-warranty-tool]: ../../backend/src/main/java/com/project/backend/mcp/tool/handler/CheckWarrantyTool.java
[text-matcher]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeTextMatcher.java
[warranty-service]: ../../backend/src/main/java/com/project/backend/warranty/WarrantyService.java
[product-category]: ../../backend/src/main/java/com/project/backend/conversation/ProductCategory.java
[symptom-parts-json]: ../../backend/src/main/resources/homecare/symptom-parts.json
[build-symptom-parts]: ../../backend/scripts/build_symptom_parts.py
[source-registry]: ../../backend/scripts/source_registry.py
[evaluate-answers]: ../../backend/scripts/evaluate_consultation_answers.py
[symptom-parts-csv]: ../../수리비_정규화_과정/증상_부품_근거_v1.csv
[observation-parts-csv]: ../../수리비_정규화_과정/관찰_부품_근거_v1.csv
[v004-sql]: ../../ops/rds/sql/V004__four_category_scope.sql
[gitignore]: ../../.gitignore
[consultation-message]: ../../frontend/src/components/ConsultationMessage.jsx
[consultation-card]: ../../frontend/src/components/ConsultationCard.jsx
[warranty-result]: ../../frontend/src/components/WarrantyResult.jsx
[customer-display]: ../../frontend/src/customerDisplay.js
[chat-jsx]: ../../frontend/src/Chat.jsx

[mcp-it]: ../../backend/src/test/java/com/project/backend/mcp/McpIntegrationTests.java
[consultation-eval-test]: ../../backend/src/test/java/com/project/backend/mcp/ConsultationEvaluationTests.java
[manual-check-items-test]: ../../backend/src/test/java/com/project/backend/mcp/agent/ManualCheckItemsTests.java
[symptom-estimate-test]: ../../backend/src/test/java/com/project/backend/mcp/tool/SymptomRepairEstimateTests.java
[tool-backend-test]: ../../backend/src/test/java/com/project/backend/mcp/tool/ToolBackendTests.java
[retrieval-test]: ../../backend/src/test/java/com/project/backend/mcp/retrieval/KnowledgeRetrievalTests.java
[warranty-service-test]: ../../backend/src/test/java/com/project/backend/warranty/WarrantyServiceTests.java
[data-integrity-test]: ../../backend/src/test/java/com/project/backend/data/DataIntegrityTests.java
[rendering-test]: ../../frontend/src/consultationRendering.test.mjs
