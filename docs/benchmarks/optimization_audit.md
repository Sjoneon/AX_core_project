# 성능 최적화 사례 검수

> 이력서 활용 후보의 구현·측정·선택 이유 근거 검수 기록
> 기준일: 2026-10-30 · 기준 커밋: `32a5b4d`(main) · 측정 원자료: [optimization_audit/results][results]

**정리 기준**

- 근거 구분
    - **구현**: 코드와 커밋으로 확인한 사실
    - **측정**: 이번 검수에서 실행한 검증 프로브의 결과
    - **기록**: 기존 평가 파일(`docs/benchmarks/*.json`)에 남은 값
    - **선택 이유**: 커밋 메시지와 저장소 설계 문서에 적힌 내용만 기재
- 제외 기준: 같은 질문을 바로 다시 실행해 빨라진 값은 일반 성능 근거로 쓰지 않음
- 비교 대상: 실제 과거 커밋과 현재 커밋, 느린 비교 코드는 새로 만들지 않음
- 외부 API: 유료 호출 없음, 모의 모델은 호출 횟수만 세고 지연을 넣지 않음
- 변경 범위: 서비스 코드·운영 자원 변경 없음, 프로브는 `git archive`로 내보낸 사본에만 넣어 실행
- 통계: 예열 표본 제외 후 중앙값과 p95(최근접 순위, `ceil(0.95 × n)`번째 값)
- 판정
    - **A**: 구현과 비교 측정이 확인돼 이력서에 사용 가능
    - **B**: 구현은 확인되지만 추가 측정 필요
    - **C**: 측정 조건이나 실사용 효과가 약해 제외

**목차**

