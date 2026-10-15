# 인계: 상담 흐름·카드 개편, 증상별 수리비, 센터 자료

작성일: 2026-10-15. 다음 대화에서 이 문서 하나로 작업을 이어간다.

## 1. 목표

사용자가 준 흐름도([consultation_flow_target.png](consultation_flow_target.png))대로 상담이 진행되게 한다. 응답마다 카드 하나만 쓰고, 한눈에 보이게 배치한다.

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

## 2. 사용자가 정한 내용

| 항목 | 결정 |
|---|---|
| 카드 | 응답마다 카드 하나. 지금처럼 자가검진·보증·A/S·요약·근거 카드를 여러 개 쌓지 않는다. |
| 불필요한 문구 | [answer_rework_requirements.md](answer_rework_requirements.md) 3절 목록을 삭제한다. "이 설명은 … 승인이 아닙니다", 연결 문구, 설명서 링크, "검토된 절차 없음" 카드 등이 대상이다. |
| 자가 점검 | 설명서 확인 항목(예: 정전 여부와 전원 플러그 연결 / 보관 온도 / 문 닫힘 / 식품 배치 / 주위 설치 환경)은 자가 조치다. 단계별로 안내하고 설명서 링크는 주지 않는다. 자가 점검이 없으면 텍스트로 "점검 방법이 없다"고 말하고 수리비로 넘어간다. |
| 수리비 표시 | 후보 부품의 기본 범위를 보여 준다. 상한이 10만 원 이상 차이 나는 비싼 부품(예: 컴프레서, 보증이 안 될 때)은 따로 알리고, 그 부품을 포함한 전체 범위도 보여 준다. 사례 3건 미만 부품도 포함한다. |
| 증상→부품 근거 | Claude가 찾아 구축한다(완료, 4절). 수리업체 가이드도 유효한 근거로 쓴다(국내 사례·공식 자료와 구분 표기). |
| 가까운 센터 | LG 센터 찾기에는 지역을 지정하는 링크가 없다. 그래서 LG 공식 센터 상세 페이지로 전국 센터 자료를 직접 구축해, 설치 지역 기준 센터를 보여 준다. 공식 센터 찾기 링크도 함께 둔다. |
| 링크 관리 파일 | `collected_sources.json`(저장소 최상위)은 GitHub에 올리지 않는다. 사용자가 `.gitignore`에 추가한다. 절대 커밋하지 않는다. |

## 3. 현재 상태

### 커밋·푸시된 것

- 로컬 `main` `010832d` = 원격 `mcp_feature_integration` `5a9eba5`. 내용은 다음과 같다.
  - 냉각 검색 오류 수정, 링크 표시 수정
  - 구매일 기준 보증
  - 설명서 관찰 항목(`ManualCheckItems`, 검토 목록 16개)
  - 냉장고 냉각 상태 질문(`COOLING_STATE`)
  - `NOT_VERIFIED`만으로 보증·센터·요약을 펼치지 않음(`ConversationService.explainedWithoutApproval`)
  - 주소 검색의 시·도/시·군·구 저장
  - `find_service_centers`가 센터 3곳 대신 공식 연결 반환(`directory_status=NOT_INTEGRATED`)
- 기록: [manufacturer_data_usage_audit.md](manufacturer_data_usage_audit.md) 8절.

### 커밋하지 않은 것 (이번 대화 후반)

| 파일 | 내용 |
|---|---|
| `collected_sources.json` | 수집 링크 관리(146건). **커밋 금지** |
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
| `docs/mcp/consultation_flow_target.png`, 이 문서 | 흐름도, 인계 |

검증: 커밋하지 않은 변경을 포함한 백엔드 전체 318개 중 316개 통과(MySQL 전용 2개 건너뜀). `build_symptom_parts.py --check` 통과.

### 같은 날 추가 구현 (커밋 전): 자가검진 단계와 요약 카드

사용자가 지적한 문제는 세 가지였다.
- 선택하기 전에 "검토를 마친 절차가 없어요"가 먼저 나왔다.
- 설명서 내용이 자가검진으로 안내되지 않았다.
- 답변이 난잡했다.

그래서 등록 제품의 증상 상담(승인 계획 없음)을 LLM 없이 정해진 순서로 진행하게 바꿨다.

