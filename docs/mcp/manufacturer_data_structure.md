# 제조사 자료 구조화 — 1단계 구현·검증 기록

작성일: 2026-10-14 (Asia/Seoul). 대상 데이터 버전: `B30-COST-V3-20261014-R4`.

## 어떤 데이터를 구조화했는가

대상은 [knowledge.json](../../backend/src/main/resources/homecare/knowledge.json)의 제조사 지식 1,542건이다. LG B30 자료에서 이미 서비스로 가져온 사실 레코드이며, 제품 목록 52개나 비용 표 32개를 다시 정규화하는 작업이 아니다.

| 품목 | 원본·구조화 레코드 수 |
|---|---:|
| 세탁기 | 520 |
| 건조기 | 274 |
| 냉장고 | 360 |
| 식기세척기 | 388 |
| 합계 | 1,542 |

실제 원문은 두 형태다. 446건은 CSV 표에서 가져온 `record.content` 형식으로 제품 정보(T1), 오류(T2), 증상(T3), 안전(T4), 보증·요금(T5)이 들어 있다. 나머지 1,096건은 `record`에 증상·원인별 분기·조치 요약·순서·선행 조건·안전·보증·부품 참고·적용/출처 검토를 직접 저장한다. 같은 고장이나 설명서의 여러 가지가 별도 레코드여서 1,542건을 고유 고장 종류나 승인 조치 수로 계산하지 않는다.

각 레코드의 `text`는 일반 문장이 아니라 다음 요소를 함께 담은 B30 JSON 래퍼였다.

- `record`: 제조사에서 확보한 사실과 조건, 출처.
- `current_QA_review`, `latest_applicability_update`: 현재 검토와 적용 보완.
- `effective_model_lists`: 검토 후 적용·조건부·제외 모델 목록.
- `procedure_supplements`: 원래 절차와 보완 자료의 연결.
- 원본 해시·수집 시각·필드 검증 이력·`use_policy`: 내부 검토·무결성·사용 제한.

구조화에는 로컬 원문을 사용했다. B30 전체 `knowledge_effective_B30.jsonl` 1,659건과 체크섬 원본을 새로 확보하거나 공식 웹페이지를 재수집하지 않았다. [기존 검수 보고서](../reviews/product_data_usability_review.md)의 누락 117건(김치냉장고 97건 포함)은 이 작업으로 복원되지 않는다. 기존 `models.json`, 비용 표, 보증 계산, 승인 계획, 사용자 DB도 이 구조화의 입력 변환 대상이 아니다.

## 원본·검색·화면 데이터의 분리

원본 `knowledge.json`의 바이트를 보존했다. SHA-256은 기존 `5ec8d44ffb4828d54a273ebef0ac1cfd302c1f2415769f50ceb978e2d800a07d` 그대로다. QA 이력·해시·사용 정책은 원본에서 대조할 수 있다.

[structure_knowledge.py](../../backend/scripts/structure_knowledge.py)가 원본을 읽어 [knowledge-structured.json](../../backend/src/main/resources/homecare/knowledge-structured.json)을 생성한다. 원본 1개당 구조화 1개이며 ID를 유지한다.

| 구조화 필드 | 역할 |
|---|---|
| `symptoms`, `errorCodes`, `causes` | 확보된 증상·오류 코드·원인 설명 |
| `actionDescriptions`, `branches`, `procedureSteps` | 조치 후보, 분기별 원인/기능/모델 조건, 원문 순서. 실행 승인 아님 |
| `preconditions`, `warnings`, `constraints` | 선행 조건·주의사항·실물/그림/전원/기능 확인 제약 |
| `modelApplicability` | 유효 적용 목록 또는 원래 모델 범위. 공란을 공통 승인으로 바꾸지 않음 |
| `sources` | 공식 URL·자료 역할·본문 위치. 해시와 DOM 선택자는 화면용 출처에서 제거 |
| `relationships` | 보완·원래 절차·검토·안전/설치 참조를 사실 ID로 연결 |
| `referenceDetails` | 제품 사양·부품 호환·정책·숫자 참고를 증상/조치와 분리 |
| `fieldOrigins`, `unknownFields` | 내부 원문 대조 경로와 미확인 필드. 사용자 본문에 넣지 않음 |
| `searchText` | 검색용으로 선택한 설명·분기·조건·주의 문장. 래퍼 키·해시·수집 이력 제외 |
| `displayTitle`, `displayText` | 사용자 표시 제목과 증상·원인·조건·주의사항. 조치 후보는 별도 필드 |

검토용 QA 전체나 `decision_summary`를 검색 본문으로 평탄화하지 않는다. 현재 QA의 구체적인 선행 조건·주의사항은 원문에 보충하고, 기능/실물 제약은 원본·현재 검토·적용 보완으로 나누어 보존한다. 빈 QA 배열로 원래 조건·단계를 지우지 않는다. 과거 `source_variants`의 절차를 현재 절차로 끌어오지 않고 `unknown_fields`의 공란을 정상/안전/해결로 추론하지 않는다. 합성된 `user_phrases`도 제조사 원문 근거로 승격하지 않는다.

## 원문 대조 사례

