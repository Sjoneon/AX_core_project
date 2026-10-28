# HomeCare AI MCP 도구 9개 구현 · v3.0

> Spring AI 단일 Agent, Java MCP 클라이언트, FastMCP 어댑터로 구성한 상담 도구 9개의 구조·계약·검증 범위
> 작성일: 2026-10-13 · 대상: `core_project` · 품목: 냉장고(김치냉장고 포함), 세탁기, 건조기, 식기세척기

**목차**

1. [구현 결과](#1-구현-결과)
2. [구조와 인증](#2-구조와-인증)
3. [답변 생성과 장애 대체](#3-답변-생성과-장애-대체)
4. [보증의 구분](#4-보증의-구분)
5. [상담 상태와 안전](#5-상담-상태와-안전)
6. [연결한 원자료와 무결성](#6-연결한-원자료와-무결성)
7. [단계별 기록](#7-단계별-기록)
8. [웹 API](#8-웹-api)
9. [도구 호출 기록](#9-도구-호출-기록)
10. [실행·배포](#10-실행배포)
11. [검증 범위](#11-검증-범위)

---

## 1. 구현 결과

### 1.1 역할 분담

- Spring AI 단일 Agent: 실제 MCP discovery로 받은 도구 선택
- 호출 방식: Java MCP SDK의 Streamable HTTP로 FastMCP 호출
- FastMCP: 기존 로그인 JWT를 요청별로 전달하는 얇은 어댑터, 데이터를 갖지 않음
- Spring: 데이터와 모든 판정(소유권·상태·보증·비용)을 한 곳에서 처리
- MCP 서버에는 OpenAI 키나 별도 LLM 없음
- 모든 도구는 읽기 전용, 쓰기·예약·결제 도구 없음

### 1.2 도구 목록

| MCP 도구 | 하는 일 | 현재 자료의 제한 |
| --- | --- | --- |
| `lookup_model` | 고객이 말한 모델 코드를 판매 코드 52개와 정확히 일치할 때만 조회. 원문 보존, 접미사 미절단, 대표 모델은 공통 자료용 | 상담 품목과 다르면 경고만. 비슷한 코드를 제안하지 않음 |
| `search_knowledge` | 제조사 전용 검색: 적용 필터·정확 코드/표시·증상 어휘·캐시 임베딩. 관련 안전 최대 2건 우선, 보완은 별도 연결 | 모델 미판정은 검증된 품목 공통 범위만. 근거 부족은 NO_DATA/NEEDS_INPUT, 의미 검색 장애는 어휘 대체 |
| `get_product_context` | 본인 등록 제품 후보·자동 연결된 상담 제품 조회 | 같은 품목 하나면 자동 연결, 여러 개면 표시 이름·전체 모델명으로 확인 |
| `get_diagnostic_plan` | 위험 차단, 승인된 모델·오류의 자가검진을 한 단계씩 조회 | TR15WV5/UE/세탁물 뭉침 1건. 선행 조건 6개 확인 후 현재 단계 1개, 나머지는 `NOT_VERIFIED` |
| `check_warranty` | 품질보증(구입일 기준)과 주요 부품 보증을 분리, 부품보유기간(제조일 기준)을 별도 블록으로 | 구입일이 없으면 `NEEDS_INPUT`. 부품보유기간은 B30 원본 확인 전까지 `UNAVAILABLE` |
| `get_repair_candidates` | 근거 원인 최대 2개·비용 입력·허용 부품과 `candidate_set_id` | 부품 가격 문의와 고장 원인 구분. 목록 포함만으로 부품 고장을 추정하지 않음 |
| `estimate_repair_cost` | 연결 근거·확신도·국내 사례 3건 확인 후 참고 범위·신뢰도·방문 가산 | 미충족은 NO_DATA. 비용 대안을 합산하거나 보증·출장비 미확인을 0원으로 바꾸지 않음 |
| `find_service_centers` | 제품 배송지 시·군·구 → 시·도 → NONE, 공식 경로 조회 | 공식 확인한 센터 3곳만 시드. 거리순·전국 전체 자료가 아님 |
| `prepare_service_handoff` | 확인한 사실을 복사할 요약과 공식 접수 경로로 구성 | `PREPARED_ONLY`. 외부 전송·예약·전화 발신 없음 |

### 1.3 공통 결과 필드

| 필드 | 의미 |
| --- | --- |
| `status`, `data`, `evidence`, `missing_fields`, `warnings`, `error`, `meta` | 기존 봉투. `warnings`는 상황별 경고, `meta`는 계약 버전 3.0.0·데이터 버전·상담 버전·trace_id |
| `source` | 데이터 버전, 읽은 파일(`homecare/parts.json` 등), fact ID(근거 ID) |
| `confidence` | HIGH·MEDIUM·LOW·UNDETERMINED. 비용은 수리비 표 등급(중간→MEDIUM, 낮음→LOW) |
| `caveats` | 고정 해석 고지. 첫 줄은 항상 "도구 결과는 후보 정보이며 조작·무상 보증·부품 장착을 승인하지 않습니다" |
| `independent_action_allowed` | 항상 false. Java 레코드가 true를 거부하고 Python 어댑터도 `Literal[False]`로 검사 |

- 계약 파일: [mcp-tools.contract.json][contract], `python -m scripts.export_contract`로 discovery에서 생성
- CI가 계약과 코드의 불일치를 실패로 처리

---

## 2. 구조와 인증

```text
React → /api/chat → Spring Agent → Java MCP Client → FastMCP /mcp (내부 전용, Host/Origin 엄격 검증)
                                                    ↓ 요청별 JWT + context version
                                         Spring /api/tools/{tool} → 소유권/상태/자료/규칙 계산
```

### 2.1 인자 제한

- LLM에 공개하지 않는 인자: `conversation_id`, 수동 보증 `request`, `region_override`
    - 서버가 인증된 상담 ID와 버전을 주입
- 인자가 없는 값: 사용자 ID, 이메일, 토큰, 가격, 보증기간, 기준일
- 허용되지 않은 인자: Python(Pydantic `extra=forbid`)과 Spring 양쪽에서 거부
- `model_code`
    - 바깥 공백 제거·대문자 변환 후 `[A-Z0-9./-]` 1~40자만 허용
    - 최근 고객 문장이나 등록 제품에 실제로 있는 코드만 조회
    - 문서·도구 결과에 섞인 지시로 다른 모델을 조회할 수 없음
- `month`: 정수 1~12
- `time_slot`, `product_category`: 열거값만
- `query`: 1~300자
- `top_k`: 1~5

### 2.2 소유권과 한도

- 상담·제품은 본인 소유만 조회(`OWNERSHIP_NOT_FOUND`)
- `X-HomeCare-Context-Version`이 다르면 409(`CONTEXT_STALE`)
- 한도 설정 위치: `homecare.agent.*` 한 곳

| 한도 | 값 |
| --- | --- |
| 한 질문의 도구 호출(서버 정책 호출 포함) | 5회 |
| 도구별 호출 | 2회 |
| MCP RPC별 시간 | 5초 |
| 세션 시간 | 20초 |

- 한도에 닿으면 그때까지의 결과로 답변

### 2.3 오류와 외부 차단

- 인증·소유권·상태 오류: 요청 종료
- 인자 오류·도구 장애: 코드만 모델에 반환(`{"error":"INVALID_ARGUMENT"}`)
- `/api/tools/`: Nginx에서 404로 차단, Spring도 `X-Forwarded-For`가 있는(=Nginx를 거친) 요청을 404로 차단
- 로그: trace_id·도구·상태·소요 시간만, FastMCP의 인자 검증 실패 로그에서도 입력 값 제거

---

## 3. 답변 생성과 장애 대체

### 3.1 답변 생성

- LLM의 역할: 메시지 구조화, 도구 선택, 최종 문단 구성
- 4단계의 `AnswerContext/GroundedAnswerGenerator`: 검증된 설명의 참조·문단·연결 문장 생성, 본문은 서버가 복원
- 금액·기간·날짜·연락처: 서버의 도구 안내 문장 그대로 유지
- 생성 결과 구분: `tools.answer_generation`의 `MODEL_GROUNDED`와 `FIXED_FALLBACK`, 사유·근거 ID·생성 시간
- 생성·근거 검증 실패와 시간 초과: 기존 고정 안내
- 최종 생성은 도구를 호출하지 않고 기존 MCP 세션의 남은 예산과 기본 5초 제한 사용
- 참고: [구성·검증·14개 실제 모델 평가](grounded_answer_generation.md)

### 3.2 답변 규칙

- 안전 문장은 항상 맨 앞
- 모델 코드를 찾지 못하면 라벨 확인 요청
- 구입일이 없으면 구입일 질문
- 품질보증(구입일)과 부품보유(제조일)는 다른 기간이라고 설명
- 수리비 안내에 함께 쓰는 것: "참고용 예상 범위(견적 아님)", 신뢰도, 평일 출장비 28,000원 기준, 고지문

### 3.3 장애 대체

- 대상: MCP 연결·초기화 실패, OpenAI 장애
- 동작: LLM 없이 고정 안내, LG전자 고객센터, 출장 예약 링크로 답하고 상담 저장(`agent_fallback`)
- 로그: trace_id·사유·예외 클래스만
- 비에이전트 `ChatService`는 가상 테스트 매뉴얼 기반이라 대체 경로로 쓰지 않음

---

## 4. 보증의 구분

- 정책·날짜 계산·4품목별 기간: [warranty_implementation.md](warranty_implementation.md)
- 정책 확인 출처: [LG 공식 요금·보증 안내][lg-warranty]

### 4.1 구매일 기준 계산(2026-10-15 요구사항)

- 보증 시작일: 등록 구매일
- 구매일이 있으면 증빙·가정용 사용 미확인이 기간 상태·만료일 계산을 막지 않음
- 실제 무상·비용 조건과 주요 부품 적용 조건은 별도 유지
- 배송일은 기산일로 우선하지 않음
- 참고: [등록 제품의 구매일 기준 보증 검수](purchase_date_warranty_verification.md)

### 4.2 부품 보증과 비용

- 제품 전체 보증이 끝나도 해당 부품의 별도 보증은 남을 수 있음
- 핵심 부품의 별도 보증을 다른 부품에 적용하지 않음
- `check_warranty`: 부품비·기술료·출장비 항목을 조건부(`CONDITIONAL_FREE`)·유상·확인 필요로 표시, 개인별 무상 여부나 면제는 판정하지 않음
- 비용 도구: 보증을 금액 0원으로 바꾸지 않고 "무상 수리 대상일 수 있음, 점검 후 결정" 문장만 추가

---

## 5. 상담 상태와 안전

### 5.1 제품 연결과 자가검진

- 같은 품목의 등록 제품이 하나면 상담 시작 시 자동 연결
- 여러 제품이 있으면 입력한 표시 이름·정확한 모델명으로 구분
- 다른 모델을 입력하면 보유 제품으로 대체하지 않음
- 상담 화면에는 별도 제품 선택 패널을 두지 않음
- 자가검진: `actionApproved=true`이고 정확한 모델·현재 오류 또는 증상이 일치하는 계획만 사용, 한 단계씩 진행

### 5.2 위험 판정

- 위험 후보: `RiskRules`의 14개 사례 키워드로 탐지
- 의심 문장: `RiskAssessmentService`가 최대 8초 안에 AI 문맥으로 판정
- 판정 실패 시 키워드 후보 유지
- 긴급 신호: AI나 MCP를 기다리지 않고 즉시 안내
- 인화성 오염: RC14
- 위험 확인 버튼·이력·조치 차단은 일반 증상 해석과 별도

### 5.3 증상 해석과 대화 상태(2026-10-14)

- `MessageInterpreter/DialogueRouter`: 품목별 5개 증상, 비용·보증·A/S 문의, 확인 질문·응답과 후속 대화 연결
- 상태 저장: 기존 상담 JSON, 최근 대화 12개 전달
- 해석 실패·8초 초과: 증상 선택 제공, 질문 뒤에도 모르면 `UNCLASSIFIED`로 안내
- 완료 조치: 정확한 모델의 승인 단계와 대조(3단계 당시 `approved-plans.json=[]`)
- 5단계의 조건부 계획 등록은 아래 기록과 분리
- `tools.consultation`: 화면용 서버 상태 결과이며 MCP 도구가 아님
- 참고: [분류·상태·38개 실제 모델 평가](symptom_interpretation.md)

### 5.4 조건부 자가조치(5단계)

- 조건 6개를 모두 확인하기 전에는 조치를 반환하지 않음
- 제품 수정·위험 감지는 확인 값 초기화
- 현재 승인 단계만 안내하고 결과와 다음 안내를 같은 트랜잭션에 저장
- `precondition-answer`, `step-answer`: `ChatResponse` 반환
- 실행 경로 검토는 제조사 승인·실물 시험을 의미하지 않으며, 진동은 미승인 상태 유지
- 참고: [원문 검토·조건·이력 계약](approved_self_care.md)

---

## 6. 연결한 원자료와 무결성

### 6.1 매니페스트

- 위치: `backend/src/main/resources/homecare/source-manifest.json`(버전 `B30-COST-V3-20261014-R5`)
- 저장 내용: 원본·생성 자료의 출처·해시·제약
- Spring 기동 시 검사: 데이터 파일 9개(homecare 8개 + `warranty/policies.json`)의 SHA-256과 건수
- 하나라도 다르면 기동하지 않음
- 데이터 JSON은 `.gitattributes`로 줄바꿈 변환 차단

### 6.2 제조사 지식

- 사용 범위: LG B30 전체 사실 1,659건 중 현재 로컬에 들어온 1,542건
- 기존 검수에서 발견한 누락 117건(김치냉장고 97건 포함)은 구조화 작업으로 복원되지 않음
- 원본 파일에 그대로 남은 값: 각 기록의 `effective_model_lists`, `current_QA_review`, `latest_applicability_update`, `procedure_supplements`, `use_policy`
    - Spring이 기동 시 읽어 적용 판정에 사용(최신 적용 목록 우선, 제외 모델 제거)
- 구조화(2026-10-14)
    - 기존 원본 1,542건을 변경 없이 보존하고 `structure_knowledge.py`로 `knowledge-structured.json` 1,542건 생성
    - 검색 본문 `searchText`와 화면 본문 `displayText` 분리
    - 보존: 원인별 분기, 선행 조건, 그림/실물 제약, 보완 관계
    - `search_knowledge`와 자가검진 `references`는 구조화 결과를 사용하며 원본 래퍼 JSON을 반환하지 않음
    - 참고: [대상 자료·원문 대조·생성/검증 기록](manufacturer_data_structure.md)
- 검색 연결(2026-10-14, 2단계)
    - 제조사 전용 `KnowledgeRetriever/KnowledgeVectorIndex`를 MCP 경로에 연결
    - 1,542개 사실 임베딩, `SimpleVectorStore`의 모델 후보 ID 필터·버전별 디스크 캐시·2초 대체 경로 사용
    - 실제 임베딩 평가 33개와 구현 검사 통과
    - 참고: [검색·캐시·비용·평가 기록](manufacturer_retrieval.md)

### 6.3 수리비

- 원본: `한국기준_예상수리비_표_v3.csv`(SHA-256이 매니페스트 `costSha256`과 일치)
- 변환: `backend/scripts/build_homecare_data.py`
- 사용 그룹: 33개 중 32개
    - 제외: D02 '히터(미국 전기식)', CSV 신뢰도 열에 "제외(미국형 전기식 건조기 전용 부품)"
- 출장비 가산(CSV 열 그대로 사용)
    - 야간·휴일 +5,000원
    - 6~8월 평일 +5,000원
    - 6~8월 야간·휴일 +10,000원(야간 가산과 중복 합산하지 않음)
    - 해석 확인: 패키지 `model_v3.py`의 `SURCHARGE`, `normalize.py`의 출장비 28,000/33,000/33,000/38,000원
    - 고정 가산이므로 중앙·하한·상한에 같은 금액 추가
- v3 성능: 국내 LG 유상 20건 검증에서 중앙 절대오차 11.2%, 80% 범위 적중 80%
    - 같은 20건으로 등급·범위를 골랐으므로 새 사례 오차는 더 클 수 있음(비용 결과 `caveats`에 포함)

### 6.4 부품보유기간과 기타 자료

- 부품보유기간
    - 변환본 T5 기록에 `parts_holding_period` 문장이 있으나 식기세척기 품목 공통 5년(T5-004)과 모델별 7년(T5-009 등)이 충돌
    - B30 원본은 로컬에 확보, 2026-10-15 체크섬 재확인
    - 이 정책 충돌의 원문 대조·제조일 기준 구조화는 미완료라 값을 제공하지 않음
    - 런타임의 "원본 확인 전" 문구도 이 상태를 정확히 표현하도록 후속 수정 필요
- `repair-cases.json=[]`
- `approved-plans.json`: TR15WV5/UE/세탁물 뭉침 조건부 계획 1건
- 센터: 대치·분당·북수원 3곳

### 6.5 B30 사용 점검(2026-10-15)

- [B30 실제 사용·냉장고 답변 점검](manufacturer_data_usage_audit.md): 원본 1,659건과 서비스 1,542건 직접 대조
    - 서비스 래퍼는 해당 B30 원문과 모두 일치하며 B29 사실도 사용
    - 구분한 문제: 누락 117건, 설명 필드의 답변 전달 제한, 등록 구매일의 보증 판정, 주소·센터 조회 연결
- 센터 3곳을 전국 조회처럼 사용하는 경로의 교체 방향: [A/S 운영 설계](service_channel_operating_plan.md)
- 두 문서는 확인 결과와 개선 설계이며 런타임 수정 완료 기록이 아님

---

## 7. 단계별 기록

| 단계 | 기록 | 내용 |
| --- | --- | --- |
| 1 | [제조사 자료 구조화](manufacturer_data_structure.md) | 원본 보존, 검색·화면용 구조화 자료 생성 |
| 2 | [제조사 자료 검색 연결](manufacturer_retrieval.md) | 적용 필터, 임베딩 캐시, 실제 임베딩 평가 33개 |
| 3 | [자연어 증상 분류와 대화 상태](symptom_interpretation.md) | 증상 분류, 확인 질문, 실제 모델 평가 38개 |
| 4 | [근거 기반 최종 답변 구성](grounded_answer_generation.md) | 근거 검증, 본문 복원, 실제 모델 평가 14개 |
| 5 | [조건 확인과 승인된 자가조치](approved_self_care.md) | TR15WV5/UE 조건부 경로 1개 |
| 6 | [원인·비용·보증·공식 서비스 연결](repair_followup.md) | 필수/보완 호출, 후속 연결 |
| 7 | [상담 화면 표시](consultation_display.md) | 공통 표시, 과거 JSON 투영, DEV 원본 분리 |
| 8 | [실제 상담·답변 평가](consultation_validation.md) | 실제 모델 상담 평가와 회귀 검증 |

### 7.1 6단계 요약

- 문의·상태에 따른 필수/보완 호출을 POLICY_FALLBACK으로 기록
- 원인 후보의 부품 연결 근거가 부족하면 확인 질문을 한 번 진행
- 자가조치 미해결·조치 불가·실행 미승인은 실제 사유를 유지해 보증·센터·전송 전 요약으로 연결
- 보완 호출도 기존 호출 수/시간 한도 공유

### 7.2 7단계 요약

- 새 답변·확인 응답·저장 이력을 같은 컴포넌트로 표시
- 근거의 제목·설명·적용 조건·공식 출처를 구분하고, 자가검진/검색/저장 근거의 중복 제거
- 과거 B30 JSON은 표시 시점에만 읽으며 저장 원문을 변경하지 않음
- 내부 메타데이터·파일 경로·원본 JSON은 기존 DEV 영역에서만 확인
- 화면의 승인 단계는 서버 결과로만 표시

### 7.3 8단계 요약

- 실행 구성: 기존 키·모델, 격리 H2, 실제 HTTP/JWT/FastMCP
- 분류·도구·상태와 사용자 질문에 대한 답변 적합성을 별도 기록
- 자료 부족·전송 전 요약·사용자 해결 보고는 수리 완료로 계산하지 않음
- 접수 답변에 실제 공식 링크·센터·연락처 포함, 일반 증상/비용 문의의 무관한 반복 보증 설명 축소
- 명백한 증상은 모델 오류 시 제한된 어휘 대체로 보존(인용·부정·타 품목·지시문 제외), AI 성공으로 계산하지 않음

---

## 8. 웹 API

기존 JWT 인증을 사용합니다.

| API | 용도 |
| --- | --- |
| `GET/POST /api/me/products` | 내 제품 조회/등록 |
| `GET /api/warranty/policies`, `POST /api/warranty/check` | 정책 조회, LLM 없이 직접 보증 계산 |
| `POST /api/chat` | 상담. 사용자별 분당 5회(연속 3회)·하루 100회, 초과 시 429 + `Retry-After` |
| `GET /api/conversations/{id}/context` | 현재 제품·위험·조치 상태 |
| `GET /api/conversations/{id}/tool-calls` | 본인 상담의 도구 호출 기록(V005) |
| `POST /api/conversations/{id}/product`, `/risk-answer` | 제품 확정, 위험 확인 |
| `POST /api/conversations/{id}/precondition-answer`, `/step-answer` | 현재 조건·조치 결과를 상태와 이력에 저장하고 다음 안내의 `ChatResponse` 반환 |
| `POST /api/tools/{name}` | MCP 컨테이너 전용(외부 차단) |

Nginx의 IP별 제한(초과 시 429)

| 경로 | 제한 |
| --- | --- |
| `POST /api/chat` | 분당 30회(burst 10) |
| `/api/auth/` | 분당 10회(burst 5) |
| 나머지 `/api/` | 초당 10회(burst 30) |

---

## 9. 도구 호출 기록

- 테이블: `chat_tool_calls`(V005)
- 저장 항목: 답변별 호출 순서, 도구 이름, 허용 목록 인자, 서버 주입 필드, 선택 주체, 상태·오류 코드, 결과 요약, 소요 시간, trace_id
- 저장하지 않는 것: 고객 원문, 이메일, 토큰(검색어는 글자 수만)
- `chat_messages.tool_results`: 화면용 전체 결과로 유지
- 삭제: 상담 삭제 시 함께 삭제, 90일 뒤 자동 삭제
- 적용 절차: [rds_initialization.md](../../ops/docs/rds_initialization.md)

---

## 10. 실행·배포

1. 로컬 실행: [mcp_server/README.md](../../mcp_server/README.md)
2. 운영 DB: 배포 전 V002 → V003 → V004 → V005를 1회 적용(운영 `ddl-auto=validate`)
    - 이 작업에서는 운영 DB에 적용하지 않음
3. CI/CD: backend·nginx·mcp 이미지 3개를 게시하고 `compose.prod.yaml` + `compose.mcp.yaml`로 배포, 실패 시 이전 릴리스로 복원
    - 선행 작업: Terraform ECR `mcp`·로그 그룹 생성, GitHub 변수 `ECR_MCP_REPOSITORY` 추가
4. `HOMECARE_AGENT_ENABLED`: 현재 코드 기본값 true
    - 운영은 실제 컨테이너의 명시적 환경변수와 MCP 연결 상태 확인 필요
    - 이 로컬 검증에서 운영 배포·기동 설정은 변경하지 않음

---

## 11. 검증 범위

### 11.1 백엔드 JUnit

- 실제 Java SDK → FastMCP HTTP → Spring의 9개 도구 호출과 호출 한도
- 모의 OpenAI의 정해진 tool call로 E2E 4개 시나리오
    - 모델 문장→조회→근거→부품→비용
    - 타는 냄새 안전 우선
    - 구입일 없는 보증
    - MCP 장애 대체 응답
- 답변 숫자와 도구 결과의 대조, 주입된 인자 거부, 호출 기록·로그의 개인정보, 상담 삭제 시 기록 삭제
- 데이터 변조 시 기동 실패, 수리비 CSV 대조(원본이 있을 때), 속도 제한

### 11.2 Python과 CI

- Python: FastMCP discovery/call, 인자 거부, 원문 전달, 토큰 없는 호출 거부, 로그 마스킹, Host/Origin 검증
- CI: MySQL 8.4 V001~V005 + validate 검사, Docker 이미지, Nginx 프록시 시험, 프론트엔드 lint/build
- 실 OpenAI의 도구 선택 정확도는 수동 절차로 별도 확인

[contract]: mcp-tools.contract.json
[lg-warranty]: https://www.lge.co.kr/support/rates-warranty-guide
