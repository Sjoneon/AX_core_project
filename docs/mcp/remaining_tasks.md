# 남은 작업과 미실행 검증

<!-- 개발자 직접 검토·수정 필요: 2026-10-16 이후 진행 상황 반영 -->

이 문서는 두 기록을 합친 것이다. 1절은 MCP 도구 보완의 남은 작업(2026-10-13 기준), 2절은 상담 흐름·카드 개편의 남은 작업(2026-10-16 기준)이다.

## 1. MCP 도구 보완 (2026-10-13 21:20 기준)

2026-10-15 갱신: 상담 기능의 현재 작업 순서는 [완료 계획](mcp_completion_plan.md)을 따른다. 제조사 지식 1,542건의 구조화는 [1단계 기록](manufacturer_data_structure.md), MCP 검색 연결·캐시·실제 임베딩 평가는 [2단계 기록](manufacturer_retrieval.md), 증상 분류·대화 상태·실제 해석 38개 평가는 [3단계 기록](symptom_interpretation.md), 최종 답변 구성 14개 평가는 [4단계 기록](grounded_answer_generation.md)에 남겼다. 5단계의 TR15WV5/UE 조건 확인·단계 진행·결과 이력은 [5단계 기록](approved_self_care.md)에 남겼다. 6단계의 비용·보증·공식 서비스 후속 연결은 [6단계 기록](repair_followup.md)에 남겼다. 7단계의 새 답변·저장 이력 표시, 과거 JSON 투영·중복 제거·DEV 원본 분리는 [7단계 기록](consultation_display.md)에 남겼다. 8단계의 실제 상담·답변 검토와 회귀 범위는 [8단계 기록](consultation_validation.md)에 남겼다. 분류·도구 성공과 답변 적합성, 수리 완료·자료 부족을 구분한다. 아래의 2026-10-13 기록은 당시 운영/환경 후속 항목이며, 상담 기능 전체 완료를 뜻하지 않는다.

당시 코드·테스트·문서 작업은 끝난 상태였고, 아래는 승인·외부 환경이 필요한 일과 개발 PC에서 실행하지 못한 검증이다. 운영 DB 마이그레이션, `terraform apply`, main 푸시는 승인을 받은 뒤 진행한다.

> [개발자 확인] 이 절은 2026-10-13 기록이며 이후 상태를 다시 확인하지 않았다. 예를 들어 `수리비_정규화_과정/`은 지금 저장소에 들어 있고(아래 1-2의 6번), 팀원 커밋 `421065a fix(deploy): migrate schema and enable MCP agent in production`이 1-2의 4·5번과 관련될 수 있다.

### 1-1. PR CI로 확인할 검증 (2026-10-13 환경 기록)

6단계 로컬 Java 286개 중 284개·Python 21개·JAR 빌드, 7단계 프론트엔드 22개·lint·build·로컬 Edge 표시 검사는 통과했다. 8단계 최종 회귀는 Java 305개 중 303개·MySQL 2개 건너뜀·JAR, Python MCP 21개·자료/평가 8개, 프론트엔드 24개·lint·build·Edge다. 실제 상담은 개발 세트 24개와 추가 표현 6개를 실행하고 분류·도구 결과와 실제 답변을 별도로 검토했다. 아래의 Node 미실행 표기는 당시 기록이며 MySQL 2개·Docker·Terraform·Linux 운영 검증은 별도다.

- `frontend`: `npm run lint`, `npm run build` (변경: `src/components/ToolResults.jsx` 비용 카드·안전 주의·안내 기준).
- `backend`: `gradlew mysqlSchemaTest mysqlTest`(MySQL 8.4에 V001→V005 적용 후 `ddl-auto=validate` 기동).
- Docker: `docker build mcp_server`(digest 고정 베이스), MCP 컨테이너 health 스모크, `ops/checks/test-proxy.py`(`/api/tools` 404, 429).
- `ops/tests` 중 `DeploymentTests`(Linux 전용. Windows에서는 `id -u` CRLF로 첫 줄에서 실패).
- MCP 계약 일치 검사(`scripts.export_contract` 후 `git diff --exit-code`).

실패하면 규칙·보안 검사를 완화하지 않고 원인을 고친다.

### 1-2. 승인·외부 조치가 필요한 일 (순서대로)

