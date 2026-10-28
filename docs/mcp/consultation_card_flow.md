# 상담 흐름·카드 개편 구현 기록

작성: 2026-10-15~16 (Asia/Seoul). 목표 흐름과 결정·이유는 [상담 설계 결정 기록](consultation_design_decisions.md), 남은 작업의 현재 상태는 [남은 작업](remaining_tasks.md#2-상담-흐름카드-개편-2026-10-16-기준)에 있다. 이 문서는 개편 중에 남긴 구현 내용, 설계, 검증 명령이다.

## 1. 구현 기록

### 1.1 기반 작업 (2026-10-15)

- 냉각 검색 오류 수정, 링크 표시 수정
- 구매일 기준 보증
- 설명서 관찰 항목(`ManualCheckItems`, 검토 목록 16개)
- 냉장고 냉각 상태 질문(`COOLING_STATE`)
- `NOT_VERIFIED`만으로 보증·센터·요약을 펼치지 않음(`ConversationService.explainedWithoutApproval`)
- 주소 검색의 시·도/시·군·구 저장
- `find_service_centers`가 센터 3곳 대신 공식 연결 반환(`directory_status=NOT_INTEGRATED`)

기록: [manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 8절.

### 1.2 증상별 수리비와 근거 자료 (2026-10-15)

| 파일 | 내용 |
|---|---|
| `collected_sources.json` | 수집 링크 관리(146건). 로컬 전용이며 `.gitignore`로 저장소에서 제외한다. |
| `backend/scripts/source_registry.py` | 링크 정규화·중복 확인. `python -I backend/scripts/source_registry.py check <url>` |
| `backend/scripts/collect_case_sources.py` | 국내 수리 후기 원문 수집. 등록된 링크는 건너뛴다. |
| `수리비_정규화_과정/증상_부품_근거_v1.csv` | 검토한 근거 78행(국내 후기 51 · LG 공식 오류 설명 25 · 수리업체 가이드 2). 링크 없이 사례 ID·자료 ID만 둔다. |
| `backend/scripts/build_symptom_parts.py` | 근거 표 검증 → `homecare/symptom-parts.json` 생성, 매니페스트 갱신. `--check`로 재현 확인 |
| `backend/src/main/resources/homecare/symptom-parts.json` | 증상·오류 → 비용 그룹 연결 45개(증상 20, 오류 25) |
| `source-manifest.json` | `files`에 symptom-parts 해시, `symptomParts` 섹션 추가 |
| `build_homecare_data.py` | `VERIFIED_FILES`에 symptom-parts 추가 |
| `ToolDataCatalog.java` | `symptomParts` 로드·건수·부품 검증 |
| `SymptomRepairEstimate.java` + 테스트 | 수리비 규칙 구현. 오류 코드 우선, 없으면 증상 기준 |
| `DataIntegrityTests.java` | 복사 목록에 symptom-parts 추가 |
| `docs/mcp/answer_rework_requirements.md` | 불필요 문구·단계 안내 요구 정리 |
| `docs/mcp/consultation_flow_target.png` | 목표 흐름도 |

검증(당시): 이 변경을 포함한 백엔드 전체 318개 중 316개 통과(MySQL 전용 2개 건너뜀). `build_symptom_parts.py --check` 통과.

### 1.3 자가검진 단계와 요약 카드 (2026-10-15)

개발자가 지적한 문제는 세 가지였다.
- 선택하기 전에 "검토를 마친 절차가 없어요"가 먼저 나왔다.
- 설명서 내용이 자가검진으로 안내되지 않았다.
- 답변이 난잡했다.

그래서 등록 제품의 증상 상담(승인 계획 없음)을 LLM 없이 정해진 순서로 진행하게 바꿨다.

0. **제품 선택:** 같은 품목의 등록 제품이 2개 이상이고 어느 제품인지 정해지지 않았으면, 메시지를 해석하기 전에 제품 선택 카드를 보낸다. 위험 의심·확정 상태라면 안전 안내가 먼저 나간다.
   - 카드: `tools.card` type `PRODUCT_CHOICE`, `products[{id, category, name(닉네임), model}]`. 오래 등록한 제품부터 나오고, 화면에는 품목·닉네임·모델명 버튼으로 보인다.
   - 선택: `POST /api/conversations/{id}/product-choice` `{product_id, context_version}` (`AgentChatService.chooseProduct`). 제품을 정한 뒤 카드를 띄운 그 메시지에 이어서 답한다. 고객 말풍선은 "냉장고 2 · S834MEE111"로 저장된다. 이미 정했거나 안내가 바뀌었으면 409를 돌려준다.
   - 자동 선택(`ConsultationService.automaticProduct`): 모델명이 하나만 맞으면 그 제품을 고른다. 닉네임은 품목 이름과 같지 않을 때만 쓴다. 예를 들어 닉네임이 "냉장고"면 "냉장고가 시원하지 않아요"라는 문장만으로 그 제품을 고르지 않는다. 닉네임이 다른 닉네임을 포함하면("냉장고 2"와 "냉장고") 더 긴 이름을 고른다.
   - 수정 전에는 test@gmail.com 계정(‘냉장고’ S836MEE022, ‘냉장고 2’ S834MEE111)에서 닉네임 "냉장고"가 문장에 들어 있다는 이유로 첫 번째 냉장고가 묻지 않고 선택됐다.
1. 정보가 부족하면 질문과 선택지만 보여 준다. 냉장고 냉각은 `COOLING_STATE` 질문 하나, 도구 호출 없음.
2. 설명서 확인 항목(`ManualCheckItems.of` → "…을/를 확인해 주세요.")과 고객이 볼 수 있는 상황(`conditions` → "…경우에 해당하는지 확인해 주세요.")을 최대 6단계로 만든다.
   - 일반 사용자가 뜻을 모를 말(운송용 고정볼트, 물 경도, 연수용 소금)이 든 항목은 단계로 묻지 않는다(`ManualCheckItems.UNFAMILIAR`, 카드 단계에만 적용). 카드에는 "무슨 뜻인지 모르거나 확인하기 어려운 항목은 ‘아니요’를 눌러 넘어가 주세요." 안내가 함께 나온다.
   - 단계 전체를 한 응답의 카드(`tools.card`, type `SELF_CHECK`, `steps`)로 보낸다. 화면이 한 단계씩 "확인한 뒤 해결됐나요?"를 묻는다.
   - [아니요, 다음 단계]는 브라우저 안에서만 넘어가고 서버 요청을 보내지 않는다. 단계마다 `/api/chat`을 보내면 채팅 요청 제한(연속 3회, 분당 5회)에 걸리고 AI 해석이 매번 돌았기 때문이다.
   - 서버에는 결과 한 번만 보낸다: `POST /api/conversations/{id}/self-check` `{result: SOLVED|NOT_RESOLVED, step, context_version}`. 채팅 요청 제한 밖이며 AI 호출이 없다(`AgentChatService.answerSelfCheck`). NOT_RESOLVED는 마지막 단계에서만 받는다. 고객 말풍선은 "N단계에서 해결됐어요" / "N단계까지 모두 확인했지만 해결되지 않았어요"로 저장된다.
   - 상태는 dialogue `answers`의 `CHECK_ITEMS`·`CHECK_INDEX`(해결·종료 단계)·`CHECK_DONE`에 저장한다.
   - 버튼 대신 직접 입력하면 `DialogueRouter`가 답 내용을 먼저 읽는다: 해결 → 종료, 안 됨 → 요약 카드, 그 밖의 말 → 같은 단계 카드를 다시 보여 준다.
3. 해결되면 상담을 종료한다. 마지막 단계도 안 되거나 단계가 없으면 요약 카드를 만든다(`tools.card`, type `SUMMARY`).
   - 담는 내용: 보증 한 줄, `SymptomRepairEstimate` 수리비, 지역, 공식 경로, 상담 요약.
   - 보증·센터·요약 도구는 `POLICY_FALLBACK` 정책 호출이다.
4. 프론트 `ConsultationCard`: 카드가 있는 응답은 이 카드 하나만 그린다. 보증·A/S·요약 카드를 따로 띄우지 않는다.

- 코드 위치: `AgentChatService.guided/guidedReply/answerSelfCheck/checkSteps/summary`, `ConsultationController`(`/self-check`), `DialogueRouter`(SELF_CHECK 직접 입력 처리·`checkQuestion`), `ManualCheckItems.sentences/conditions`, `frontend/src/components/ConsultationCard.jsx`(`SelfCheck`), `Chat.jsx`(`answerSelfCheck`)
- 검증(당시): 백엔드 320개 중 318개 통과(MySQL 2개 건너뜀, 통합 테스트는 `HOMECARE_TEST_PYTHON=mcp_server/.venv/Scripts/python.exe` 필요), 프론트 29개·lint·build·브라우저 표시 검증(`npm run test:display:browser`, 단계 넘김 무전송 확인) 통과
- 당시 남은 부분: 2.1 센터 자료, 위험 흐름 카드(2.2의 2번), 제품 확인 질문(2.2의 3번), 위험 확정 후 요약 카드 연결. 요약 카드의 센터 목록은 2.1 전까지 비어 있고, 공식 센터 찾기 링크로 대신한다. 이후 상태는 [남은 작업](remaining_tasks.md#2-상담-흐름카드-개편-2026-10-16-기준)에 있다.

### 1.4 원인을 좁히는 관찰 질문 (2026-10-15)

- 냉장고 냉각 불량에 질문 3개를 둔다(`ObservationQuestions`): 표시창 상태, 작동 소리('웅'), 냉동실 성에. 분해 없이 보거나 들어서 답할 수 있는 것만 묻고, 성에는 직접 녹이거나 떼지 말라고 안내한다.
- 자가점검 마지막 단계에서 해결되지 않으면 같은 카드에서 질문으로 넘어간다. 자가점검 단계가 없으면 질문부터 보여 준다. 답은 `/self-check` 요청 한 번에 `observations`로 함께 보낸다.
- 근거 표: `수리비_정규화_과정/관찰_부품_근거_v1.csv` (13행 → 연결 9건). `build_symptom_parts.py`가 `symptom-parts.json`에 `kind: OBSERVATION` 연결로 넣는다(버전 `SYMPTOM-PARTS-V2-20261015`).
  - 표시창 꺼짐 → 메인 PCB(가이드 GUIDE-02, 사례 SR06)
  - 표시창 깜빡임 → 메인 PCB(사례 R14), 제상 센서(사례 SR15)
  - 작동 소리 없음 → 컴프레서·메인 PCB(GUIDE-02, 사례 SR07·R22)
  - 뒷벽 두꺼운 성에 → 제상 히터(GUIDE-03), 제상 센서·히터·메인 PCB(LG 공식 F dH)
  - 문 주변 성에 → 도어 가스켓(GUIDE-03)
- GUIDE-02/03은 EasyBear 수리 기술자 글이다. 원문을 내려받아 인용 문장 7개가 실제로 있는지 확인했고, 크기·해시를 로컬 출처 등록 파일(`collected_sources.json`, git 제외)에 기록했다.
- 수리비: 답이 가리키는 부품만으로 "답하신 내용 기준" 범위를 만들고 전체 범위는 함께 보여 준다. 답끼리 서로 다른 부품을 가리키면 모두 남긴다(빼지 않음). 정상·모르겠어요 답만 있으면 좁히지 않는다. 오류 코드가 있으면 질문을 하지 않는다.
- "오류 코드가 보여요"를 고르면 카드에 "오류 코드를 채팅에 입력해 주세요"를 보여 준다. 코드를 입력하면 상담이 그 코드 기준으로 다시 시작된다(`ConsultationService.begin`).
- 상담원 요약에 "고객이 확인한 관찰: …"을 넣는다.

### 1.5 오류 코드는 LG 공식 안내대로 (2026-10-15)

- 안전 규칙 변경: 실행 지시(전원 분리·해동 등)는 **LG 공식 오류 안내에 있는 것만** 자가점검 단계로 안내한다. 설명서 일반 문구에서 뽑는 `ManualCheckItems`의 규칙은 그대로다.
- 수집: `backend/scripts/collect_error_guides.py`
  - 등록 모델 52개의 설명서 25개 중 세탁기·건조기·식기세척기 16개의 "에러 메시지" 항목을 설명서 검색 색인(`res/js/indexer.js`, 항목 전문 포함)에서 가져온다.
  - 냉장고는 설명서에 코드표가 없어 LG 지원 페이지 2개(Er CH/CL, 27개 코드)와 기존 공식 사실(T2-030~033, 045~050)을 쓴다.
  - 원문은 `.homecare-cache/`(git 제외)에, 출처와 해시는 `collected_sources.json`(git 제외)에 남는다.
- 검토 표: `수리비_정규화_과정/오류코드_공식안내_v1.csv` (339행). `build_error_guides.py`가 검증해 `homecare/error-guides.json`(178개 항목, 매니페스트 해시 검증)을 만든다.
  - 항목은 설명서의 "확인? 조치" 쌍을 원문대로 둔다. 서비스 문의만 하라는 항목은 단계가 아니라 마지막 연락 문구(contact)가 된다.
- 연동:
  - 설명서 코드표는 그 설명서를 쓰는 모든 모델에 적용된다(예: F21VDSKR ← F24WDWPR 설명서). 색상 모델은 `models.json`의 대표 모델(`representative_model_code`)로 연결된다.
  - 같은 안내 문구를 쓰는 표시 문구와 코드는 대표 코드 하나로 묶는다(세탁기 "배수 안됨"=OE, "물높이 감지 안됨"=PE, 건조기 "컴프레서 이상"=LE2 등). 7세그먼트 표기(dEz=dE2, tEs=tE5, [L=CL)도 정규화한다.
- 인식(`ErrorCodeParser`): 새로 추가된 코드와 한글 표시 문구는 "표시/떠요/화면/에러" 같은 말이 있을 때만 인식한다. 인식한 값은 대표 코드로 저장한다.
- 흐름: 오류 코드가 있으면 자가점검 카드 단계가 LG 공식 안내 단계가 된다(카드 머리말 "LG 안내대로 확인해 보세요"). 단계가 없으면(서비스 문의 전용) 바로 요약 카드로 간다. 요약 카드 맨 위 "LG 안내" 줄에 의미·원인·연락 문구를 보여 준다.
- 부품 연결(`증상_부품_근거_v1.csv`, 근거 종류 `OFFICIAL_GUIDE` 추가, 71개 연결):
  - 세탁기: FE→급수밸브, PE→수위센서, dE4·dL→도어락 스위치, OE→배수펌프, tE→히터
  - 건조기: 드럼 모터 이상→드럼 모터
  - 냉장고: ER rF→팬모터, Er dH·d1·d2·d·H1·H2→제상 센서·제상 히터(LG 지원 페이지 + GUIDE-03)
  - 공식 원인이 부품을 지목하지 않는 코드(예: Er CO, 탁도 감지, 진동 센서, 건조기 컴프레서·펌프처럼 수리비 표에 없는 부품)는 금액을 만들지 않는다.

남은 결정·한계
- **냉장고 공식 안내 충돌:** 냉장고 설명서 9개의 "고장 감지 기능"은 "에러 코드가 나타나면 전원을 끄지 말고 바로 서비스 센터에 연락"하라고 한다. 코드별 LG 지원 페이지는 "전원 플러그를 뽑고 5분 후 다시 꽂기", "전원 분리 후 해동"을 안내한다. 지금은 코드별 지원 페이지를 따른다.
- 냉장고 27개 코드는 LG가 코드별 의미를 따로 공개하지 않아 "내부 시스템 이상"으로 함께 안내한다.

### 1.6 위험 확정 후 요약 카드, 보증 카드의 비용 칸 제거 (2026-10-16)

- 결정: 위험이 확정돼도 자가조치만 빼고 수리비는 알려 준다. 보증 카드의 부품비·기술료·출장비는 금액을 보여 줄 수 없으면 빼고 수리비를 보여 준다([설계 결정 기록](consultation_design_decisions.md)).
- 위험 확정(`CONFIRMED`): `safety()`가 경고 문구 뒤에 SUMMARY 카드(보증 한 줄·예상 수리비·A/S 연결·상담 요약)를 붙인다. 자가점검 카드와 MCP 비용 도구(`get_repair_candidates`·`estimate_repair_cost`)는 계속 막고, 카드 금액은 `SymptomRepairEstimate`로 서버에서 계산한다. 의심(`SUSPECT`) 단계에서는 질문만 보여 준다.
- 위험 문장(연기·타는 냄새 등)은 증상·오류와 부품이 연결되지 않으므로 `SymptomRepairEstimate.orCategory`가 그 품목 주요 부품 전체 범위를 `basis: CATEGORY`로 돌려준다. 카드에는 "원인 부품은 점검 후 확인돼요. ○○ 주요 부품 N종 기준 범위예요."로 표시하고, 부품별 금액은 "수리비 근거"에 둔다. 예: 건조기 4.3만~28.3만 원(6종).
- 보증 카드(`WarrantyResult`)에서 부품비·기술료·출장비 칸을 뺐다. 실제 데이터에서는 출장비가 항상, 나머지도 대부분 "추가 확인 필요"였다. 보증 문의 답변(`WarrantyService.summarize`)도 기간만 쓴다. 보증 결과의 `partFee`·`laborFee`·`visitFee` 필드와 도구용 `explain` 문구는 그대로 둔다.
- 일반 증상의 근거 없는 7개(아래)는 지금도 금액을 표시하지 않는다. 같은 품목 전체 범위로 바꿀지는 정하지 않았다.

### 1.7 수리비 계산 예 (냉장고 냉각 불량)

- 기본 4.8만~18.4만 원 (제상 센서·응축기 팬모터·메인 PCB)
- 따로 안내: 컴프레서 23.1만~42.8만 원
- 전체 4.8만~42.8만 원

근거가 없는 증상은 7개다: 세탁기 DOOR, 건조기 DOOR·ODOR, 냉장고 ODOR, 식기세척기 WASHING·DRYING·FILL. 이 증상에서는 금액을 표시하지 않는다.

## 2. 개편 설계

2026-10-15에 다음 작업으로 정리한 설계다. 진행 상태는 [남은 작업](remaining_tasks.md#2-상담-흐름카드-개편-2026-10-16-기준)에서 확인한다.

### 2.1 전국 센터 자료

- 출처: `https://www.lge.co.kr/support/find-service-center-{seq}`. robots.txt가 `/support/`를 막지 않는다.
- HTML에서 뽑을 값: 센터명, 도로명·지번 주소, 운영시간, 취급 제품, 출장 안내, `data-latitude`/`data-longitude`.
- 예: `seq=137`은 은평서비스센터. 방문 예약 링크는 `/support/visit-center-reservation?seq={seq}`이다.
- 수집 방식: seq 범위를 확인한 뒤 1초 간격으로 수집한다. `SourceRegistry`로 중복을 막고 `used_for=service_center:{seq}`로 기록한다.
- 결과물: `homecare/centers.json`을 교체한다. 각 센터에 `sido`, `sigungu`, 출처 URL, 확인일을 넣고, 매니페스트 해시를 갱신한다. 수집·변환 스크립트는 `backend/scripts`에 둔다.
- 조회 방식: `FindServiceCentersTool`은 설치 지역의 시·군·구 → 시·도 순으로 최대 3곳을 반환하고 공식 센터 찾기 링크를 함께 준다. 지역이 없으면 `NEEDS_INPUT`(`CONFIRM_REGION`)이다. 거리순이라고 표시하지 않는다(사용자 주소 좌표가 없음).
- 영향받는 테스트: `ToolBackendTests.serviceConnectionReportsTheStoredRegionAndNeverListsSeedCenters`, `McpIntegrationTests`의 센터 기대값, `consultationRendering.test.mjs` 센터 카드.

### 2.2 흐름 개편 (백엔드)

현재 코드에 이미 있는 부분은 그대로 활용한다.
- 위험 판단: `RiskRules`, `RiskAssessmentService`, `ConsultationService.riskAnswer`(UNSURE=확정, 모두 NO면 DISMISSED), 긴급이면 즉시 CONFIRMED
- 승인 계획 단계 진행: `SelfCareChatService`
- 보증: `WarrantyService`
- 수리비: `SymptomRepairEstimate`

구현할 단계는 다음과 같다.

1. **카드 계약:** 응답 `tools`에 정책 봉투 `card`를 하나 추가한다. `ToolEnvelope.policy`를 쓰고 MCP 도구는 아니다. `data.type`은 `RISK_QUESTION`, `SAFETY`, `PRODUCT_CONFIRM`, `SELF_CHECK`, `SUMMARY`, `RESOLVED` 중 하나다. 기존 도구 결과는 저장·DEV 표시용으로 유지한다.
2. **위험:**
   - SUSPECT: 사용 중지 문구를 먼저 보여 주고, 질문과 예/아니요/모르겠어요, [상담원 연결]을 붙인다.
   - CONFIRMED: 안전 경고 뒤에도 제품 확인 → SUMMARY(맨 위 안전 경고)로 이어간다. 설계 당시에는 `applyDialogue`가 SUSPECT/CONFIRMED에서 막히고 `safety()`에서 끝났다.
   - DISMISSED: 원래 증상으로 바로 이어간다. 설계 당시 `answerRisk`는 고정 문구만 반환했다.
3. **제품 확인:**
   - 자동 선택된 제품을 대화에서 아직 확인하지 않았으면 "등록하신 '{표시 이름}({모델})'로 상담할까요?"를 묻는다. dialogue `answers`에 `PRODUCT_CONFIRMED=<productId>`를 저장한다.
   - 여러 제품이면 선택 버튼을, 없으면 등록 안내를 보여 준다.
4. **자가검진 대상:**
   - 단계 출처는 승인 계획 단계를 우선하고, 없으면 검색된 MANUAL 자료의 `ManualCheckItems`를 쓴다. "보관 온도, 문 닫힘, …"처럼 대상이 여럿이면 하나씩 나눈다.
   - 단계 목록과 진행 위치는 dialogue `answers`에 저장한다(예: `CHECK_ITEMS`, `CHECK_INDEX`).
   - 위험 확정이면 자가검진을 건너뛴다.
5. **1차 카드:** 제품 정보와 현재 단계 하나, "해결됐나요?" 예/아니요. 예면 RESOLVED(종료), 아니요면 다음 단계, 마지막이면 SUMMARY.
6. **2차 카드(SUMMARY):**
   - 위험이면 안전 경고를 맨 위에 둔다.
   - 보증은 한 줄로 쓴다. 예: "보증기간 안 · 만료 2027-05-05 · 무상 여부는 점검 때 확정"
   - 예상 수리비: 기본 범위, 따로 안내할 부품, 전체 범위. 보증기간 안이면 "보증이 안 될 때 금액"이라고 밝힌다.
   - 근거는 접힌 상태로 둔다(사례·공식·가이드 건수와 요지).
   - A/S: 지역 센터 최대 3곳, 공식 센터 찾기, 출장 접수, 상담원 1544-7777, 요약 복사.
7. **도구 호출:** 단계 진행·요약은 LLM 도구 선택 없이 정책 호출로 처리한다. `SelfCareChatService.save`처럼 `ToolBackend`를 직접 부르고 `ToolCallRecord`를 남긴다. MCP 세션 한도는 최대 5회, 도구당 2회다.
8. **말풍선:** 한두 문장만 쓴다. 나머지는 카드에 둔다.

### 2.3 프론트엔드

- `ConsultationMessage`는 `tools.card`가 있으면 카드 하나만 그린다. 기존 `ToolResults`, `WarrantyResult`, `KnowledgeSources`는 카드가 없는 과거 저장 메시지에만 쓴다.
- 새 `ConsultationCard` 컴포넌트를 만든다. 유형별 레이아웃과 버튼(예/아니요, 단계 응답, 제품 선택, 상담원 연결, 출장 접수, 센터 찾기, 요약 복사)을 넣는다.
- 단계 응답과 제품 확인 버튼은 일반 메시지로 보낼지 전용 API로 보낼지 정한다. 기존 `onAnswerSelfCare`(조건·단계 API)와 `onSelectSymptom`(메시지 전송)을 참고한다.
- 휴대폰 너비에서도 한 화면에 보이게 한다.

### 2.4 문서·테스트

- [설계 결정](consultation_design_decisions.md)과 맞지 않게 된 원칙을 고친다: [repair_followup.md](repair_followup.md)(비용은 질문 시만·3건 기준), [grounded_answer_generation.md](grounded_answer_generation.md)(조치 설명 제외), [consultation_display.md](consultation_display.md), [service_channel_operating_plan.md](service_channel_operating_plan.md)(센터 직접 구축으로 변경).
- `McpIntegrationTests`, `ConsultationEvaluationTests`, `RepairFollowupPolicyTests`, 프론트 렌더링 테스트를 새 흐름에 맞춘다.

## 3. 검증 명령

저장소 루트에서 실행한다. 저장소 작업 시 주의사항(줄바꿈, Python `-I` 등)은 [개발 가이드 10.4](../guides/repair_development_guide.md#104-저장소-작업-시-주의사항)에 있다.

```powershell
$env:JAVA_HOME='<JDK 21 설치 경로>'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_TEST_PYTHON=(Resolve-Path '.\mcp_server\.venv\Scripts\python.exe').Path
Set-Location backend; .\gradlew.bat test --offline; Set-Location ..
& $env:HOMECARE_TEST_PYTHON -m unittest discover -s .\mcp_server\tests -t .\mcp_server -q
Set-Location frontend; npm test; npm run lint; npm run build; Set-Location ..
python -I backend/scripts/build_symptom_parts.py --check
python -I backend/scripts/structure_knowledge.py --check
```

MCP 도구 설명을 바꾸면 `mcp_server`에서 `.venv\Scripts\python.exe -m scripts.export_contract`로 계약을 다시 생성한다.
