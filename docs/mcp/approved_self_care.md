# 5단계: 조건 확인과 승인된 자가조치 진행

> 검토한 실제 자료 1개 경로(`TR15WV5 / UE / 세탁물 뭉침`)의 조건 확인과 승인된 자가조치 구현 기록
> 작성일: 2026-10-14 · 데이터 버전: `B30-COST-V3-20261014-R5`

**구현 범위**

- 흐름: 조건 확인 → 세탁물 정리 → 탈수 재시도 → 해결 또는 공식 상담 안내
- 적용 조건: 등록 제품과 전체 모델명 일치
- 적용 제외: 다른 UE 분기, TR15MV5, SKU 접미사, 진동 자료

**목차**

1. [원문과 검토 범위](#1-원문과-검토-범위)
2. [조건과 상태](#2-조건과-상태)
3. [응답과 이력 계약](#3-응답과-이력-계약)
4. [검증 기록](#4-검증-기록)

---

## 1. 원문과 검토 범위

### 1.1 검토한 원문

- 지식 원본: B30 `knowledge_effective_B30.jsonl`의 `T2-065`, 분기 `TR15WV5-T2-065-bundled`
    - 레코드 SHA-256: `ae71963120069770989afb6e86b4b7848c5d2925694317a25afc6a066ceb011c`
    - 이 레코드에 없는 것: 현재 QA·적용 갱신·보완 문서
    - A/S 필요 여부 공란: 안전함이나 서비스 불필요로 해석하지 않음
- B30 체크섬 대조: 지식 원본, 설명서 목록, 판매 코드 매핑, 검토 링크 파일
    - `TR15WV5`와 설명서 `MFL71831233` 정확히 대조
- [공식 설명서 ZIP][manual-zip]: B30에 기록된 SHA-256 `c3c9c3b31d2e79da40a7e029a97f4b8f820a5b1b38c24bb71ad46030f51e28e3`과 일치
    - 검색 브라우저의 HTML 접근 실패는 원문 검토 성공으로 계산하지 않음
- 원문 대조 범위: [UE 오류 분기][ue-branch], 안전 주의 `topic_9`, 문·정지 조건 `topic_16`, 조작부 `topic_20`, 탈수단독 `topic_23`
    - ZIP의 원문 조작부 SVG 화면 확인
    - 실물 일치 여부는 상담마다 별도 확인

### 1.2 승인 범위

- 범위: HomeCare의 조건부 실행 경로 검토
- 제조사·정비기사 승인이나 실물 시험 완료로 표시하지 않음
- 유지한 값
    - `review.manufacturerApproval=false`
    - 원본 B30의 `standalone_execution_allowed=false`
    - 후보 검색 정책과 모든 원본 바이트
- `approved_model_codes`를 승인 계획으로 일괄 변환하지 않음

### 1.3 재생성

- 스크립트: [build_approved_plans.py][build-plans]
- 검토한 레코드나 공식 ZIP 해시가 바뀌면 재검토 전에는 생성하지 않음

```powershell
$b30Root='C:\Users\Roneon\Desktop\LG 제품명 및 관련정보\LG_최종정리_B30_20261011'
.\mcp_server\.venv\Scripts\python.exe .\backend\scripts\build_approved_plans.py `
  --b30 $b30Root --manual-zip .\backend\build\TR15-manual.zip
```

- ZIP: 검토용 로컬 파일, 저장소에 포함하지 않음
- 앱이 읽는 파일: 패키지의 [approved-plans.json][approved-plans]
- 데이터 규모: 승인 계획 1건, 검색용 원본·구조화 자료 각 1,542건

---

## 2. 조건과 상태

### 2.1 조건 질문

아래 6개 질문을 하나씩 표시합니다. 모두 `YES`여야 첫 단계가 나옵니다.

1. 제품 라벨의 정확한 모델명
2. 현재 UE 표시 직접 관찰
3. 세탁 가능한 일반 의류 여러 장의 뭉침(금지·특수 세탁물, 다른 원인 분기, 용량 초과 제외)
4. 위험 징후 없음과 주변·손 상태
5. 세탁통 완전 정지, 온전한 문, 안전한 접근
6. 원문 그림과 실제 조작부 일치, 필요한 표시·버튼과 버튼 잠금 상태

### 2.2 조건 처리 규칙

- 미확인·거절 응답: 실행 단계를 반환하지 않고 공식 상담 안내로 종료
- 조건을 충족하도록 사용자에게 조작을 강요하지 않음
- `selfCare.conditions/results`의 묶음 기준: 데이터 버전, 계획 ID/개정, 제품 ID, 전체 모델명
- 확인 값 초기화 조건: 제품 수정/재확정, 증상·오류 변경, 새 위험 감지

### 2.3 단계 완료 판정

- `DiagnosticPlans`의 같은 판정을 도구, 자연어 완료 처리, 최종 답변 근거 검증에 공통 사용
- 조건 확인 전의 "이미 했어요"나 모델이 만든 완료 ID로는 단계를 건너뛰지 않음
- 조건 확인 후에는 현재 표시한 승인 단계만 완료 처리
- 반영하는 응답: 확실한 짧은 해결/미해결 응답, 실제 단계와 정확히 일치하는 수행 보고

### 2.4 결과별 상태

| 결과 | 반환 상태·사유 | 후속 안내 |
| --- | --- | --- |
| 조건 대기 | `NEEDS_INPUT / PRECONDITIONS_PENDING` | 질문 1개, `steps=[]` |
| 조건 거절 | `OK / PRECONDITION_NOT_MET` | 중지·공식 상담 |
| 조건 모름 | `OK / PRECONDITION_UNCONFIRMED` | 확인 불가·공식 상담 |
| 단계 진행 | `OK`, 단계 최대 1개 | 이미 완료한 단계 제외 |
| 해결 | `OK / RESOLVED`, `closed=true` | 종료, 새 증상은 새 상담 |
| 단계 모두 미해결 | `OK / ALL_DONE` | 추가 조작 없이 공식 경로 |
| 실행 미승인 | `NOT_VERIFIED / NOT_VERIFIED` | 근거 후보·공식 상담, 조치 없음으로 확정하지 않음 |
| 검토 결과 조치 없음 | `OK / NO_SELF_CARE` | 명시적 `disposition=NO_SELF_CARE` 계획에만 적용 |

- 현재 실제 자료에는 `NO_SELF_CARE` 계획 없음
- 해당 분기는 `TEST_ONLY` 자료로 상태 구분 검증

---

## 3. 응답과 이력 계약

### 3.1 API

- `POST /api/conversations/{id}/precondition-answer`: `question_id`, `plan_scope`, `answer=YES|NO|UNSURE`, `context_version`
- `POST /api/conversations/{id}/step-answer`: `step_code`, `answer=YES|NO`, `context_version`
- 두 API의 응답: 갱신한 진단 도구를 담은 `ChatResponse`
    - 기존 step-answer의 단독 `ToolEnvelope` 응답에서 변경, 화면 소비 코드도 함께 수정

### 3.2 저장

- 소유권·버전·현재 질문/단계·계획 범위를 확인한 뒤 상태, 사용자 응답, 다음 안내, 도구 이력을 같은 트랜잭션에 저장
- 저장 실패 시 조건·결과·버전도 롤백
- 자연어의 정확한 "예/네/아니요/모르겠어요"는 현재 이력의 조건 질문에만 연결
- 자유로운 문장으로 조건을 추측해 승인하지 않음
- 자연어 단계 결과도 별도 트랜잭션 경로로 저장

### 3.3 화면

- 응답을 사용자·상담 메시지로 추가하며, 다음 단계를 로컬 카드 교체 상태에만 보관하지 않음
- 저장 이력 조회에서도 질문·결과·다음 단계 복원
- 오래된 버튼은 버전 충돌로 거부

### 3.4 MCP 도구와의 관계

- 9개 MCP 도구는 계속 읽기 전용
- 상태 쓰기 API는 모델의 MCP 도구로 노출하지 않음
- 승인 경로의 질문·조치 문장: 서버가 검토된 문구를 그대로 반환하고 `execution_source=SELF_CARE_POLICY`, `selection_source=POLICY_FALLBACK`으로 기록
- 이 기록은 AI의 도구 선택 성공이나 자유로운 조치 생성으로 계산하지 않음

---

## 4. 검증 기록

### 4.1 검사 환경

- 사용한 구성: 실제 등록 계획, Spring HTTP/JWT API, H2 저장 이력, 실제 FastMCP HTTP
- OpenAI: 기존 스크립트 응답 서버
- 이 검사가 대신하지 않는 것: 실제 제품 조작, 실제 모델 품질 평가

### 4.2 검사 항목

- 조건 6개 확인 → 1단계 `NO` → 2단계 `YES`: 해결 상태와 총 18개 대화 메시지 저장, 조회 결과 일치
- 두 단계 `NO`: `ALL_DONE`, 추가 단계 없음, 공식 후속 경로
- 조건 `NO/UNSURE`, 순서/범위 위조, 이전 버전, 타 사용자 요청: 실행·이력 추가 차단
- 자연어 조건 응답, 승인 전 완료 주장 차단, 승인 후 이미 수행한 단계 제외·해결 종료
- 같은 제품 수정·재확정, 새 위험 감지, 다른 모델·색상 별칭·SKU 접미사: 이전 확인 재사용 차단
- 조건 및 단계 결과 저장 중 이력 실패: 상태·버전·이력 롤백
- 실제 승인 단계라도 조건 미확인 또는 다음 단계 위조 시 `AnswerContext`가 거부

### 4.3 최종 검사(2026-10-15)

- Java 266개 중 264개 통과, MySQL 마이그레이션 2개 건너뜀, `bootJar` 성공
- Python 20개, 프론트엔드 10개·lint·build 통과
- MCP discovery 계약 재생성
- 생성 스크립트의 바이트 재현성, 손상된 공식 ZIP 거부와 기존 데이터 보존 확인
- 이 단계의 완료 범위 밖: 실물 시험, 운영 DB 마이그레이션·배포, 비용·보증·센터 도구의 자동 후속 선택

[manual-zip]: https://gscs-b2c.lge.com/downloadFile?fileId=TmhEiHqWvmy9cV8lOIlxQ
[ue-branch]: https://gscs-manual.lge.com/DFZ/MFL71831233/ko-kr/main.html#topic_44
[build-plans]: ../../backend/scripts/build_approved_plans.py
[approved-plans]: ../../backend/src/main/resources/homecare/approved-plans.json