1. 원본 묶음 `LG_최종정리_B30_20261011/`은 로컬 Desktop에서 확보했고 5단계 UE 원문 대조에 사용했다. 별도 검토로 부품보유기간(제조일 기준)과 fact_id를 구조화하고 매니페스트를 갱신한다. 변환본 T5 기록의 식기세척기 5년(T5-004)·7년(T5-009 등) 충돌을 원본으로 해소한다.
2. Terraform: `terraform plan` 결과 검토(예상: ECR `homecare-prod/mcp`·수명 정책·로그 그룹 `/homecare/prod/mcp` 추가 3개, GitHub 배포·EC2 런타임 IAM 정책의 리소스 목록 변경 2개). 승인 후 `apply`.
3. GitHub `production` 변수 `ECR_MCP_REPOSITORY=homecare-prod/mcp` 추가.
4. 운영 RDS에 V002 → V003 → V004 → V005 적용(`ops/docs/rds_initialization.md` 절차). 이 코드가 main에 들어가기 전에 해야 한다(운영 `validate`).
5. 운영에서 `HOMECARE_AGENT_ENABLED=true`로 켜는 시점·방법 결정(현재 코드 기본 true이며 실제 운영 환경변수는 별도 확인).
6. 업로드한 `수리비_정규화_과정/` 폴더를 커밋할지 결정(당시 미추적. `CostTableSourceTests`는 이 폴더가 있으면 CSV와 `parts.json`을 대조하고, 없으면 건너뛴다).

### 1-3. 남은 개선 후보

- MCP 전이 의존성 해시 lock 파일(Linux에서 `pip-compile --generate-hashes` 등으로 생성 후 `pip install --require-hashes`).
- MCP Inspector 수동 확인(`mcp_server/README.md` 절차)과 실제 OpenAI 수동 E2E(`docs/guides/verification_guide.md` 3-1).

## 2. 상담 흐름·카드 개편 (2026-10-16 기준)

설계와 구현 내용은 [상담 흐름·카드 개편 구현 기록](consultation_card_flow.md), 결정과 이유는 [상담 설계 결정 기록](consultation_design_decisions.md)에 있다. 아래 상태는 커밋 `0dbb709`의 코드에서 확인했다.

| 작업 | 상태 | 확인한 근거 |
|---|---|---|
| 전국 센터 자료 구축([구현 기록 2.1](consultation_card_flow.md#21-전국-센터-자료)) | 미구현 | `homecare/centers.json`은 3건이고 `FindServiceCentersTool`은 `directory_status=NOT_INTEGRATED`와 공식 센터 찾기 연결만 반환한다. 센터 수집 스크립트는 `backend/scripts`에 없다. |
| 위험 의심(SUSPECT) 단계의 위험 확인 카드(구현 기록 2.2의 2번) | 미구현 | 의심 단계 응답에는 `card`가 없다(`McpIntegrationTests.burningSmellUsesAiAssessmentThenAsksAConfirmationQuestionWithoutMcp`). |
| 자동 선택 제품의 확인 질문 `PRODUCT_CONFIRM`(구현 기록 2.2의 3번) | 미구현 | 코드에서 `PRODUCT_CONFIRM` 카드 유형을 찾지 못했다. 같은 품목 제품이 여러 개일 때의 선택 카드(`PRODUCT_CHOICE`)는 구현돼 있다. |
| 위험 확정 후 요약 카드 연결 | 구현 | `AgentChatService.safety()`(커밋 `7f1c338`), `McpIntegrationTests.yesAndUnsureConfirmRiskAndConfirmedRiskCannotBeDismissedByAi` |
| 기존 원칙 문서 개정(구현 기록 2.4: `repair_followup.md`, `grounded_answer_generation.md`, `consultation_display.md`, `service_channel_operating_plan.md`) | 미반영으로 보임 | 네 문서 모두 개편 첫 커밋 `8b42182` 이전 커밋이 마지막 수정이다. [개발자 확인] |
| 근거가 없는 일반 증상 7개의 금액 표시 | 결정 필요 | 지금은 금액을 표시하지 않는다. 같은 품목 전체 범위로 바꿀지는 정하지 않았다. |
| 냉장고 오류 코드 안내의 공식 자료 충돌 | 결정 필요 | 설명서 9개는 "전원을 끄지 말고 서비스 센터에 연락", 코드별 LG 지원 페이지는 "전원 플러그를 뽑고 5분 후 다시 꽂기"를 안내한다. 지금은 코드별 지원 페이지를 따른다. |