1. 정보가 부족하면 질문과 선택지만 보여 준다. 냉장고 냉각은 `COOLING_STATE` 질문 하나, 도구 호출 없음.
2. 설명서 확인 항목(`ManualCheckItems.of` → "…을/를 확인해 주세요.")과 고객이 볼 수 있는 상황(`conditions` → "…경우에 해당하는지 확인해 주세요.")을 최대 6단계로 만든다.
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
- 검증: 백엔드 320개 중 318개 통과(MySQL 2개 건너뜀, 통합 테스트는 `HOMECARE_TEST_PYTHON=mcp_server/.venv/Scripts/python.exe` 필요), 프론트 29개·lint·build·브라우저 표시 검증(`npm run test:display:browser`, 단계 넘김 무전송 확인) 통과
- 남은 부분: 4.1 센터 자료, 위험 흐름 카드(4.2의 2번), 제품 확인 질문(4.2의 3번), 위험 확정 후 요약 카드 연결. 요약 카드의 센터 목록은 4.1 전까지 비어 있고, 공식 센터 찾기 링크로 대신한다.

### 수리비 계산 예 (냉장고 냉각 불량)

- 기본 4.8만~18.4만 원 (제상 센서·응축기 팬모터·메인 PCB)
- 따로 안내: 컴프레서 23.1만~42.8만 원
- 전체 4.8만~42.8만 원

근거가 없는 증상은 7개다: 세탁기 DOOR, 건조기 DOOR·ODOR, 냉장고 ODOR, 식기세척기 WASHING·DRYING·FILL. 이 증상에서는 금액을 표시하지 않는다.

## 4. 다음 작업

### 4.1 전국 센터 자료

- 출처: `https://www.lge.co.kr/support/find-service-center-{seq}`. robots.txt가 `/support/`를 막지 않는다.
- HTML에서 뽑을 값: 센터명, 도로명·지번 주소, 운영시간, 취급 제품, 출장 안내, `data-latitude`/`data-longitude`.
- 예: `seq=137`은 은평서비스센터. 방문 예약 링크는 `/support/visit-center-reservation?seq={seq}`이다.
- 수집 방식: seq 범위를 확인한 뒤 1초 간격으로 수집한다. `SourceRegistry`로 중복을 막고 `used_for=service_center:{seq}`로 기록한다.
- 결과물: `homecare/centers.json`을 교체한다. 각 센터에 `sido`, `sigungu`, 출처 URL, 확인일을 넣고, 매니페스트 해시를 갱신한다. 수집·변환 스크립트는 `backend/scripts`에 둔다.
- 조회 방식: `FindServiceCentersTool`은 설치 지역의 시·군·구 → 시·도 순으로 최대 3곳을 반환하고 공식 센터 찾기 링크를 함께 준다. 지역이 없으면 `NEEDS_INPUT`(`CONFIRM_REGION`)이다. 거리순이라고 표시하지 않는다(사용자 주소 좌표가 없음).
- 영향받는 테스트: `ToolBackendTests.serviceConnectionReportsTheStoredRegionAndNeverListsSeedCenters`, `McpIntegrationTests`의 센터 기대값, `consultationRendering.test.mjs` 센터 카드.

### 4.2 흐름 개편 (백엔드)

현재 코드에 이미 있는 부분은 그대로 활용한다.
- 위험 판단: `RiskRules`, `RiskAssessmentService`, `ConsultationService.riskAnswer`(UNSURE=확정, 모두 NO면 DISMISSED), 긴급이면 즉시 CONFIRMED
- 승인 계획 단계 진행: `SelfCareChatService`
- 보증: `WarrantyService`
- 수리비: `SymptomRepairEstimate`

구현할 단계는 다음과 같다.

1. **카드 계약:** 응답 `tools`에 정책 봉투 `card`를 하나 추가한다. `ToolEnvelope.policy`를 쓰고 MCP 도구는 아니다. `data.type`은 `RISK_QUESTION`, `SAFETY`, `PRODUCT_CONFIRM`, `SELF_CHECK`, `SUMMARY`, `RESOLVED` 중 하나다. 기존 도구 결과는 저장·DEV 표시용으로 유지한다.
2. **위험:**
   - SUSPECT: 사용 중지 문구를 먼저 보여 주고, 질문과 예/아니요/모르겠어요, [상담원 연결]을 붙인다.
   - CONFIRMED: 안전 경고 뒤에도 제품 확인 → SUMMARY(맨 위 안전 경고)로 이어간다. 현재는 `applyDialogue`가 SUSPECT/CONFIRMED에서 막히고 `safety()`에서 끝난다.
   - DISMISSED: 원래 증상으로 바로 이어간다. 현재 `answerRisk`는 고정 문구만 반환한다.
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