| 원문 ID | 구조화 결과·유지한 조건 |
|---|---|
| `B17-MFL71792818-topic_66-009` | `FX24ENER/FX24KNTR`의 “소음 또는 진동이 심해요.”. 고정볼트·수평·펌프 작동 3분기를 유지. 펌프 분기의 `turbo_optional`과 실제 기능·상황 확인 조건 보존 |
| `T2-003` | `F24WDWPR`의 `OE`, 의미·원인·조치 후보와 배수 호스 끝 높이 1 m 설치 조건을 분리. 이 수치를 다른 세탁기에 전용하지 않음 |
| `T3-001` | 현재 설명서로 대조한 잔수/필터 절차, 1 m 조건, 뜨거운 물·재조립 누수 주의 보존. 미확인 `escalate_when`과 과거 공통 요약을 현재 안내에 추가하지 않음 |
| `B17-MFL71995305-topic_39-procedure` | 원래 청소 2단계, 전원 분리·급수 밸브 차단, 그림 확인 및 재조립 순서 부족 조건 보존 |
| `B18-MFL71995305-inlet-reassembly-supplement` | 보완 5단계는 별도 문서로 유지. 원래 2단계를 덮어쓰거나 7단계로 합치지 않음. 보완의 실물/그림/패킹/급수 연결 조건도 보존 |
| `B19-MFL05882501-topic_37-procedure` | `G646GBB021/G646GBB031` 제외 목록을 유지. 모델 목록이나 원문 존재가 조치 승인으로 바뀌지 않음 |

각 ID는 원본의 `text` 래퍼와 구조화 파일에서 대조할 수 있다. 대표 증상·조건 필드는 `fieldOrigins`에 원문 경로도 기록한다. 공식 출처 URL을 유지한 것이며 해당 페이지의 최신 내용이나 실제 기기 조작을 이번에 다시 검증한 것은 아니다.

## 런타임과 결과 계약

[ToolDataCatalog.java](../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java)는 원본과 구조화 파일을 모두 무결성 검사 후 읽는다. 구조화 스키마·건수·ID 집합·핵심 메타데이터·원본 해시 참조를 대조한다. 원본 모델 적용 판정은 기존 `KnowledgeOverlay`를 계속 사용한다.

[SearchKnowledgeTool.java](../../backend/src/main/java/com/project/backend/mcp/tool/handler/SearchKnowledgeTool.java)는 구조화 `searchText`로 단어 점수를 계산하고 [KnowledgeDocument.java](../../backend/src/main/java/com/project/backend/mcp/tool/KnowledgeDocument.java)의 화면/후보 필드만 반환한다. `content`에는 `displayText`를 넣는다. 절차 보완과 자가검진의 `references`에도 같은 투영을 사용하므로 원본 래퍼 JSON이 반환되지 않는다. 기존 프론트엔드의 `title/content` 소비와 호환된다.

`action_descriptions`, `branches`, `requirements`, `model_applicability`는 검토 가능한 후보·제약이다. 승인된 실행 단계가 아니다. 모든 구조화 문서는 `is_action_approved=false`이며 `approved-plans.json=[]`와 `NOT_VERIFIED`를 유지한다. 도구 입력과 공통 Envelope는 유지하고 추가 결과 필드를 [MCP 계약](mcp-tools.contract.json)의 `knowledge_result_contract`에 기록했다.

이 단계는 임베딩/RAG 도입이나 증상 검색 품질 통과가 아니다. 단어 점수, 안전 문서 우선순위, 모델 미판정 및 보완 자료의 실제 적용 필터는 2단계에서 별도로 평가해야 한다. 화면의 필드별 표시·중복 제거·기존 저장 상담 JSON 처리는 7단계다. 실행 중인 서비스 재시작·운영 배포는 하지 않았다.

## 생성·검증 방법

저장소 루트에서 실행한다.

```powershell
python -I backend/scripts/structure_knowledge.py
python -I backend/scripts/structure_knowledge.py --check
python -I -m unittest discover -s backend/scripts -p test_structure_knowledge.py -v
```

생성기는 원본 해시가 기존 매니페스트와 일치해야 실행한다. 원본을 읽기만 하고 구조화 파일·매니페스트의 버전·해시·건수·생성 경로를 갱신한다. `--check`는 재생성 결과와 저장된 파일을 비교하며 수정하지 않는다. 수리비 생성기는 현재 데이터 버전을 유지하고 구조화 파일의 해시도 포함하도록 갱신했다. CI에도 구조화 재현 검사를 연결했다.

검증 결과:

- 생성기 5개 검사 통과: 원본 바이트 보존·결정적 재생성·해시 불일치 차단·진동 분기·빈 QA 보충·과거/미확인 필드 제외·전체 본문 메타데이터 제외.
- 백엔드 관련 검사 통과: 자료 무결성·원문/구조화 연결·도구 결과·모델 제외/색상 공통 자료 제한·실행 승인 미확인·위험 규칙·실제 FastMCP HTTP 경로.
- Python MCP 20개 검사 통과, 실제 9개 도구 discovery에서 계약 재생성.

최종 백엔드 검사 수는 아래 계획서와 해당 실행의 `backend/build/test-results/test` 보고서에 기록한다. 실제 OpenAI 모델의 증상 검색·답변 품질, 브라우저 표시와 기존 저장 상담은 이번 검사로 통과했다고 주장하지 않는다.
