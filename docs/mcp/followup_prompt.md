# 남은 작업: HomeCare AI MCP 도구 보완 (2026-10-13 21:20 기준)

2026-10-14 갱신: 상담 기능의 현재 작업 순서는 [완료 계획](mcp_completion_plan.md)을 따른다. 제조사 지식 1,542건의 구조화는 [1단계 기록](manufacturer_data_structure.md), MCP 검색 연결·캐시·실제 임베딩 평가는 [2단계 기록](manufacturer_retrieval.md)에 남겼다. 3~8단계는 후속 작업이다. 아래의 2026-10-13 기록은 당시 운영/환경 후속 항목이며, 상담 기능 전체 완료를 뜻하지 않는다.

원래 작업 지시(1~12장)와 승인된 결정 1~9는 그대로 지킨다. 코드·테스트·문서 작업은 끝났고, 아래는 사용자 승인·외부 환경이 필요한 일과 이 PC에서 실행하지 못한 검증이다. 운영 DB 마이그레이션, `terraform apply`, main 푸시는 사용자 승인 없이 하지 않는다.

## 1. PR CI로 확인할 검증 (이 PC에 Node·Docker·Terraform·WSL 없음)
- `frontend`: `npm run lint`, `npm run build` (변경: `src/components/ToolResults.jsx` 비용 카드·안전 주의·안내 기준).
- `backend`: `gradlew mysqlSchemaTest mysqlTest`(MySQL 8.4에 V001→V005 적용 후 `ddl-auto=validate` 기동).
- Docker: `docker build mcp_server`(digest 고정 베이스), MCP 컨테이너 health 스모크, `ops/checks/test-proxy.py`(`/api/tools` 404, 429).
- `ops/tests` 중 `DeploymentTests`(Linux 전용. Windows에서는 `id -u` CRLF로 첫 줄에서 실패).
- MCP 계약 일치 검사(`scripts.export_contract` 후 `git diff --exit-code`).
- 실패하면 원인을 고치고 규칙·보안 검사를 완화하지 않는다.

## 2. 사용자 승인·조치가 필요한 일 (순서대로)
1. 원본 묶음 `LG_최종정리_B30_20261011/`(`knowledge_effective_B30.jsonl`, `query_B30.py`, `checksums_B30.json`) 위치 확인. 받으면 격리 폴더에서 스크립트를 읽고 검토한 뒤 부품보유기간(제조일 기준)과 fact_id를 구조화하고 매니페스트를 갱신한다. 변환본 T5 기록의 식기세척기 5년(T5-004)·7년(T5-009 등) 충돌을 원본으로 해소한다.
2. Terraform: `terraform plan` 결과 검토(예상: ECR `homecare-prod/mcp`·수명 정책·로그 그룹 `/homecare/prod/mcp` 추가 3개, GitHub 배포·EC2 런타임 IAM 정책의 리소스 목록 변경 2개). 승인 후 `apply`.
3. GitHub `production` 변수 `ECR_MCP_REPOSITORY=homecare-prod/mcp` 추가.
4. 운영 RDS에 V002 → V003 → V004 → V005 적용(`ops/docs/rds_initialization.md` 절차). 이 코드가 main에 들어가기 전에 해야 한다(운영 `validate`).
5. 운영에서 `HOMECARE_AGENT_ENABLED=true`로 켜는 시점·방법 결정(현재 기본 false, MCP 컨테이너만 실행).
6. 업로드한 `수리비_정규화_과정/` 폴더를 커밋할지 결정(현재 미추적. `CostTableSourceTests`는 이 폴더가 있으면 CSV와 `parts.json`을 대조하고, 없으면 건너뛴다).

## 3. 남은 개선 후보
- MCP 전이 의존성 해시 lock 파일(Linux에서 `pip-compile --generate-hashes` 등으로 생성 후 `pip install --require-hashes`).
- MCP Inspector 수동 확인(`mcp_server/README.md` 절차)과 실제 OpenAI 수동 E2E(`docs/guides/verification_guide.md` 3-1).
