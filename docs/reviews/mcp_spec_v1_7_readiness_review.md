# 기능명세 v1.7·DB v0.4·수리비 v3 대비 MCP 작동 가능성 검토

> 기능명세 v1.7, DB 설계서 v0.4, 수리비 v3 기준으로 본 MCP 구현의 작동 범위와 부족 사항
> 검토일: 2026-10-13 (Asia/Seoul) · 대상: 당시 작업 폴더의 수정·미추적 파일을 포함한 구현
> 이 검토에서 기능 코드와 운영 DB를 변경하거나 배포하지 않음

**목차**

1. [결론](#1-결론)
2. [확인한 원자료](#2-확인한-원자료)
3. [실제 검증 결과](#3-실제-검증-결과)
4. [현재 구현으로 가능한 범위](#4-현재-구현으로-가능한-범위)
5. [주요 부족 사항](#5-주요-부족-사항)
6. [수리비 v3 연결 범위와 한계](#6-수리비-v3-연결-범위와-한계)
7. [원자료끼리 먼저 정리해야 할 차이](#7-원자료끼리-먼저-정리해야-할-차이)
8. [완료를 위한 권장 순서](#8-완료를-위한-권장-순서)

---

## 1. 결론

- **현재 `mcp_server`: 9개 조회 도구를 Spring에 연결하는 기반으로 작동**
- **기능명세 v1.7의 전체 MVP를 수행하기에는 부족**
- 부족한 부분 대부분: MCP 전송 계층이 아니라 Spring 상담 흐름, 자료, 화면, DB 계약
- 권장: MCP 서버를 다시 만들기보다 현재 어댑터를 유지하고 위 부분 보완
- 판정 구분
    - 통과: 실제 FastMCP HTTP와 Java 클라이언트를 쓰는 개발 통합 테스트
    - 이번 실행으로 확인하지 않은 것: 실 OpenAI의 도구 선택 품질, 실제 MySQL 스키마 적용, 운영 기동
    - "프로토콜 연동 가능"과 "명세 시나리오 완료"를 별도로 판정

---

## 2. 확인한 원자료

- Drive의 [LGAX_Core_project → data → 기능 명세 및 DB][drive-spec-folder]에서 기능명세·DB 설계서·SQL·시퀀스 4개 원본을 내려받아 확인
- 4개 모두 최초 검토에 쓴 바탕화면 로컬 파일과 바이트 단위·SHA-256 일치
- 수리비 자료와 B30 안내: Drive에서 직접 확인
- 멘토링 v3: 로컬 문서로 검토

| 자료 | 읽은 원본 |
| --- | --- |
| 기능명세 | [Drive 기능명세 v1.7][drive-spec] |
| 사용자 흐름·시퀀스 | [Drive 시퀀스 v1.7][drive-sequence] |
| DB 설계·SQL | [Drive DB 설계서 v0.4][drive-db], [Drive homecare_ai_schema.sql][drive-sql] |
| 멘토링 | [2차 멘토링 자료 v3](<C:/Users/Roneon/Desktop/최종 기능 명세v0.4/HomeCare_AI_2차_멘토링_자료_피드백반영_v3.docx>) |
| 수리비 v3 | [Drive README][drive-cost-readme], [최종 CSV][drive-cost-csv], [실행 로그][drive-cost-log], [패키지 폴더][drive-cost-folder] |
| 제품·지식 자료 | [Drive README_B30][drive-b30], 현재 `homecare/*.json`·매니페스트·변환 코드 |

---

## 3. 실제 검증 결과

| 검사 | 결과·범위 |
| --- | --- |
| Drive 명세 원본 대조 | 기능명세·DB 설계서·SQL·시퀀스 4개 모두 최초 로컬 원본과 바이트 단위 일치. 문서 변경이 없으므로 이전 테스트 결과를 재사용 |
| Python MCP | `unittest discover -s tests -t . -q`: 20개 통과 |
| Java | JDK 21로 `gradlew.bat test bootJar --offline --no-daemon --rerun-tasks`: 130개, 실패 0, 오류 0, 건너뜀 0, JAR 빌드 성공 |
| 실제 discovery 계약 | 도구 9개, 저장된 `mcp-tools.contract.json`의 `tools`와 일치, 모두 `readOnlyHint=true` |
| Java↔MCP↔Spring | `McpIntegrationTests` 포함. 실제 HTTP 전송·소유권·상태 버전·후보 묶음·모의 OpenAI 흐름 검사 통과 |
| v3 CSV 대조 | Drive와 로컬 CSV의 헤더 및 33개 자료 행 전체 일치. Java `CostTableSourceTests`도 건너뛰지 않고 통과 |
| 자료 건수 | 판매 코드 52개, 지식 1,542개, 수리 부품 그룹 32개, 매핑된 그룹 0개, 승인 계획 0개, 후기 0개, 센터 3개 |
| 실 OpenAI | 미실행. 당시 도구 실행 환경에 `OPENAI_API_KEY`, `OPENAI_MODEL`, `JWT_SECRET`이 설정되지 않았고 backend에는 `.env.example`만 확인됨. 다른 IDE·배포 환경의 설정 유무는 미확인 |
| MySQL 원본 SQL·운영 DB | 미실행. 일반 Java 검증은 H2 사용, MySQL 전용 `mysqlSchemaTest`는 기본 테스트 집합에서 제외됨 |
| 프론트 lint/build | 당시 환경에서 `npm`을 찾지 못해 미실행. 화면 코드는 정적으로 검토 |

- Java의 Spring AI deprecated API 경고: 있었지만 빌드 실패는 없음
- 기존 자동 테스트 통과가 명세 T1~T19 전체 실행을 뜻하지 않음

Drive 원본 대조 SHA-256(로컬과 동일)

| 파일 | SHA-256 |
| --- | --- |
| 기능명세 v1.7 | `be1ea7360a082cb7fb8157abf758e56d07315d39a8d072b07d2a921b7a2cbfe3` |
| DB 설계서 v0.4 | `d80514656eeb3dcaa3af2a254e0336e8bb8471f5e58d402da48c98b8a1ee077f` |
| homecare_ai_schema.sql | `79f94254b7cf40e727c9a6925e6b5ce67092c349d025ca0e3d276f5cf9554cf5` |
| 시퀀스 v1.7 | `7300fb636489dc8fbe9d83e7db4bbb337ed6850bf632e2976b4f4c6b108b7808` |

---

## 4. 현재 구현으로 가능한 범위

### 4.1 구조

- 경로: `React → Spring Agent → Java MCP Client → FastMCP → Spring /api/tools/{name}`
- Python: 요청별 JWT와 상태 버전 전달, 결과 검증
- Spring: 실제 자료 조회와 보증·비용 계산
- `mcp_server` 단독으로는 DB·보증·비용 기능을 제공하지 않음

### 4.2 구현된 기능

- 모델 정확 일치 조회, 지식 후보 검색, 본인 제품 조회, 보증 계산, 참고 수리비 범위, 지역 센터 조회, 접수용 요약 준비
- 자동 테스트로 확인: 인증, 소유권, 상태 버전, 허용 인자, 후보 집합 검사, 오류 마스킹
- 승인되지 않은 자가검진은 `NOT_VERIFIED`, 자료가 부족한 비용은 `NO_DATA` 등으로 반환
    - 정상 동작이지만 해당 사용자 과업을 완료하지는 못함
- 센터: 시연 지역 3곳
    - 전국 전체 자료가 아닌 것은 명세의 시연 지역 제한과 양립
    - 지역 변경 화면은 별도 부족 사항

---

## 5. 주요 부족 사항

| 우선순위 | 항목 |
| --- | --- |
| 높음 | [5.1 상담 상태와 메시지 해석](#51-상담-상태와-메시지-해석) |
| 높음 | [5.2 승인된 자가검진 진행](#52-승인된-자가검진-진행) |
| 높음 | [5.3 비용 후보 경로](#53-비용-후보-경로) |
| 높음 | [5.4 비용 구성과 보증 분기](#54-비용-구성과-보증-분기) |
| 높음 | [5.5 위험의 문맥 판단과 복귀](#55-위험의-문맥-판단과-복귀) |
| 높음 | [5.6 요청 멱등성](#56-요청-멱등성) |
| 높음 | [5.7 DB v0.4 원본 SQL 연결](#57-db-v04-원본-sql-연결) |
| 중간 | [5.8 가입 주소와 제품 수정](#58-가입-주소와-제품-수정) |
| 중간 | [5.9 센터 지역 변경](#59-센터-지역-변경) |
| 중간 | [5.10 입력 길이·시간 제한·상담 종료](#510-입력-길이시간-제한상담-종료) |
| 중간 | [5.11 이용 이벤트와 측정](#511-이용-이벤트와-측정) |

### 5.1 상담 상태와 메시지 해석

- 명세: F-03/F-06/F-08, SD-03~05의 9개 상담 상태, 품목 불일치 확인, 제품 확인 뒤 분기, 미분류 질문
- 당시 구현
    - 상태는 위험·오류·단계·닫힘 일부만 보유
    - 자연어 품목·증상·이미 한 조치를 구조화하는 MessageInterpreter 없음
    - `symptomCode`를 실제로 채우는 경로 없음
    - 비용이 미등록 제품에도 허용되어 제품 확정 선행 조건이 강제되지 않음
- 코드 근거: `ConsultationState.java`, `ConsultationService.begin`, `AgentChatService`, `GetRepairCandidatesTool`, `EstimateRepairCostTool`

### 5.2 승인된 자가검진 진행

- 명세: F-09, T1~T3의 실제 모델에 승인된 단계 진행, 이미 한 조치 건너뜀
- 당시 구현
    - `approved-plans.json=[]`이라 실제 자가검진 시작 경로가 자료 부족으로 막힘
    - 버튼 결과를 처리할 기반은 있지만 자연어에서 이미 한 조치·해결 여부를 추출하지 않음
- 코드 근거: `GetDiagnosticPlanTool.java`, `ConsultationService.java`, `ToolBackend.answerStep`

### 5.3 비용 후보 경로

- 명세: F-10, SD-08의 매핑→AI→후기 3건 이상→기본 안내
- 당시 구현
    - 32개 그룹 모두 `targetCodes`가 비었고 `repair-cases.json=[]`
    - 실질 경로: AI 후보 선택 또는 자료 부족
    - 모델이 필요한 도구를 생략하면 비용·센터 호출을 강제하는 흐름 없음
- 코드 근거: `GetRepairCandidatesTool.java`, `EstimateRepairCostTool.java`, `AgentChatService.policyFallback`

### 5.4 비용 구성과 보증 분기

- 명세: F-10/F-12, T1/T5의 구성 금액, 부품별 보증, 조건별 다음 행동
- 당시 구현
    - 비용 도구가 v3 총액 범위와 보증 설명 문장을 반환
    - `next_action`은 항상 `VISIT_DIAGNOSIS`
    - `FREE_WARRANTY`·`CORE_CONDITIONAL`·`PAID_ESTIMATE` 분기와 출장비/부품비/기술료 구성 카드 없음
- 코드 근거: `EstimateRepairCostTool.java`, `frontend/src/components/ToolResults.jsx`

### 5.5 위험의 문맥 판단과 복귀

- 명세: F-05, T6/T11/T18/T19의 위험 문맥 판단, 해제 후 복귀, 사례별 경고
- 당시 구현
    - 위험 판단은 LLM 전에 로컬 규칙 14종으로 종료
    - 위험 상태에서는 Agent/MCP를 거치지 않는 안전 경로 사용
    - 점수·`return_state` 없음, 위험 답 버튼은 상태 갱신만 수행
    - 미충족: 확정 뒤 자동 2차 카드 흐름, 사람 갇힘 등 사례별 긴급 문구
- 코드 근거: `RiskRules.java`, `ConsultationService.riskAnswer`, `AgentChatService.safety`, `ConsultationControls.jsx`

### 5.6 요청 멱등성

- 명세: F-03/F-13, T17의 동일 요청 ID 응답 재사용, 중복 저장 방지
- 당시 구현
    - 요청에 `clientMessageId` 없음
    - DB 고유 키·처리 결과 재사용 없음
    - 상태 버전 검사와 전송 버튼 잠금은 재전송 멱등성을 대신하지 못함
- 코드 근거: `ChatRequest.java`, `Chat.jsx`, `chat_messages` 관련 SQL

### 5.7 DB v0.4 원본 SQL 연결

- 명세: DB v0.4의 원본 SQL로 앱 연결
- 당시 구현
    - 원본 SQL: `homecare_ai` DB와 단수 이름 17개 테이블
    - 현재 JPA: 복수 이름 5개 테이블, 기본 DB는 `project_db`
    - 원본만 실행하면 JPA가 요구하는 테이블이 없음
- 코드 근거: 원본 SQL, `ops/rds/sql/V001~V005`, JPA `@Table`

### 5.8 가입 주소와 제품 수정

- 명세: F-01/F-02, T9/T16의 가입 주소·동의·배송지 기본값·제품 수정
- 당시 구현
    - 가입은 이메일/비밀번호만 받음
    - 제품은 구매일·시도/시군구를 저장하지만 주소·동의·계정 주소 기본값 없음
    - 제품 수정 API/UI 없음
- 코드 근거: `SignupRequest.java`, `AppUser.java`, `UserProductService.java`, `UserProductController.java`

### 5.9 센터 지역 변경

- 명세: F-11, T10의 센터 지역 변경, 지역 목록
- 당시 구현: MCP 도구의 지역 override는 있으나 명세의 지역 목록 API와 지역 변경 화면 없음
- 코드 근거: `FindServiceCentersTool.java`, `McpGateway.callback`, `ToolResults.jsx`

### 5.10 입력 길이·시간 제한·상담 종료

- 명세: F-03/F-13의 300자, 25초 후 재시도, 상담 종료
- 당시 구현
    - 입력 4,000자, chat 화면 timeout 120초
    - 이력 조회/이어 하기 제공, 비용 카드 표시로 상담을 닫지 않음
    - 명세의 1회 상담 종료 정책과 다름
- 코드 근거: `ChatRequest.java`, `Chat.jsx`, `ConsultationState.java`

### 5.11 이용 이벤트와 측정

- 명세: F-14의 이용 이벤트 15종, H1~H4 측정
- 당시 구현
    - `/api/events`와 `event_log` 없음
    - `chat_tool_calls`는 도구 감사 기록이므로 A/S 클릭·근거 열기·이탈·과업 시간 등의 이벤트를 대체하지 못함
- 코드 근거: 현재 controller·SQL·frontend 검색, `ToolCallLog.java`

### 5.12 대화 맥락 전달

- Agent prompt: 현재 질문·품목·제품 확정 여부만 전달
- 과거 대화와 구조화된 해석 결과를 전달하지 않음
    - 미등록 상태에서 앞 질문에 말한 모델을 다음 질문에서 생략하면 모델 근거를 이어 쓰기 어려움
- `ToolSupport.modelArgument`: 최신 문장 또는 등록 모델에만 조회 허용
    - 보안상 이유가 있는 제한이므로, 상담 모델을 서버 상태에 검증해 보존하는 방식으로 보완 필요

---

## 6. 수리비 v3 연결 범위와 한계

### 6.1 연결 상태

- `parts.json`: 최종 v3 CSV에서 국내 적용 제외 D02를 뺀 32개 그룹 사용
- 원본과 일치: 중앙값, 80% 하한/상한, 출장비 가산
- 출장비 처리
    - 기본 출장비 28,000원은 이미 총액에 포함
    - 범위 전체에 더하는 가산: 야간/휴일 +5,000원, 여름 평일 +5,000원, 여름 야간/휴일 +10,000원

### 6.2 계산 예(W01)

| 조건 | 중앙 | 범위 |
| --- | ---: | --- |
| 평일 | 120,120원 | 88,373~163,272원 |
| 7월 야간/휴일(+10,000원) | 130,120원 | 98,373~173,272원 |

- 28,000원을 다시 더하면 중복 계산

### 6.3 구성 금액과 총액의 차이

- v3는 국내 총액으로 보정하므로 부품비 중앙+기술료 중앙+출장비가 v3 중앙 총액과 같지 않을 수 있음
    - W01: 구성 합계 126,743원, 보정 총액 120,120원
- 최종 CSV에는 구성별 상하한이 없어, 총액의 80% 범위를 각 항목의 범위처럼 나눠 표시할 근거 없음
- 명세의 구성 카드·핵심부품 무상 조건 구현에 필요한 것: 구성 항목의 별도 근거와 계산 계약
- 총액에서 부품비 중앙을 임의로 빼서 보증 적용 비용을 확정하지 않음
- 현재의 "참고 범위와 조건 문장" 정책을 유지한다면 기능명세의 T1/T5와 비용 카드 요구를 변경한 것으로 명시 필요

### 6.4 패키지 재계산

- 패키지 재계산은 현재 그대로 실행 가능하다고 볼 수 없음
    - `normalize.py`와 모델 파일들은 국내 후기·중간 CSV에 의존(검토 당시에는 작성 당시 실행 환경의 절대 경로로 읽었고, 이후 `COST_DATA_DIR` 기준으로 변경)
    - 확인한 패키지 폴더에는 전체 재계산 입력이 없음
- 현 MCP는 이 Python 모델을 실행하지 않고 이미 생성된 CSV의 변환 결과를 읽으므로 참고 범위 조회에는 문제 없음

---

## 7. 원자료끼리 먼저 정리해야 할 차이

1. 위험 판단 기준
    - 기능명세·시퀀스 v1.7: AI 해석 8초, 확인 질문 최대 2개, 하나라도 예/모름이면 위험 확정
    - DB v0.4: AI 해석 12초, 확인 질문 최대 3개·점수 합산으로 변경
    - D1~D4가 일부 결정 필요로 표시되어 있어 최종 기준 확정 필요
2. 시간 제한
    - 현재: MCP RPC 5초, 도구 호출 전 확인하는 세션 deadline 20초, OpenAI 설정 30초
    - 위의 MessageInterpreter/PartEstimator 제한과 다름
    - 20초 설정은 진행 중인 전체 LLM 흐름을 강제로 끝내는 요청 deadline이 아님
3. 무상 구성 금액
    - 기능명세: 조건부 무상 구성·금액 요구
    - B30: 개인별 무상·공임/출장비 면제·정확한 총액을 판정한 자료가 아니라고 명시
    - 조건이 확인되지 않은 항목은 미확인으로 남겨야 하며, B30만으로 0원을 확정할 수 없음
4. 부품 식별자
    - DB v0.4의 `part_no`: 실물 부품 번호 관계 전제
    - 비용 v3의 W01/DW01 등: 추정 그룹 코드
    - 그룹 코드를 국내 SKU나 실제 호환 확정 번호로 취급하지 않도록 별도 식별자 필요

---

## 8. 완료를 위한 권장 순서

1. 최종 계약 결정: 위험 질문 방식, AI 제한 시간, 비용 카드 정책, DB 유지 방식
    - 기존 SQL을 현재 DB에 그대로 적용하지 않음
    - 현재 구현 기준의 설계 변경표 작성, 또는 필요한 migration과 repository 작성
2. Spring에 명세 상태 전이와 구조화된 메시지 해석 구현
    - 처리 대상: 품목 충돌, 제품 확인, 미분류, 위험 복귀, 끝 상태
    - 현재의 안전 선행 검사도 유지할 수 있도록 정책 차이 명시
3. 세탁기 OE 한 경로부터 실제 승인 계획·증상/오류 매핑·독립 검증용 후기 확보
    - 단계 실패→보증→비용→센터가 자동으로 이어지도록 구성
    - 빈 자료를 추측으로 채우지 않음
4. 보완: 구성 비용/조건부 보증 분기, 제품 수정, 지역 변경, `clientMessageId` 멱등성, 이용 이벤트와 카드 순서
5. 실제 OpenAI로 T1~T19와 별도 평가 문장 실행
    - API 성공률·모의 도구 선택·v3의 기존 20건 오차를 실제 Agent 성공률로 집계하지 않음
    - MySQL V001~V005와 `ddl-auto=validate`도 개발용 DB에서 별도 검증

- 현재 어댑터는 유지 가능
- 위 단계를 완료하기 전의 상태 표기: **"MCP 연동 및 참고 정보 조회 검증 완료, 기능명세 전체 MVP 미완료"**

[drive-spec-folder]: https://drive.google.com/drive/folders/1tQYLeZCMrAdp7WbKX_6KpWRQhiat5R9Q
[drive-spec]: https://drive.google.com/file/d/1_hoit_nbHjbbdvYwvF4k4PJC7c3uZoOM/view
[drive-sequence]: https://drive.google.com/file/d/1JhU_IcB0ARC608vwlqlXvod8BuDndrBZ/view
[drive-db]: https://drive.google.com/file/d/1KYTbxaJe6evQEpAf0-tqAlDpRIdRy-gK/view
[drive-sql]: https://drive.google.com/file/d/1DmiKQHnKGIEvyliFcHs2Dx8FVemJLhLe/view
[drive-cost-readme]: https://drive.google.com/file/d/1ZafZOEs7gdn6dlzQ76ngF6aSxwvHhiyH/view
[drive-cost-csv]: https://drive.google.com/file/d/1-5zS1cbxQX39O-2MBGufl9HEAw0kJBzl/view
[drive-cost-log]: https://drive.google.com/file/d/1MjZl28Vzg1dj9f1YtCwTfVJbLfY88ATE/view
[drive-cost-folder]: https://drive.google.com/drive/folders/1Yuk7WZoF3theT-b8qwrC-iqw2IG-DxbO
[drive-b30]: https://drive.google.com/file/d/1sUE4Whd7bUXue8fW9bJ4F4VOSavzTgxw/view
