# 제조사 자료 구조화 — 1단계 구현·검증 기록

> 제조사 지식 1,542건의 원본 보존, 검색·화면용 구조화 자료 생성 기록
> 작성일: 2026-10-14 (Asia/Seoul) · 대상 데이터 버전: `B30-COST-V3-20261014-R4`

**목차**

1. [구조화 대상](#1-구조화-대상)
2. [원본·검색·화면 데이터의 분리](#2-원본검색화면-데이터의-분리)
3. [원문 대조 사례](#3-원문-대조-사례)
4. [런타임과 결과 계약](#4-런타임과-결과-계약)
5. [생성·검증 방법](#5-생성검증-방법)

---

## 1. 구조화 대상

### 1.1 대상 자료

- 대상: [knowledge.json][knowledge-json]의 제조사 지식 1,542건
- LG B30 자료에서 이미 서비스로 가져온 사실 레코드
- 대상이 아닌 것: 제품 목록 52개, 비용 표 32개의 재정규화

| 품목 | 원본·구조화 레코드 수 |
| --- | ---: |
| 세탁기 | 520 |
| 건조기 | 274 |
| 냉장고 | 360 |
| 식기세척기 | 388 |
| 합계 | 1,542 |

### 1.2 원문 형태

| 형태 | 건수 | 내용 |
| --- | ---: | --- |
| CSV 표에서 가져온 `record.content` | 446 | 제품 정보(T1), 오류(T2), 증상(T3), 안전(T4), 보증·요금(T5) |
| `record` 직접 저장 | 1,096 | 증상, 원인별 분기, 조치 요약, 순서, 선행 조건, 안전, 보증, 부품 참고, 적용/출처 검토 |

- 같은 고장이나 설명서의 여러 가지가 별도 레코드이므로 1,542건을 고유 고장 종류나 승인 조치 수로 계산하지 않음

### 1.3 B30 JSON 래퍼의 구성

각 레코드의 `text`는 일반 문장이 아니라 아래 요소를 함께 담은 B30 JSON 래퍼입니다.

- `record`: 제조사에서 확보한 사실과 조건, 출처
- `current_QA_review`, `latest_applicability_update`: 현재 검토와 적용 보완
- `effective_model_lists`: 검토 후 적용·조건부·제외 모델 목록
- `procedure_supplements`: 원래 절차와 보완 자료의 연결
- 원본 해시·수집 시각·필드 검증 이력·`use_policy`: 내부 검토·무결성·사용 제한

### 1.4 범위 밖

- 사용한 원문: 로컬 원문
- 새로 확보하지 않은 것: B30 전체 `knowledge_effective_B30.jsonl` 1,659건, 체크섬 원본, 공식 웹페이지 재수집
- 복원되지 않는 것: [기존 검수 보고서][usability-review]의 누락 117건(김치냉장고 97건 포함)
- 입력 변환 대상이 아닌 것: 기존 `models.json`, 비용 표, 보증 계산, 승인 계획, 사용자 DB

---

## 2. 원본·검색·화면 데이터의 분리

### 2.1 원본 보존

- 원본 `knowledge.json`의 바이트 보존
- SHA-256: 기존 `5ec8d44ffb4828d54a273ebef0ac1cfd302c1f2415769f50ceb978e2d800a07d` 그대로
- QA 이력·해시·사용 정책은 원본에서 대조 가능

### 2.2 구조화 파일

- 생성기: [structure_knowledge.py][structure-script]
- 결과: [knowledge-structured.json][structured-json]
- 원본 1개당 구조화 1개, ID 유지

| 구조화 필드 | 역할 |
| --- | --- |
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

### 2.3 구조화 규칙

- 검토용 QA 전체나 `decision_summary`는 검색 본문으로 평탄화하지 않음
- 현재 QA의 구체적인 선행 조건·주의사항은 원문에 보충
- 기능/실물 제약은 원본·현재 검토·적용 보완으로 나누어 보존
- 빈 QA 배열로 원래 조건·단계를 지우지 않음
- 과거 `source_variants`의 절차를 현재 절차로 끌어오지 않음
- `unknown_fields`의 공란을 정상/안전/해결로 추론하지 않음
- 합성된 `user_phrases`는 제조사 원문 근거로 승격하지 않음

---

## 3. 원문 대조 사례

| 원문 ID | 구조화 결과·유지한 조건 |
| --- | --- |
| `B17-MFL71792818-topic_66-009` | `FX24ENER/FX24KNTR`의 “소음 또는 진동이 심해요.”. 고정볼트·수평·펌프 작동 3분기를 유지. 펌프 분기의 `turbo_optional`과 실제 기능·상황 확인 조건 보존 |
| `T2-003` | `F24WDWPR`의 `OE`, 의미·원인·조치 후보와 배수 호스 끝 높이 1 m 설치 조건을 분리. 이 수치를 다른 세탁기에 전용하지 않음 |
| `T3-001` | 현재 설명서로 대조한 잔수/필터 절차, 1 m 조건, 뜨거운 물·재조립 누수 주의 보존. 미확인 `escalate_when`과 과거 공통 요약을 현재 안내에 추가하지 않음 |
| `B17-MFL71995305-topic_39-procedure` | 원래 청소 2단계, 전원 분리·급수 밸브 차단, 그림 확인 및 재조립 순서 부족 조건 보존 |
| `B18-MFL71995305-inlet-reassembly-supplement` | 보완 5단계는 별도 문서로 유지. 원래 2단계를 덮어쓰거나 7단계로 합치지 않음. 보완의 실물/그림/패킹/급수 연결 조건도 보존 |
| `B19-MFL05882501-topic_37-procedure` | `G646GBB021/G646GBB031` 제외 목록을 유지. 모델 목록이나 원문 존재가 조치 승인으로 바뀌지 않음 |

- 대조 위치: 각 ID의 원본 `text` 래퍼와 구조화 파일
- 대표 증상·조건 필드는 `fieldOrigins`에 원문 경로도 기록
- 공식 출처 URL은 유지했으며, 해당 페이지의 최신 내용이나 실제 기기 조작을 다시 검증한 것은 아님

---

## 4. 런타임과 결과 계약

### 4.1 자료 읽기

- [ToolDataCatalog.java][tool-data-catalog]: 원본과 구조화 파일을 모두 무결성 검사 후 읽음
- 대조 항목: 구조화 스키마, 건수, ID 집합, 핵심 메타데이터, 원본 해시 참조
- 원본 모델 적용 판정: 기존 `KnowledgeOverlay` 계속 사용

### 4.2 검색 결과

- [SearchKnowledgeTool.java][search-tool]: 구조화 `searchText`로 단어 점수를 계산하고 [KnowledgeDocument.java][knowledge-document]의 화면/후보 필드만 반환
- `content`: `displayText`
- 절차 보완과 자가검진의 `references`에도 같은 투영을 사용하므로 원본 래퍼 JSON이 반환되지 않음
- 기존 프론트엔드의 `title/content` 소비와 호환

### 4.3 실행 승인과 계약

- `action_descriptions`, `branches`, `requirements`, `model_applicability`: 검토 가능한 후보·제약이며 승인된 실행 단계가 아님
- 모든 구조화 문서: `is_action_approved=false`
- 유지: `approved-plans.json=[]`, `NOT_VERIFIED`
- 도구 입력과 공통 Envelope 유지, 추가 결과 필드는 [MCP 계약][mcp-contract]의 `knowledge_result_contract`에 기록

### 4.4 이 단계의 범위 밖

- 임베딩/RAG 도입이나 증상 검색 품질 통과가 아님
- 2단계에서 별도 평가: 단어 점수, 안전 문서 우선순위, 모델 미판정 및 보완 자료의 실제 적용 필터
- 7단계: 화면의 필드별 표시, 중복 제거, 기존 저장 상담 JSON 처리
- 하지 않은 것: 실행 중인 서비스 재시작, 운영 배포

---

## 5. 생성·검증 방법

### 5.1 실행 명령

저장소 루트에서 실행합니다.

```powershell
python -I backend/scripts/structure_knowledge.py
python -I backend/scripts/structure_knowledge.py --check
python -I -m unittest discover -s backend/scripts -p test_structure_knowledge.py -v
```

### 5.2 생성기 동작

- 실행 조건: 원본 해시가 기존 매니페스트와 일치
- 원본은 읽기만 하고, 구조화 파일과 매니페스트의 버전·해시·건수·생성 경로 갱신
- `--check`: 재생성 결과와 저장된 파일을 비교하며 수정하지 않음
- 수리비 생성기: 현재 데이터 버전을 유지하고 구조화 파일의 해시도 포함하도록 갱신
- CI에 구조화 재현 검사 연결

### 5.3 검증 결과

- 생성기 5개 검사 통과: 원본 바이트 보존, 결정적 재생성, 해시 불일치 차단, 진동 분기, 빈 QA 보충, 과거/미확인 필드 제외, 전체 본문 메타데이터 제외
- 백엔드 관련 검사 통과: 자료 무결성, 원문/구조화 연결, 도구 결과, 모델 제외/색상 공통 자료 제한, 실행 승인 미확인, 위험 규칙, 실제 FastMCP HTTP 경로
- Python MCP 20개 검사 통과, 실제 9개 도구 discovery에서 계약 재생성
- 최종 백엔드 검사 수: 해당 실행의 `backend/build/test-results/test` 보고서에 기록
- 이번 검사로 통과했다고 보지 않는 것: 실제 OpenAI 모델의 증상 검색·답변 품질, 브라우저 표시, 기존 저장 상담

[knowledge-json]: ../../backend/src/main/resources/homecare/knowledge.json
[structured-json]: ../../backend/src/main/resources/homecare/knowledge-structured.json
[structure-script]: ../../backend/scripts/structure_knowledge.py
[tool-data-catalog]: ../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java
[search-tool]: ../../backend/src/main/java/com/project/backend/mcp/tool/handler/SearchKnowledgeTool.java
[knowledge-document]: ../../backend/src/main/java/com/project/backend/mcp/tool/KnowledgeDocument.java
[mcp-contract]: mcp-tools.contract.json
[usability-review]: ../reviews/product_data_usability_review.md