1. [검수 결과](#1-검수-결과)
2. [검수 방법](#2-검수-방법)
3. [자가점검 결과 1회 전송](#3-자가점검-결과-1회-전송)
4. [제조사 검색 개선](#4-제조사-검색-개선)
5. [문서 임베딩 인덱스 재사용](#5-문서-임베딩-인덱스-재사용)
6. [그 밖의 검토 후보](#6-그-밖의-검토-후보)
7. [관련 문서](#7-관련-문서)

---

## 1. 검수 결과

| 후보 | 판정 | 핵심 근거 |
| --- | --- | --- |
| [자가점검 결과 1회 전송](#3-자가점검-결과-1회-전송) | **A** | 과거·현재 구현 실측, 단계 응답 요청 5회 → 1회 |
| [제조사 검색 개선](#4-제조사-검색-개선) | **C** | 속도 개선 아님, 22→25는 판정 기준이 다른 개발 세트 비교 |
| [문서 임베딩 인덱스 재사용](#5-문서-임베딩-인덱스-재사용) | **A** | 실제 API 기록, 재시작 시 문서 임베딩 25요청 → 0 |
| [그 밖의 후보](#6-그-밖의-검토-후보) | B·C | 비교 기준 또는 실사용 비율 자료 없음 |

**근거가 있는 주장 범위**

- 자가점검 결과 1회 전송
    - 자가점검 k단계 진행 시 서버 요청 k회 → 1회(5단계 기준 POST 5회 → 1회, 화면의 이력 조회 포함 HTTP 10회 → 2회)
    - 채팅 요청 제한 대상 요청 5회 → 0회, 기본 제한에서 즉시 진행 시 429 응답 4회·대기 45초 → 0회
- 문서 임베딩 인덱스 재사용
    - 자료·모델 설정의 SHA-256 지문을 캐시 키로 사용, 재시작 시 문서 1,542건 재임베딩 생략(25요청·155,721토큰 → 0)
    - 의미 검색 준비 시간: 미적중 11.0~27.3초(실 API 1회씩) → 적중 중앙값 0.90초(실 API 9회, p95 1.21초)

**근거가 없는 주장**

- "자가점검 통합으로 AI 호출 감소": 변경 전에도 버튼 응답은 해석기를 거치지 않음(외부 모델 호출 0회 측정)
- "하이브리드(어휘·벡터) 검색으로 정확도 22→25 향상": 벡터 없이 어휘·필터 경로만으로 33/33 통과
- "재검색 5 ms": 같은 질의 반복 시 검색어 임베딩 캐시 효과
- "임베딩 비용 n% 절감" 총량: 운영 재시작 횟수·적중률 자료 없음

---

## 2. 검수 방법

### 2.1 대상 커밋

| 라벨 | 커밋 | 용도 |
| --- | --- | --- |
| `before_dd95d9a` | `8b42182`(`dd95d9a^`) | 자가점검 단계별 채팅 방식 |
| `after_dd95d9a` | `dd95d9a` | 자가점검 결과 1회 전송 도입 |
| `retrieval_9af5760` | `9af5760` | 제조사 검색 평가 33/33 기록 시점 |
| `current` | `8fd1ce2`(트리 = main `32a5b4d`) | 현재 |

- AGENTS.md: 저장소에 없음
- 검수 시점 브랜치: `docs/docs-format`(PR #3으로 main에 병합된 상태, main과 트리 동일)

### 2.2 측정 환경

- 기기: Windows 11(10.0.26200), Intel Core i5-1340P(논리 16코어), 메모리 15.6 GB
- 실행: OpenJDK 21.0.1(Gradle 툴체인), Gradle 9.7.1, Python 3.13.2(`mcp_server/.venv`)
- 상담 흐름: 기존 `McpIntegrationTests`의 격리 환경 재사용
    - H2 메모리 DB(MySQL 모드), 실제 FastMCP(로컬 HTTP), 지연 없는 모의 OpenAI 서버
    - 모의 서버가 해석기·답변 생성기·위험 판정·도구 선택 요청을 구분해 셈
- 인덱스: 실제 제조사 지식 1,542건, 1,536차원 오프라인 계수 모델
    - 문자 2-gram 해시 + 고정 시드 잡음으로 조밀한 벡터 생성(캐시 파일 34.1 MB, 실제 기록 약 33 MB)
    - 호출 횟수·입력 텍스트만 세고 지연 없음
- 실행하지 않은 것: 유료 API 호출, 운영 DB·운영 서버 접근, 서비스 코드 수정

### 2.3 검증 프로브

| 파일 | 측정 내용 |
| --- | --- |
| [SelfCheckRequestProbe][probe-self-check] | 기본 요청 제한에서 각 버전의 화면 동작을 HTTP로 재현, 429·대기 기록 |
| [SelfCheckRequestTimingProbe][probe-self-check-timing] | 요청 제한을 높여 요청 수·모델 호출·로컬 처리 시간 측정, 서버 검증 응답 확인 |
| [RetrievalAuditProbe][probe-retrieval] | 저장된 검색 실행의 재판정, 어휘·필터 경로 단독 실행, 로컬 검색 시간 |
| [IndexCacheProbe][probe-index-cache] | 실제 지식으로 인덱스 생성·캐시 로드 비교, 다른 질문 재사용, 캐시 무효화 |

- 실행기: [run_audit.py][run-audit]
    1. 대상 커밋의 `backend`, `mcp_server`, 평가 정의를 `git archive`로 저장소 밖 임시 폴더에 내보냄
    2. 대상 커밋에 있는 테스트 구성에 맞는 프로브만 사본에 복사
    3. 프로브별로 Gradle 테스트 실행, 결과 JSON을 `results/<라벨>/`에 복사
    4. `summarize`로 중앙값·p95·개선율 계산 후 [summary.json][summary] 생성

### 2.4 재현 명령

저장소 루트에서 실행합니다.

```powershell
python -I docs/benchmarks/optimization_audit/run_audit.py run --work $env:TEMP\homecare-audit
python -I docs/benchmarks/optimization_audit/run_audit.py summarize
```

- 필요 환경: JDK 21, `mcp_server` 요구 패키지가 설치된 `mcp_server/.venv`
- 소요 시간: 약 14분(이 기기 기준, 대상별 최초 Gradle 컴파일과 기본 요청 제한 대기 포함)
- 일부만 실행: `run --work <폴더> --only current --probes index_cache`

---

## 3. 자가점검 결과 1회 전송

### 3.1 문제와 중요성

**문제**

- 선택 이유(커밋 `dd95d9a` 메시지)
    - 단계마다 채팅 요청을 보내 요청 제한(연속 3회·분당 5회)에 걸림
    - AI 해석이 매번 실행됨
- 검수 결과
    - 요청 제한: 재현됨(3.7)
    - AI 해석 반복: 버튼 응답 기준으로 재현되지 않음(외부 모델 호출 0회, 3.8)

**중요성**

- 429 응답은 화면에 오류로 표시되고 고객이 같은 답을 다시 보내야 진행
- 단계 응답마다 상담 상태 갱신·대화 저장·이력 재조회 반복
- 변경 전 통합 테스트도 HTTP 요청 제한 때문에 남은 단계 응답을 서비스 직접 호출로 우회
    - 주석: "The per-user chat rate limit applies to HTTP; the remaining step answers go to the same service directly."

### 3.2 당시 제약

- 채팅 요청 제한: 사용자별 토큰 버킷(연속 3회, 분당 5회 충전), 하루 100회
    - 적용 경로: `POST /api/chat`([WebConfig.java:17][web-config])
    - 설정: [ChatRateLimiter.java:27][rate-limiter], 기준 문서 [MCP 구현][mcp-impl]
- 자가점검 단계 수: 설명서 확인 항목 최대 6개(`ManualCheckItems.MAX_STEPS`)
- 상담 상태: 버전 번호로 갱신 충돌 검사(`ConsultationService.applyDialogue`)
- 요청 제한 상태: 백엔드 컨테이너 1대의 메모리에 보관

### 3.3 적용한 설계 원리

- 중간 상호작용의 클라이언트 처리와 결과 1회 확정(요청 통합)
    - 단계 목록 전체를 응답 카드 하나로 전달
    - [아니요, 다음 단계]는 브라우저 상태(`useState`)로만 진행
    - 서버에는 최종 결과(해결 단계 또는 마지막 단계 미해결)만 전송
- 낙관적 동시성 제어
    - 카드에 `context_version`을 담아 보내고 서버가 현재 상담 버전과 비교
    - 다르면 409로 거부해 오래된 화면의 결과 반영 차단
- 서버 측 검증 유지: 소유권, 결과 값, 단계 번호 범위
- 요청 제한 경로 분리: 결과 요청은 `/api/conversations/{id}/self-check`로 보내 채팅 제한 대상에서 제외

### 3.4 변경 전후 처리 방식

| 구분 | 변경 전(`8b42182`) | 변경 후(`dd95d9a`, 현재) |
| --- | --- | --- |
| 단계 전달 | 응답마다 1단계 문장·선택지 | 응답 1회에 단계 목록 카드 |
| [아니요, 다음 단계] | `POST /api/chat` + 이력 `GET` | 브라우저 안에서 진행, 요청 없음 |
| 최종 결과 | `POST /api/chat` | `POST /api/conversations/{id}/self-check` |
| 채팅 요청 제한 | 단계 응답마다 적용 | 적용 안 됨 |
| 서버 처리 | 해석 생략 분기 → 단계 진행 → 상태·대화 저장 | 버전·단계 검증 → 상태·대화 저장 |

- 현재 main: 같은 결과 요청에 관찰 질문 답(`observations`)도 함께 전송(커밋 `1e91ac0`, `5db86ca`)

### 3.5 근거 위치

- 커밋: `dd95d9a`, `1e91ac0`, `5db86ca`
- 백엔드
    - `AgentChatService.answerSelfCheck`: [AgentChatService.java:256][agent-answer]
    - 자가점검 카드 생성: [AgentChatService.java:239][agent-card]
    - 결과 API: [ConsultationController.java:65][consultation-controller]
- 프론트엔드
    - 단계 진행 컴포넌트 `SelfCheck`: [ConsultationCard.jsx:60][consultation-card]
    - 결과 전송 `answerSelfCheck`: [Chat.jsx:258][chat-jsx]
- 테스트
    - `McpIntegrationTests.registeredRefrigeratorPurchaseReachesMcpChatAndSavedHistory`
    - `McpIntegrationTests.manualConditionsBecomeSelfCheckStepsAndOneOutcomeClosesThem`
    - [verify-display.mjs][verify-display]: 브라우저에서 단계 넘김 시 전송 없음 확인

### 3.6 측정 조건

- 시나리오: 냉장고 `S836MEE022` 등록 → "냉장고가 시원하지 않아요." → "모두 전혀 차갑지 않아요" → 자가점검 5단계
    - `NOT_RESOLVED`: 5단계 모두 미해결
    - `SOLVED@3`: 3단계에서 해결
- 화면 동작 재현: 각 버전의 `Chat.jsx`처럼 POST 뒤 이력 `GET /api/conversations` 1회
- 집계 범위: 자가점검 안내를 받은 뒤부터 최종 응답까지, 제품 등록과 상담 시작 요청 2회는 제외
- 반복
    - 기본 요청 제한: 시나리오별 1회, 429는 `Retry-After`만큼 기다린 뒤 재전송
    - 요청 제한 해제: 예열 3회 + 측정 20회, 두 시나리오를 번갈아 실행
- 처리 시간: 로컬 기기 안의 HTTP 왕복과 서버 처리, 네트워크·RDS 지연 미포함

### 3.7 측정값

**요청 수(`NOT_RESOLVED`, 5단계)**

| 항목 | 변경 전 | 변경 후 | 현재 |
| --- | ---: | ---: | ---: |
| 단계 응답 POST | 5 | 1 | 1 |
| 이력 GET | 5 | 1 | 1 |
| 채팅 요청 제한 대상 | 5 | 0 | 0 |
| 외부 모델 호출 | 0 | 0 | 0 |
| 상담 전체 저장 메시지 | 14 | 6 | 6 |

**요청 수(`SOLVED@3`)**

| 항목 | 변경 전 | 변경 후 | 현재 |
| --- | ---: | ---: | ---: |
| 단계 응답 POST | 3 | 1 | 1 |
| 이력 GET | 3 | 1 | 1 |
| 외부 모델 호출 | 0 | 0 | 0 |
| 상담 전체 저장 메시지 | 10 | 6 | 6 |

**기본 요청 제한에서 즉시 진행**

| 시나리오 | 변경 전 429 | 변경 전 대기 | 변경 후 429 | 현재 429 |
| --- | ---: | ---: | ---: | ---: |
| `NOT_RESOLVED` | 4 | 45초 | 0 | 0 |
| `SOLVED@3` | 2 | 24초 | 0 | 0 |

**로컬 처리 시간(단계 구간 POST 합계, n=20)**

| 시나리오 | 변경 전 중앙값(p95) | 변경 후 중앙값(p95) | 현재 중앙값(p95) |
| --- | ---: | ---: | ---: |
| `NOT_RESOLVED` | 183.2 ms(258.5) | 32.8 ms(50.0) | 30.2 ms(53.5) |
| `SOLVED@3` | 84.1 ms(120.4) | 19.1 ms(37.1) | 16.4 ms(37.7) |

**개선율 계산식**

- 단계 응답 POST 감소율 = (변경 전 − 변경 후) / 변경 전
    - `NOT_RESOLVED`: (5 − 1) / 5 = 80%
    - `SOLVED@3`: (3 − 1) / 3 = 66.7%
    - 일반식: k단계에서 끝나면 (k − 1) / k
- HTTP 요청 감소율(POST + 이력 GET): `NOT_RESOLVED` (10 − 2) / 10 = 80%
- 채팅 요청 제한 대상: 5 → 0(100% 감소), 상담 1건의 채팅 한도 사용 7회 → 2회(하루 100회 기준)
- 로컬 처리 시간 감소율 = 1 − 변경 후 중앙값 / 변경 전 중앙값 (로컬 참고값)
    - `NOT_RESOLVED`: 1 − 32.8 / 183.2 = 82.1%
    - `SOLVED@3`: 1 − 19.1 / 84.1 = 77.3%

### 3.8 정확성 유지·적용 범위·한계

**정확성 유지**

- 최종 결과: 세 버전 모두 같은 상태로 종료
    - `NOT_RESOLVED`: 요약 카드(`SUMMARY`), `CHECK_DONE=NOT_RESOLVED`
    - `SOLVED@3`: 상담 종료, `CHECK_DONE=SOLVED`
- 서버 검증: 변경 직후(`dd95d9a`)와 현재 main의 응답 동일
    - 변경 전 방식에서도 다른 사용자의 상담 번호로 보낸 채팅은 404

| 요청 | 응답 |
| --- | --- |
| 다른 사용자의 결과 전송 | 404 |
| 이전 `context_version` | 409 |
| 마지막 단계 전 미해결 | 400 |
| 알 수 없는 결과 값 | 400 |
| 정상 결과 | 200(외부 모델 호출 0회) |
| 같은 결과 재전송 | 409 |

**적용 범위와 한계**

- 외부 AI 호출 감소 효과 없음
    - 변경 전에도 서버가 제시한 선택지와 같은 문장은 해석기 호출 생략(`MessageInterpreter.interpret`, 커밋 `08146fe`)
    - 위험 판정 AI는 위험 키워드 후보가 있을 때만 호출(`RiskAssessmentService.assess`)
    - 직접 입력한 답은 변경 후에도 `/api/chat`과 해석기를 거침
- 시간 수치는 같은 기기 안의 로컬 왕복 기준, 실제 네트워크 왕복·RDS 지연 미측정
    - 일반화 가능한 값은 요청 수 감소
- 429는 고객이 약 12초(분당 5회 충전 간격)보다 빨리 누를 때만 발생, 실제 고객의 단계 확인 시간 자료 없음
- 단계 수는 증상별 1~6개, 위 값은 냉장고 5단계 시나리오
- 변경 전 구현(`8b42182`)은 `dd95d9a`보다 11초 먼저 저장소에 들어온 개발 단계 커밋, 운영 배포 기록 없음

### 3.9 개인 기여 근거

- 변경 커밋 `dd95d9a`, `1e91ac0`, `5db86ca`: 작성자 `Sjoneon`(송재원)
- 요청 제한기 최초 구현 `66b76db`: 작성자 `Sjoneon`
- 팀원 작업
    - 채팅 화면 기반과 스트리밍 요청(`chatStream.js`, `3b1d20c`): `gwonu1`
    - `f149048`(`gwonu1`): null 안전성·형식 정리, 자가점검 동작 변경 없음
- 커밋 메시지의 cherry-pick 원본 커밋은 이 저장소에 없음

### 3.10 판정

- **A**
- 사용 범위: 자가점검 단계 응답의 서버 요청을 단계 수만큼에서 1회로 통합, 채팅 요청 제한 대상 요청 0회
- 제외할 표현: AI 호출 감소, 응답 속도 n% 개선

---

## 4. 제조사 검색 개선

### 4.1 문제와 중요성

**문제**

- 선택 이유(커밋 `9af5760` 메시지, [제조사 자료 검색 연결][retrieval-doc] 1·3·5장)
    - 제조사 지식 1,542건에서 등록 모델·품목에 맞는 근거만 선택
    - 첫 평가에서 위치·비유 표현 누락, 지원하지 않는 기능 문의에 무관한 문서 선택

**중요성**

- 다른 모델·제외 자료가 상담 근거로 나가면 잘못된 조치 안내로 이어짐

### 4.2 당시 제약

- 적용 범위 밖 자료 금지(안전 자료 포함), 결과 수를 채우려고 관련 낮은 문서를 넣지 않음
- 검색 요청당 의미 검색 대기 상한 2초
- 별도 벡터 DB 없이 Spring AI `SimpleVectorStore` 사용

### 4.3 적용한 자료구조·알고리즘

- 적용 필터 선행(필터 후 순위)
    - 품목·모델 적용 상태·주제·검토 행 제외로 후보 ID 집합 생성([KnowledgeRetriever.java:58][retriever-filter])
    - 벡터 검색에 `fact_id in [후보]` 메타데이터 필터 전달, 다른 모델 문서가 상위 K를 차지해 잘리는 문제 방지
- 증상 표현 정규화: 정규식 개념 묶음 17개, 부정 표현 제거([KnowledgeTextMatcher.java:10][text-matcher])
- 어휘 점수: 개념 일치 4점, 제목·증상 토큰 2점, 본문 토큰 0.4점, 불용어·조사 제거([KnowledgeTextMatcher.java:50][text-matcher-lexical])
- 오류 코드: 문자 경계 정규식, 띄어 쓴 코드 허용
- 의미 근거 기권 규칙
    - 유사도 0.60 이상·증상 호환
    - 어휘 근거가 없으면 유사도 0.72 이상, 1·2위 주제 간격 0.05 이상([KnowledgeRetriever.java:87][retriever-abstain])
- 임베딩 텍스트: 증상 사실은 제목·증상·오류 코드·원인만 사용

### 4.4 변경 전후 처리 방식

- 변경 전 코드: 저장소에 없음
    - 최초 평가 이후 수정이 커밋 `9af5760` 하나에 합쳐짐
- 기록으로 확인 가능한 차이

| 구분 | 최초 실행 | 최종 실행 |
| --- | --- | --- |
| 인덱스 지문 | `0ef8073a…` | `04c51cd4…` |
| 임베딩 대상 | 전체 본문 | 증상 중심 |
| 문서 임베딩 토큰 | 366,669 | 155,721 |
| 평가 사례 | 25 | 33(기존 25 + 검증 7 + 개발 1) |

### 4.5 근거 위치

- 커밋: `9af5760`, 이후 `85f9c54`(냉각 표현 1개 추가), `f149048`(형식 정리)
- 코드
    - `KnowledgeRetriever.retrieve`: [KnowledgeRetriever.java:50][retriever]
    - `KnowledgeRetrievalPolicy.eligible`: [KnowledgeRetrievalPolicy.java:50][retrieval-policy]
    - `KnowledgeRetrievalEvaluation.passes`: [KnowledgeRetrievalEvaluation.java:59][retrieval-eval-code]
- 평가 정의: [retrieval_evaluation.json][retrieval-eval]
- 기록: [최초 실행][retrieval-initial], [최종 실행][retrieval-final]

### 4.6 측정 조건

- 기록 대조: 최초·최종 실행 JSON의 질의·모델·결과 비교
- 재판정: `9af5760` 사본에서 커밋된 판정 함수로 저장 결과를 다시 채점
    - 패키지 자료 버전 `B30-COST-V3-20261014-R4`가 두 실행의 자료 버전과 같음
- 어휘·필터 경로 단독: 같은 검색기에서 벡터 인덱스만 제외하고 33개 사례 실행(`9af5760`, 현재)
- 로컬 검색 시간: 어휘·필터 경로, 33개 질의 × 20회(예열 3회)

### 4.7 측정값

**통과 수**

| 구분 | 최초 실행 | 최종 실행 |
| --- | ---: | ---: |
| 기록된 통과 | 22/25 | 25/25(전체 33/33) |
| 커밋된 기준으로 재판정 | 21/25 | 25/25(전체 33/33) |
| 같은 질의·모델 | 25/25 | - |

**판정이 바뀐 사례**

| 사례 | 분류 | 최초 | 최종 | 최종 결과의 근거 |
| --- | --- | --- | --- | --- |
| `vibration-paraphrase` | 개발 | 실패 | 통과 | 일치 방식 `HYBRID`, 진동 개념 정규식에 "춤을 춰" 포함 |
| `vibration-heldout-position` | 최초 heldout | 실패 | 통과 | 일치 방식 `LEXICAL`, 진동 개념 정규식에 "밀려" 포함 |
| `heldout-no-data` | 최초 heldout | 실패 | 통과 | 최초 무관한 문서 1건 → 최종 빈 결과 |
| `excluded-optional` | 개발 | 통과 | 통과 | 최초 문서 3건, 커밋된 기준(`expect_empty`)으로는 실패 |

- 변경 전 코드가 없어 각 사례를 바꾼 규칙은 특정할 수 없음
- 네 사례 모두 최종 코드의 어휘·필터 경로 단독으로도 같은 결과

**벡터 경로의 기여**

- 어휘·필터 경로 단독 통과: `9af5760` 33/33, 현재 33/33
- 최종 하이브리드 결과와 어휘 단독 결과의 차이: 33건 중 1건(`frost-paraphrase`) 문서 순서만 다름
- 최종 실행의 문서 일치 방식: `HYBRID` 17, `LEXICAL` 4, `EXACT_ERROR_CODE` 4, `EXACT_DISPLAY_LABEL` 1, `SEMANTIC_ONLY` 0
    - 벡터 유사도는 점수(어휘 점수 + 유사도 × 5)에 더해져 순위에만 영향

**검색 시간**

| 구분 | 값 | 판단 |
| --- | ---: | --- |
| 최초 실행 첫 검색 중앙값(n=21) | 175 ms | 검색어 임베딩 API 포함 |
| 최종 실행 첫 검색 중앙값(같은 사례, n=20) | 429 ms | 실행 시각·동시 작업이 달라 비교 불가 |
| 최종 실행 재검색 중앙값 | 5 ms | 같은 질의 반복, 제외 |
| 어휘·필터 경로 로컬 시간 중앙값(p95, n=660) | 4.3 ms(10.9 ms) | `9af5760`, 네트워크 없음 |

- 문서 임베딩 토큰 57.5% 감소(366,669 → 155,721)는 임베딩 텍스트 변경의 부수 효과
    - 1회 생성 비용 약 $0.0073 → $0.0031(2026-10-14 단가 $0.02/100만 토큰)

### 4.8 정확성 유지·적용 범위·한계

- 비교 기준 차이: `excluded-optional`의 최초 기록 판정(통과)과 커밋된 기준의 판정(실패)이 다름
    - 같은 기준으로는 21/25 → 25/25, 기록끼리 비교한 22/25 → 25/25와 다름
- 개발 세트 중심: 판정이 바뀐 4건 중 2건은 개발 사례, 2건은 최초 결과를 보고 수정한 뒤 회귀 사례로 전환된 heldout
- 독립 검증: 최종 실행 전에 추가한 검증 7건만 해당, 최초 값 없음
- 벡터 경로 기여 미확인: 이 평가 세트에서는 통과 여부를 바꾸지 않음
- 속도 개선 아님: 하이브리드 첫 검색 시간은 검색어 임베딩 API 왕복이 대부분
- 실제 사용자 질의 분포 미반영

### 4.9 개인 기여 근거

- `9af5760`, `85f9c54`: 작성자 `Sjoneon`
- `f149048`(`gwonu1`): 줄바꿈·null 안전성 정리, 검색 로직 변경 없음

### 4.10 판정

- **C**
- 성능 최적화 사례에서 제외
- 검색 품질 사례로 쓰려면 필요한 측정
    - 수정에 쓰지 않은 새 평가 세트
    - 변경 요소별 제거 실험(표현 정규화, 기권 규칙, 임베딩 텍스트)
    - 벡터 사용·미사용 비교(임베딩 비용 발생)

---

## 5. 문서 임베딩 인덱스 재사용

### 5.1 문제와 중요성

**문제**

- 선택 이유([제조사 자료 검색 연결][retrieval-doc] 4.3·4.4·5.3절)
    - 문서 1,542건을 매번 임베딩하면 API 비용·시간 발생
    - 첫 상담이 전체 임베딩을 기다리지 않아야 함
    - 이전 인덱스를 현재 자료로 쓰지 않아야 함

**중요성**

- 배포·재시작마다 외부 API 25회 호출과 수십 초의 의미 검색 공백 발생
- 자료·모델이 바뀐 뒤 이전 벡터를 쓰면 다른 버전의 근거가 섞임

### 5.2 당시 제약

- 운영 컨테이너: 읽기 전용 루트 파일 시스템, 메모리 1 GB
- 규모: 1,542 × 1,536 float 약 9 MiB, 별도 벡터 DB 미도입
- 검색 요청당 의미 검색 대기 상한 2초

### 5.3 적용한 자료구조·설계 원리

- 내용 주소 방식 캐시 키
    - SHA-256(알고리즘 버전, 자료 버전, 모델, 공급자, 설정 차원, 검색 단위 전체의 ID·본문·메타데이터)
    - 키가 파일 이름이 되므로 자료·모델·차원 중 하나만 바뀌어도 다른 캐시 사용
    - 사용자 질문은 키에 포함하지 않음, 질문이 달라도 같은 문서 인덱스 사용
- 무결성 검사: 페이로드 SHA-256, ID 집합·본문·메타데이터 일치, 벡터 차원·유한값·0 벡터 검사
- 원자적 교체: 임시 파일에 저장한 뒤 `ATOMIC_MOVE`
- 일괄 요청: 64건씩 25회
- 백그라운드 준비: 앱 시작 후 로드 또는 생성, 완성된 인덱스만 공개, 준비 전에는 어휘 검색으로 대체
- 검색어 임베딩 LRU(256개, 접근 순서 `LinkedHashMap`): 같은 질문 반복에만 효과가 있어 이번 판정에서 제외

### 5.4 변경 전후 처리 방식

| 구분 | 캐시 미적중(최초·자료 변경) | 캐시 적중(재시작) |
| --- | --- | --- |
| 문서 임베딩 요청 | 25회 | 0회 |
| 문서 임베딩 토큰 | 155,721 | 0 |
| 처리 | 64건씩 임베딩 → 검증 → 저장 | 지문·해시·내용 검증 → 로드 |

- 두 경우 공통: Spring AI `SimpleVectorStore`의 관측 정보 생성 시 `EmbeddingModel.dimensions()`가 "Test String" 임베딩을 1회 요청
    - 미적중: 문서 추가 때 발생, 적중: 첫 검색 때 발생
    - 검색어 임베딩 캐시를 거치므로 인덱스 인스턴스당 1회

### 5.5 근거 위치

- 커밋: `9af5760`
- [KnowledgeVectorIndex.java][vector-index]
    - 지문 계산: [82행][vector-index-fingerprint]
    - 생성 `prepare`: [94행][vector-index-prepare], 64건 일괄: [102행][vector-index-batch]
    - 캐시 검증 `load`: [130행][vector-index-load]
    - 2초 상한: [175행][vector-index-timeout]
- 운영 캐시 볼륨: [compose.prod.yaml:20][compose-prod](커밋 `421065a`, 작성자 `gwonu1`)
- 테스트: [KnowledgeVectorIndexTests][vector-index-test]
    - `cacheReloadDoesNotReembedAndTamperingForcesRebuild`
    - `changingEmbeddingIdentityInvalidatesTheCache`, `changingDimensionsInvalidatesTheCache`

### 5.6 측정 조건

- 기록: 실제 `text-embedding-3-small` 실행 11회의 인덱스 지표
    - 검색 평가 2회, 상담 평가 9회, 실행마다 새 JVM 프로세스
- 측정: [IndexCacheProbe][probe-index-cache]
    - 현재 main 사본, 실제 지식 1,542건, 1,536차원 오프라인 계수 모델
    - 생성: 빈 폴더에서 예열 1회 + 5회
    - 로드: 같은 폴더에 새 인스턴스 생성, 예열 3회 + 20회, `prepare()` 안의 시간 측정(`initialization_ms`와 같은 구간)

### 5.7 측정값

**실제 API 기록**

| 인덱스 지문 | 자료 | 토큰 | 미적중 생성 | 적중 로드 |
| --- | --- | ---: | ---: | ---: |
| `0ef8073a…` | R4, 전체 본문 | 366,669 | 17,574 ms | - |
| `04c51cd4…` | R4, 증상 중심 | 155,721 | 11,048 ms | 764 ms(n=1) |
| `7f3d9e30…` | R5, 증상 중심 | 155,721 | 27,291 ms | 중앙값 898 ms, p95 1,214 ms(n=9) |

- 준비 시간 감소율 = 1 − 적중 중앙값 / 미적중 생성 시간
    - `7f3d9e30…`: 1 − 898 / 27,291 = 96.7%
    - `04c51cd4…`: 1 − 764 / 11,048 = 93.1%
- 다른 질문 재사용: 상담 평가 9회(사례 묶음과 검색어 요청 2~18회가 서로 다름) 모두 캐시 적중, 문서 임베딩 토큰 0

**오프라인 측정(API 왕복 제외)**

| 항목 | 값 |
| --- | ---: |
| 생성 로컬 처리 중앙값(p95, n=5) | 1,127.6 ms(1,157.4 ms) |
| 캐시 로드 중앙값(p95, n=20) | 1,331.1 ms(1,653.0 ms) |
| 생성 시 문서 임베딩 요청 | 문서 25회 + 차원 확인 1회 |
| 로드 시 문서 임베딩 요청 | 0회(첫 검색 때 차원 확인 1회) |
| 캐시 파일 크기 | 34.1 MB |
| 서로 다른 질문 33개 처리 중 추가 문서 임베딩 | 0회(검색어 임베딩 26회만 발생) |

- 로컬에서는 캐시 로드(중앙값 1.33초)가 API 왕복을 뺀 생성(1.13초)보다 느림
    - 로드 경로: 34 MB 파일의 SHA-256 계산, 검증용 JSON 파싱, 저장소 적재용 JSON 파싱
    - 캐시의 효과는 로컬 계산 절약이 아니라 외부 API 왕복 25회 제거
- 실제 생성 시간 11.0~27.3초 중 로컬 처리는 약 1.1초, 나머지는 API 응답 대기
- 오프라인 로드 시간은 테스트 JVM(최대 힙 512 MB) 기준, 실제 기록의 적중 로드 중앙값 0.90초와 실행 환경이 다름

### 5.8 정확성 유지·적용 범위·한계

**정확성 유지**

- 로드한 인덱스와 생성한 인덱스의 33개 질의 결과: 일치(문서 ID·일치 방식·유사도)
- 페이로드 변조: 캐시 미사용, 다시 생성(문서 25회 + 차원 확인 1회)
- 모델 이름 변경: 다른 캐시 파일 사용, 다시 생성

**적용 범위와 한계**

- 운영 적중률 자료 없음, 배포·재시작 빈도를 몰라 절감 총량은 산정하지 않음
- 미적중 시간은 지문별 1회 측정, 같은 토큰 수에서도 11.0~27.3초로 API 응답 변동이 큼
- 금액 효과는 재시작 1회당 약 $0.0031로 작음, 주된 효과는 외부 호출 제거와 준비 시간 단축
- 준비 중에도 어휘 검색으로 응답하므로 첫 응답 차단 시간이 아니라 의미 검색 사용 가능 시점이 앞당겨짐
- 4장 결과대로 이 평가 세트에서는 의미 검색 유무가 통과 여부를 바꾸지 않음
- 운영 영속성은 팀원이 추가한 캐시 볼륨에 의존, 쓰기 불가하면 메모리 인덱스만 사용

### 5.9 개인 기여 근거

- `KnowledgeVectorIndex` 구현 `9af5760`: 작성자 `Sjoneon`
- 운영 캐시 볼륨·디렉터리 `421065a`: 작성자 `gwonu1`
- `f149048`(`gwonu1`): null 안전성 정리, 캐시 로직 변경 없음

### 5.10 판정

- **A**
- 사용 범위
    - 자료·모델 설정 SHA-256 지문 기반 임베딩 캐시로 재시작 시 문서 임베딩 호출 제거(25요청·155,721토큰 → 0)
    - 의미 검색 준비 11.0~27.3초 → 중앙값 0.90초(실 API 기록)
- 제외할 표현: 비용 절감 총량, 검색 응답 속도 개선, 캐시 로드가 로컬 계산보다 빠르다는 표현
- 시간 수치를 단일 값으로 쓰려면 미적중 생성의 반복 측정 필요(유료, 1회 약 $0.003)

---

## 6. 그 밖의 검토 후보

| 후보 | 판정 | 사유 |
| --- | --- | --- |
| 선택지 문구 일치 시 해석 생략 | B | 처음부터 있던 설계라 비교 기준 없음 |
| 위험 판정 키워드 선검사 | C | 안전 설계 목적, 비교 측정 없음 |
| 검색어 임베딩 LRU | C | 같은 질문 반복에서만 효과 |
| 의미 검색 2초 상한·어휘 대체 | C | 지연 상한 장치, 속도 개선 아님 |
| 임베딩 텍스트 축소 | C | 품질 목적 변경의 부수 효과 |
| `__pycache__` 측정 | C | 최적화하지 않기로 한 판단 근거 |
| DB 복합 인덱스 | C | 실행 계획·시간 측정 없음 |
| 채팅 요청 제한 토큰 버킷 | C | 보호 장치, 성능 개선 아님 |
| 상담 평가 지연 p50·p95 변화 | C | 품질 수정 사이 비교, 실제 모델 지연 변동 |

**선택지 문구 일치 시 해석 생략**

- 구현: `MessageInterpreter.interpret`(커밋 `08146fe`, 작성자 `Sjoneon`)
    - 서버가 제시한 선택지와 같은 문장이면 생성형 해석 호출 없이 처리
- 측정: 3.7의 변경 전 단계 응답 5회에서 해석기 호출 0회
- 추가 측정: 실제 상담의 버튼 응답·직접 입력 비율, 해석 호출 1회의 실제 지연

**위험 판정 키워드 선검사**

- 구현: `RiskAssessmentService.assess`(커밋 `624a5ea`)
    - 규칙 후보가 없으면 AI 호출 없음, 긴급 신호는 AI를 기다리지 않음
- 목적: 안전 안내 지연 방지, 비교 대상 구현 없음

**검색어 임베딩 LRU**

- 구현: `KnowledgeVectorIndex` 256개 접근 순서 `LinkedHashMap`
- 효과 범위: 같은 질문 문자열 반복, 운영 적중률 자료 없음

**의미 검색 2초 상한·어휘 대체**

- 구현: 전용 스레드 2개·대기열 8개, 2초 초과 시 `LEXICAL_FALLBACK`
- 기록: 최종 검색 평가에서 1건(2,028 ms) 대체, 꼬리 지연 상한 장치

**임베딩 텍스트 축소**

- 기록: 문서 임베딩 토큰 366,669 → 155,721(57.5% 감소), 1회 생성 17,574 ms → 11,048 ms
- 같은 155,721토큰 인덱스의 다른 생성 기록이 27,291 ms라 시간 비교는 불가

**`__pycache__` 측정**

- 기록: [pycache 측정][pycache](커밋 `9493717`, 작성자 `Sjoneon`)
- 결과: 자체 코드 캐시 효과 기동 1회당 약 2 ms, 쓰기 금지 설정 유지 판단의 근거

**DB 복합 인덱스**

- 구현: `idx_product_owner(user_id, category, id)`, `idx_tool_calls_conversation(conversation_id, id)`(커밋 `e607f52`)
- 측정 없음: 데이터 규모·실행 계획·조회 시간 기록 없음

**채팅 요청 제한 토큰 버킷**

- 구현: [ChatRateLimiter.java][rate-limiter](커밋 `66b76db`)
- 목적: 사용자별 요청 보호, 처리 성능 개선이 아님

**상담 평가 지연 p50·p95 변화**

- 기록: 기준 실행 859 ms·11,050 ms → 최종 실행 827 ms·9,147 ms(46턴, 실제 `gpt-4o-mini`)
- 판단: 두 실행 사이 변경은 답변 품질 수정, 실제 모델 응답 지연 변동과 구분 불가

---

## 7. 관련 문서

- [제조사 자료 검색 연결][retrieval-doc]
- [상담 설계 결정 기록][design-decisions]
- [MCP 구현][mcp-impl]
- [MCP 서버 기동 시간: `__pycache__` 유무 비교][pycache]
- 측정 원자료: [summary.json][summary], [results/][results]

[results]: optimization_audit/results/
[summary]: optimization_audit/results/summary.json
[run-audit]: optimization_audit/run_audit.py
[probe-self-check]: optimization_audit/probes/SelfCheckRequestProbe.java
[probe-self-check-timing]: optimization_audit/probes/SelfCheckRequestTimingProbe.java
[probe-retrieval]: optimization_audit/probes/RetrievalAuditProbe.java
[probe-index-cache]: optimization_audit/probes/IndexCacheProbe.java
[retrieval-initial]: manufacturer_retrieval_initial_20261014.json
[retrieval-final]: manufacturer_retrieval_20261014.json
[pycache]: pycache_benchmark.md

[retrieval-doc]: ../mcp/manufacturer_retrieval.md
[design-decisions]: ../mcp/consultation_design_decisions.md
[mcp-impl]: ../mcp/mcp_implementation.md
[retrieval-eval]: ../mcp/retrieval_evaluation.json

[agent-answer]: ../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java#L256
[agent-card]: ../../backend/src/main/java/com/project/backend/mcp/agent/AgentChatService.java#L239
[consultation-controller]: ../../backend/src/main/java/com/project/backend/mcp/consultation/ConsultationController.java#L65
[rate-limiter]: ../../backend/src/main/java/com/project/backend/chat/ChatRateLimiter.java#L27
[web-config]: ../../backend/src/main/java/com/project/backend/config/WebConfig.java#L17
[retriever]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetriever.java#L50
[retriever-filter]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetriever.java#L58
[retriever-abstain]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetriever.java#L87
[text-matcher]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeTextMatcher.java#L10
[text-matcher-lexical]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeTextMatcher.java#L50
[retrieval-policy]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetrievalPolicy.java#L50
[retrieval-eval-code]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetrievalEvaluation.java#L59
[vector-index]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java
[vector-index-fingerprint]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java#L82
[vector-index-prepare]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java#L94
[vector-index-batch]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java#L102
[vector-index-load]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java#L130
[vector-index-timeout]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndex.java#L175
[vector-index-test]: ../../backend/src/test/java/com/project/backend/mcp/retrieval/KnowledgeVectorIndexTests.java
[compose-prod]: ../../ops/compose/compose.prod.yaml#L20
[consultation-card]: ../../frontend/src/components/ConsultationCard.jsx#L60
[chat-jsx]: ../../frontend/src/Chat.jsx#L258
[verify-display]: ../../frontend/scripts/verify-display.mjs
