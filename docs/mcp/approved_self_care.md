# 5단계: 조건 확인과 승인된 자가조치 진행

2026-10-14 · 검토한 실제 자료 1개 경로에 한정한다.

`TR15WV5 / UE / 세탁물 뭉침`의 조건 확인 → 세탁물 정리 → 탈수 재시도 → 해결 또는 공식 상담 안내를 구현했다. 등록 제품과 전체 모델명이 일치해야 하며, 다른 UE 분기·TR15MV5·SKU 접미사·진동 자료에는 적용하지 않는다.

## 원문과 검토 범위

- B30 `knowledge_effective_B30.jsonl`의 `T2-065`, 분기 `TR15WV5-T2-065-bundled`를 읽었다. 레코드 SHA-256은 `ae71963120069770989afb6e86b4b7848c5d2925694317a25afc6a066ceb011c`이다. 현재 QA·적용 갱신·보완 문서는 이 레코드에 없다. A/S 필요 여부 공란은 안전함 또는 서비스 불필요로 해석하지 않는다.
- B30의 체크섬으로 지식 원본·설명서 목록·판매 코드 매핑·검토 링크 파일을 확인했다. `TR15WV5`와 설명서 `MFL71831233`을 정확히 대조했다.
- [공식 설명서 ZIP](https://gscs-b2c.lge.com/downloadFile?fileId=TmhEiHqWvmy9cV8lOIlxQ)을 내려받아 B30에 기록된 SHA-256 `c3c9c3b31d2e79da40a7e029a97f4b8f820a5b1b38c24bb71ad46030f51e28e3`과 일치하는 것을 확인했다. 검색 브라우저의 HTML 접근 실패를 원문 검토 성공으로 계산하지 않았다.
- [UE 오류 분기](https://gscs-manual.lge.com/DFZ/MFL71831233/ko-kr/main.html#topic_44), 안전 주의 `topic_9`, 문·정지 조건 `topic_16`, 조작부 `topic_20`, 탈수단독 `topic_23`의 원문을 대조했다. ZIP의 원문 조작부 SVG를 화면으로 확인했다. 실물 일치 여부는 각 상담에서 별도로 확인한다.

승인 범위는 **HomeCare의 조건부 실행 경로 검토**다. 제조사·정비기사 승인이나 실물 시험 완료로 표시하지 않는다. `review.manufacturerApproval=false`이며 원본 B30의 `standalone_execution_allowed=false`, 후보 검색 정책과 모든 원본 바이트를 유지했다. `approved_model_codes`를 승인 계획으로 일괄 변환하지 않았다.

재생성은 [build_approved_plans.py](../../backend/scripts/build_approved_plans.py)를 사용한다. 검토한 레코드와 공식 ZIP 해시가 바뀌면 재검토 없이 생성하지 않는다.

```powershell
$b30Root='C:\Users\Roneon\Desktop\LG 제품명 및 관련정보\LG_최종정리_B30_20261011'
.\mcp_server\.venv\Scripts\python.exe .\backend\scripts\build_approved_plans.py `
  --b30 $b30Root --manual-zip .\backend\build\TR15-manual.zip
```

ZIP은 검토용 로컬 파일이며 저장소에 포함하지 않는다. 앱은 패키지의 [approved-plans.json](../../backend/src/main/resources/homecare/approved-plans.json)을 사용한다. 데이터 버전은 `B30-COST-V3-20261014-R5`, 승인 계획은 1건이고 검색용 원본·구조화 자료는 각각 1,542건이다.

## 조건과 상태

다음 6개 질문을 하나씩 표시한다. 전부 `YES`여야 첫 단계가 나온다.

1. 제품 라벨의 정확한 모델명.
2. 현재 UE 표시 직접 관찰.
3. 세탁 가능한 일반 의류 여러 장의 뭉침. 금지·특수 세탁물, 다른 원인 분기, 용량 초과 제외.
4. 위험 징후 없음과 주변·손 상태.
5. 세탁통 완전 정지, 온전한 문, 안전한 접근.
6. 원문 그림과 실제 조작부 일치, 필요한 표시·버튼과 버튼 잠금 상태.

미확인·거절 응답에서는 실행 단계를 반환하지 않는다. 사용자에게 조건을 충족하도록 조작을 강요하지 않으며, 공식 상담 안내로 끝낸다. 상태의 `selfCare.conditions/results`는 데이터 버전·계획 ID/개정·제품 ID·전체 모델명에 묶인다. 제품 수정/재확정, 증상·오류 변경, 새 위험 감지는 확인 값을 초기화한다.

`DiagnosticPlans`의 동일한 판정을 도구, 자연어 완료 처리, 최종 답변 근거 검증에 사용한다. 조건 확인 전의 “이미 했어요”나 모델이 만든 완료 ID로는 단계를 건너뛰지 않는다. 조건을 확인한 뒤에는 현재 표시한 승인 단계만 완료 처리한다. 확실한 짧은 해결/미해결 응답 또는 실제 단계와 정확히 일치하는 수행 보고만 반영한다.

| 결과 | 반환 상태·사유 | 후속 안내 |
|---|---|---|
| 조건 대기 | `NEEDS_INPUT / PRECONDITIONS_PENDING` | 질문 1개, `steps=[]` |
| 조건 거절 | `OK / PRECONDITION_NOT_MET` | 중지·공식 상담 |
| 조건 모름 | `OK / PRECONDITION_UNCONFIRMED` | 확인 불가·공식 상담 |
| 단계 진행 | `OK`, 단계 최대 1개 | 이미 완료한 단계 제외 |
| 해결 | `OK / RESOLVED`, `closed=true` | 종료, 새 증상은 새 상담 |
| 단계 모두 미해결 | `OK / ALL_DONE` | 추가 조작 없이 공식 경로 |
| 실행 미승인 | `NOT_VERIFIED / NOT_VERIFIED` | 근거 후보·공식 상담, 조치 없음으로 확정하지 않음 |
| 검토 결과 조치 없음 | `OK / NO_SELF_CARE` | 명시적 `disposition=NO_SELF_CARE` 계획에만 적용 |

현재 실제 자료에는 `NO_SELF_CARE` 계획이 없다. 해당 분기는 `TEST_ONLY` 자료로 상태 구분을 검증했다.

## 응답과 이력 계약

- `POST /api/conversations/{id}/precondition-answer`: `question_id`, `plan_scope`, `answer=YES|NO|UNSURE`, `context_version`.
- `POST /api/conversations/{id}/step-answer`: `step_code`, `answer=YES|NO`, `context_version`.
- 두 API는 갱신한 진단 도구를 담은 `ChatResponse`를 반환한다. 기존 step-answer의 단독 `ToolEnvelope` 응답을 변경했고 화면 소비 코드도 함께 수정했다.
- 소유권·버전·현재 질문/단계·계획 범위를 확인한 뒤 상태, 사용자 응답, 다음 안내, 도구 이력을 같은 트랜잭션에 저장한다. 저장 실패 시 조건·결과·버전도 롤백된다.
- 자연어의 정확한 “예/네/아니요/모르겠어요”는 현재 이력의 조건 질문에만 연결한다. 자유로운 문장으로 조건을 추측해서 승인하지 않는다. 자연어 단계 결과도 별도 트랜잭션 경로로 저장한다.
- 화면은 응답을 사용자·상담 메시지로 추가한다. 로컬 카드 교체 상태에만 다음 단계를 보관하지 않는다. 저장 이력 조회에서도 질문·결과·다음 단계가 복원된다. 오래된 버튼은 버전 충돌로 거부한다.

9개 MCP 도구는 계속 읽기 전용이다. 상태 쓰기 API를 모델의 MCP 도구로 노출하지 않는다. 승인 경로의 질문·조치 문장은 서버가 검토된 문구를 그대로 반환하고 `execution_source=SELF_CARE_POLICY`, `selection_source=POLICY_FALLBACK`으로 기록한다. 이를 AI의 도구 선택 성공이나 자유로운 조치 생성으로 계산하지 않는다.

## 검증 기록

자동 검사는 실제 등록 계획, Spring HTTP/JWT API, H2 저장 이력, 실제 FastMCP HTTP를 사용한다. OpenAI는 기존 스크립트 응답 서버이며 실제 제품 조작·실제 모델 품질 평가를 대신하지 않는다.

- 조건 6개 확인 → 1단계 `NO` → 2단계 `YES`: 해결 상태와 총 18개 대화 메시지 저장, 조회 결과 일치.
- 두 단계 `NO`: `ALL_DONE`, 추가 단계 없음, 공식 후속 경로.
- 조건 `NO/UNSURE`, 순서/범위 위조, 이전 버전, 타 사용자 요청: 실행·이력 추가 차단.
- 자연어 조건 응답, 승인 전 완료 주장 차단, 승인 후 이미 수행한 단계 제외·해결 종료.
- 같은 제품 수정·재확정, 새 위험 감지, 다른 모델·색상 별칭·SKU 접미사: 이전 확인 재사용 차단.
- 조건 및 단계 결과 저장 중 이력 실패: 상태·버전·이력 롤백.
- 실제 승인 단계라도 조건 미확인 또는 다음 단계 위조 시 `AnswerContext`가 거부.

최종 검사(2026-10-15): Java 266개 중 264개 통과·MySQL 마이그레이션 2개 건너뜀, `bootJar` 성공. Python 20개, 프론트엔드 10개·lint·build 통과, MCP discovery 계약 재생성. 생성 스크립트의 바이트 재현성과 손상된 공식 ZIP의 거부·기존 데이터 보존도 확인했다. 명령은 [검증 가이드](../guides/verification_guide.md)에 있다. 실물 시험, 운영 DB 마이그레이션·배포, 비용·보증·센터 도구의 자동 후속 선택은 이 단계의 완료 범위에 포함하지 않는다.