### 4.3 프론트엔드

- `ConsultationMessage`는 `tools.card`가 있으면 카드 하나만 그린다. 기존 `ToolResults`, `WarrantyResult`, `KnowledgeSources`는 카드가 없는 과거 저장 메시지에만 쓴다.
- 새 `ConsultationCard` 컴포넌트를 만든다. 유형별 레이아웃과 버튼(예/아니요, 단계 응답, 제품 선택, 상담원 연결, 출장 접수, 센터 찾기, 요약 복사)을 넣는다.
- 단계 응답과 제품 확인 버튼은 일반 메시지로 보낼지 전용 API로 보낼지 정한다. 기존 `onAnswerSelfCare`(조건·단계 API)와 `onSelectSymptom`(메시지 전송)을 참고한다.
- 휴대폰 너비에서도 한 화면에 보이게 한다.

### 4.4 문서·테스트

- 위 결정과 맞지 않게 된 원칙을 고친다: [repair_followup.md](repair_followup.md)(비용은 질문 시만·3건 기준), [grounded_answer_generation.md](grounded_answer_generation.md)(조치 설명 제외), [consultation_display.md](consultation_display.md), [service_channel_operating_plan.md](service_channel_operating_plan.md)(센터 직접 구축으로 변경).
- `McpIntegrationTests`, `ConsultationEvaluationTests`, `RepairFollowupPolicyTests`, 프론트 렌더링 테스트를 새 흐름에 맞춘다.

## 5. 작업 규칙과 함정

- **커밋·푸시:**
  - 로컬 `main`에 커밋한다.
  - 원격 `mcp_feature_integration`은 `main`과 이력이 갈라져 있다(공통 조상 `65248f9`). 그래서 `origin/mcp_feature_integration` 기준 임시 worktree에 새 커밋만 cherry-pick해서 fast-forward로 푸시한다. 강제 푸시는 하지 않는다.
  - 로컬 브랜치 `mcp_feature_integration`(`b256ac1`)은 codex worktree에 체크아웃되어 있으니 건드리지 않는다.
- **커밋 메시지:** `feat: …` 한국어 한 줄 + 본문.
- **줄바꿈:** 저장소 파일은 LF다. Windows에서 Python `write_text`를 쓰면 CRLF가 되니 `write_bytes`나 `newline='\n'`을 쓴다.
- **Python `-I`:** 스크립트 폴더가 import 경로에서 빠지고 `PYTHONIOENCODING`도 무시된다. 스크립트 안에서 `sys.path`를 지정한다.
- **임시 worktree 삭제:** "Filename too long"이 나면 PowerShell `Remove-Item -LiteralPath '\\?\C:\…' -Recurse -Force`로 지운다.
- **반영:** 백엔드는 재시작해야 반영된다. 저장된 과거 상담은 다시 계산되지 않는다.
- **사용자 DB:** 제품 1(S836MEE022, 인천 연수구)은 주소 검색으로 지역을 저장했다. 로컬 DB는 MySQL80(8.0.36)을 쓴다.
- **원문 HTML:** 수집한 원문은 임시 폴더에만 있었다. 다시 필요하면 수집해야 하는데, `collected_sources.json`에 이미 등록된 링크는 수집 스크립트가 건너뛴다. 새 폴더로 다시 받으려면 재수집 옵션을 추가하거나 해당 항목을 지운다.
- **안전 원칙:** 확인 항목 외의 실행 지시(분해·교체·청소·재시작 등)는 단계에 넣지 않는다. 검토 목록은 `ManualCheckItemsTests`에 있다.

## 6. 검증 명령

```powershell
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_TEST_PYTHON=(Resolve-Path '.\mcp_server\.venv\Scripts\python.exe').Path
Set-Location backend; .\gradlew.bat test --offline; Set-Location ..
& $env:HOMECARE_TEST_PYTHON -m unittest discover -s .\mcp_server\tests -t .\mcp_server -q
Set-Location frontend; npm test; npm run lint; npm run build; Set-Location ..
python -I backend/scripts/build_symptom_parts.py --check
python -I backend/scripts/structure_knowledge.py --check
```

MCP 도구 설명을 바꾸면 `mcp_server`에서 `.venv\Scripts\python.exe -m scripts.export_contract`로 계약을 다시 생성한다.
