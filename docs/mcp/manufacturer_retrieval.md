# 제조사 자료 검색 연결 — 2단계 구현·검증 기록

> MCP `search_knowledge`의 제조사 전용 검색기, 적용 필터, 임베딩 캐시, 실제 임베딩 평가 기록
> 작성일: 2026-10-14 (Asia/Seoul) · 원자료 버전: `B30-COST-V3-20261014-R4` · 검색 알고리즘: `manufacturer-hybrid-v1`

**목차**

1. [구현 결과](#1-구현-결과)
2. [검색 단위와 필터](#2-검색-단위와-필터)
3. [관련성·자료 부족 처리](#3-관련성자료-부족-처리)
4. [저장·캐시·실행 환경](#4-저장캐시실행-환경)
5. [실제 임베딩 평가와 비용](#5-실제-임베딩-평가와-비용)
6. [재현 명령과 회귀 검증](#6-재현-명령과-회귀-검증)

---

## 1. 구현 결과

### 1.1 검색기 연결

- 연결: MCP `search_knowledge`에 제조사 전용 [KnowledgeRetriever][retriever]
- 대상: 1단계에서 만든 지식 1,542건
- 검사 순서: 모델·품목·제외 조건을 먼저 검사한 뒤 정확 오류 코드/실제 표시 문구, 증상 표현, 임베딩 유사도를 함께 사용
- 자가검진 `references`도 같은 경로 사용

### 1.2 재현 사례

- 입력: `탈수할때 세탁기 본체가 많이 흔들리면서 원 위치에서 크게 벗어나요.`
- 반환: `FX24ENER`의 `B17-MFL71792818-topic_66-009`
- 이 흔들림 문의의 근거로 선택되지 않는 자료: 침수 `T4-016/T4-036`, 급수·배수·거름망 자료, 진동 **센서 오류 표시** 자료
- 실제 표시 문구를 확인한 문의는 센서 오류 표시 자료로 별도 연결

### 1.3 변경하지 않은 것

- `RiskAssessmentService`, `RiskRules`, 대화의 `symptomCode`, 승인 계획
- 검색용 표현 묶음은 위험 판정이나 상담 상태 분류가 아님
- 유지: `approved-plans.json=[]`, `is_action_approved=false`, `NOT_VERIFIED`

---

## 2. 검색 단위와 필터

### 2.1 검색 단위

- 원본 사실 ID 하나를 검색 단위 하나로 구성, 다른 원인·모델의 절차를 합치지 않음
- 증상이 있는 사실: 제목·증상·오류 코드·원인 중심으로 임베딩
    - 첫 평가에서 긴 조치·주의사항이 증상 제목을 흐리는 문제 확인
- 절차·참고 자료: 구조화 검색 본문을 임베딩
- 반환 결과: 1단계에서 보존한 분기·조건·출처 유지
- 이 인덱스에는 실제 제조사 자료만 포함, 기존 `ManualCatalog/ManualRetriever`와 가상 `HC-*` 매뉴얼은 별도 경로 유지

### 2.2 모델 적용 필터

- 모델 적용: 기존 `KnowledgeOverlay` 사용
- 정확 판매 코드와 명시된 공통 자료 대표 연결 유지, 부품 호환에는 색상 대표를 전용하지 않음
- 일반/안전 문서 모두에서 제외: `EXCLUDED`, `NOT_LISTED`, 모델 미확정 문서
- `UNDETERMINED`는 공통 승인으로 처리하지 않음
- 품목 공통 참고 후보로 허용하는 조건: 원문 `scope=품목 공통`, `verification_status=verified_excerpt`, 사용 가능 표시와 출처
    - 표시: `scope_basis=VERIFIED_CATEGORY_COMMON`
    - 개인별 보증·조치 승인은 별도

### 2.3 제외·보완 자료

- 출처 적용 검토·충돌 검토·제외 참고 행은 일반 해결 문서로 검색하지 않음
- 원본 검토 자료와 본문에 연결된 제약/관계는 보존
- 보완 문서도 자신의 품목·모델 적용 조건을 검사한 뒤 별도 연결

---

## 3. 관련성·자료 부족 처리

### 3.1 검색 표현

- 정규화하는 제한된 검색 표현: `흔들림/진동/이동/위치 이탈`, `성에/얼음막`, 자연어 급수·배수 등
- `세탁기`, `제품`, `본체`, `탈수` 같은 넓은 단어만으로 결과를 채우지 않음
- 명시적으로 부정한 진동·연기 등의 표현은 검색의 양성 증거로 쓰지 않음
- 이 규칙은 일반 자연어 이해 완료를 뜻하지 않음

### 3.2 일치 조건

- 확인된 증상 묶음은 문서의 **제목·증상**과 일치해야 함
- 무관한 청소 절차의 주의사항에 단어가 있다는 이유로 선택하지 않음
- `크래프트/정수`처럼 특정 기능을 요청하면 다른 필터 자료로 대체하지 않음
- 오류 코드: 문자 경계를 검사하고 `OE/O E`, `dE1`을 원문 코드와 대조
- LCD의 한국어 표시를 임의 영문 코드로 바꾸지 않음

### 3.3 임베딩 검색 조건

- 임베딩 검색에도 같은 적용 필터를 먼저 적용
- 모델 후보 ID 목록을 `SimpleVectorStore` 메타데이터 필터로 전달해, 다른 모델이 상위 결과를 차지한 뒤 잘려 나가는 문제 방지

| 근거 종류 | 조건 |
| --- | --- |
| 일반 의미 근거 | 유사도 0.60 이상, 증상 호환성 |
| 어휘 증거가 없는 순수 의미 후보 | 유사도 0.72 이상, 가장 가까운 서로 다른 주제와 0.05 이상 간격 |

- 유사도는 원인 확률이나 고장 확정 값이 아님

### 3.4 자료 부족

| 상황 | 반환 |
| --- | --- |
| 확인된 모델에 관련 근거 없음 | `NO_DATA` |
| 모델 미확정 | `NEEDS_INPUT`, `missing_fields=[model_code]` |
| 의미 검색 준비 전/장애 | `LEXICAL_FALLBACK`, 준비 여부·실패 상태를 결과 메타데이터에 기록 |

- 검색 한도가 3/5건이어도 결과 수를 채우지 않음
- 기존 위험 차단 경로는 검색 결과와 독립

---

## 4. 저장·캐시·실행 환경

### 4.1 벡터 저장소

- [KnowledgeVectorIndex][vector-index]: 기존 Spring AI `SimpleVectorStore` 재사용
- 지원 기능: 로컬 JSON 저장/복원, 메타데이터 필터
- 참고: [Spring AI API 문서][spring-ai-doc]

### 4.2 규모

- 1,542개 벡터·1536차원의 순수 float 배열: 약 9 MiB
- 저장 JSON: 약 33 MB
- 이 규모에서는 별도 벡터 DB를 추가하지 않음
- JSON 파싱·텍스트·메타데이터의 메모리는 float 배열보다 크며, 위 값은 JVM 전체 메모리 실측치가 아님
- 문서 규모나 동시 접속이 커지면 로컬 전수 검색과 파싱 비용 재평가

### 4.3 캐시

- 기본 위치: 백엔드 실행 디렉터리의 `.homecare-cache/manufacturer/`
- `.gitignore`로 제외, 서비스 원자료/매니페스트에 임베딩을 넣지 않음
- 파일명 기준: 원자료 버전, 실제 검색 단위의 ID·텍스트·메타데이터, 알고리즘·임베딩 모델·공급자·설정된 차원의 해시
- 저장: 파일/메타데이터를 임시 파일에 저장한 뒤 교체
- 읽기: 페이로드 해시, ID 집합, 본문/메타데이터, 벡터 차원/값 대조
- 불일치 시 재생성, 이전 인덱스를 현재 자료로 사용하지 않음

### 4.4 준비와 대체 경로

- 앱 시작 후 백그라운드에서 캐시를 로드하거나 64개씩 임베딩
- 완성된 인덱스만 공개하므로 첫 상담이 전체 임베딩을 기다리지 않음
- 준비 전이거나 실패하면 적용 필터와 증상/오류 어휘 검색으로 대체
- 장애 재시도: 최소 1분 간격
- 검색 요청의 의미 검색: 최대 2초 대기 후 대체 경로
- 검색어 임베딩: 메모리에 최대 256개, 디스크 캐시에는 저장하지 않음

### 4.5 설정

```properties
homecare.knowledge.vector.enabled=${HOMECARE_KNOWLEDGE_VECTOR_ENABLED:true}
homecare.knowledge.vector.cache-dir=${HOMECARE_KNOWLEDGE_CACHE_DIR:.homecare-cache/manufacturer}
```

- `HOMECARE_AGENT_ENABLED=false`: 제조사 인덱스 준비도 비활성화
- 의미 검색을 끄면 `LEXICAL_ONLY`로 표시
- 쓰기 불가능한 디스크: 메모리 인덱스 사용, 재시작 시 재임베딩 필요
- 운영에서 캐시를 유지하려면 위 경로에 쓰기 가능한 영속 디렉터리 지정
- 실행하지 않은 것: 운영 배포, 프로세스 재시작, DB 변경

---

## 5. 실제 임베딩 평가와 비용

### 5.1 평가 구성

- [평가 정의][retrieval-eval]와 기존 API 키로 **실제 `text-embedding-3-small` 임베딩** 실행
- 이 명령에서 사용하지 않는 것: 채팅 모델, 답변 생성, 사용자 DB

### 5.2 결과

| 실행 | 결과 | 비고 |
| --- | --- | --- |
| [최초 전체 본문 임베딩 평가][initial-result] | 25개 중 22개 통과 | 놓친 위치/비유 표현, 위성 통신 문의의 무관한 연결 문서 선택 보존 |
| [최종 평가][final-result] | 총 33개 통과 | 원래 25개, 추가 검증 7개, 성에 표현 개발 사례 1개 |

- 최초 heldout 결과를 수정에 참고했으므로 해당 7개는 이후 회귀 사례로 명시
- 수정에 사용한 개발/회귀 사례와 이후 추가한 검증 7개를 구분하며, 독립적인 대규모 미사용 평가로 보지 않음
- 최종 평가 포함 사례: 다른 모델·품목, 모델 없음, 정확/띄어 쓴 코드, 표시 문구, 부정 표현, 제외 기능, 자료 없는 문의
- 최종 결과 기록 항목: 문서 ID, 일치 방식, 유사도, 최초/재검색 시간과 모드

### 5.3 인덱스 생성 비용

| 인덱스 | 토큰 | 시간 |
| --- | ---: | ---: |
| 최초 인덱스 | 366,669 | 약 17.6초 |
| 증상 중심 인덱스 | 155,721 | 약 11.0초 |

- 캐시 로드: 실측 약 0.7~1.4초
- 재실행: 문서 1,542개를 다시 임베딩하지 않고 새 프로세스의 검색어만 임베딩
- 최종 실행의 검색어 토큰·시간: 결과 JSON의 `query_embedding_tokens`, `first_query_ms`, `cached_query_ms`
- 금액(2026-10-14 공식 입력 단가 $0.02/100만 토큰 기준)
    - 증상 중심 인덱스 1회 생성: 약 **$0.003114**
    - 최초 실험과 합친 문서 임베딩: 약 **$0.010447**(검색어 요청은 별도)
    - 사용량에 단가를 곱한 추정이며 계정 청구서 확인 금액이 아님
- 참고: [OpenAI 모델 가격][openai-pricing], [공식 임베딩 안내][openai-embeddings]

### 5.4 검색 시간

| 구분 | 값 |
| --- | ---: |
| 최초 검색 중앙값 | 421 ms |
| 최초 검색 최댓값 | 2,028 ms |
| 재검색 중앙값 | 5 ms |

- 2초 제한을 넘긴 요청은 어휘 대체로 반환되며 그 모드도 결과 JSON에 기록
- 동시에 수행한 단위/통합 검사 때문에 시간에는 실행 간 차이가 있음
- 평균·최댓값·구간별 통과 수는 결과 JSON으로 계산 가능

### 5.5 평가 범위

- 이 33개와 구현 검사를 전체 4품목의 검색 품질이나 사용자 상담 전체 완료로 확대하지 않음
- 후속 작업: 아직 확보하지 않은 B30 원문 117건 복원, 3~8단계

---

## 6. 재현 명령과 회귀 검증

### 6.1 실행 명령

저장소 루트에서 실행합니다.

```powershell
python -I backend/scripts/structure_knowledge.py --check
$env:HOMECARE_TEST_PYTHON=(Resolve-Path 'mcp_server/.venv/Scripts/python.exe').Path
Set-Location backend
.\gradlew.bat evaluateManufacturerRetrieval
.\gradlew.bat test --tests 'com.project.backend.mcp.*' --tests 'com.project.backend.data.*'
```

- `evaluateManufacturerRetrieval`: 기존 `backend/.env`/환경의 `OPENAI_API_KEY`를 읽고 실제 임베딩 API 사용
    - 캐시가 없으면 문서 임베딩 비용 발생
    - 평가가 실패하면 결과를 저장한 뒤 실패 코드로 종료
- 일반 CI의 JUnit: 가짜 임베딩과 모의 OpenAI/실제 FastMCP HTTP를 사용하므로 외부 API 키가 필요 없음

### 6.2 회귀 검증 결과

- 백엔드 관련 9개 클래스 79개 검사: 실패·오류·건너뜀 0건
- 검사 범위
    - 적용 범위, 코드 경계, 자료 부족, 진동과 표시 오류 구분
    - 캐시 재사용·손상·모델/차원 변경, 임베딩 오류·2초 시간 초과
    - 기존 위험 규칙과 소유권/버전 제약
    - 등록 `FX24ENER`의 실제 MCP HTTP 검색과 승인 미확인
- Python MCP 20개 통과, 실제 discovery에서 9개 도구 계약 재생성
- 프론트엔드는 수정하지 않음
- 이번 검증 범위 밖: 실제 채팅 모델의 도구 선택·최종 답변 품질

[retriever]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetriever.java
[vector-index]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java
[retrieval-eval]: retrieval_evaluation.json
[initial-result]: ../benchmarks/manufacturer_retrieval_initial_20261014.json
[final-result]: ../benchmarks/manufacturer_retrieval_20261014.json
[spring-ai-doc]: https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/SimpleVectorStore.html
[openai-pricing]: https://developers.openai.com/api/docs/models/text-embedding-3-small
[openai-embeddings]: https://developers.openai.com/api/docs/guides/embeddings
