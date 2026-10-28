# 제품 전체·주요 부품 보증 구현

> 제품 전체 보증과 주요 부품 보증의 계산 기준, 정책 데이터, API·MCP 연결 기록
> 최초 작성: 2026-10-13 · 구매일 기준 변경: 2026-10-15 · 대상: 냉장고(김치냉장고 포함), 세탁기, 건조기, 식기세척기

**목차**

1. [현재 기산 기준](#1-현재-기산-기준)
2. [고객에게 설명하는 구분](#2-고객에게-설명하는-구분)
3. [정책 데이터와 날짜](#3-정책-데이터와-날짜)
4. [API와 화면](#4-api와-화면)
5. [FastMCP 보증 도구](#5-fastmcp-보증-도구)
6. [기존 OpenAI 설정과 DB 반영](#6-기존-openai-설정과-db-반영)
7. [검증](#7-검증)

---

## 1. 현재 기산 기준

### 1.1 구매일 기준(2026-10-15 개발자 요구사항)

- **등록된 구매일을 보증 시작일로 계산**
- 구매일이 있으면 구입 증빙·국내 신품/가정용 확인 여부가 비어 있어도 제품 전체 보증의 만료일과 기간 내/만료 상태 제공
- 배송일: 입력 정보로 보존하지만 보증 시작일을 대체하지 않음
- 구매일이 없으면 제조일이나 배송일로 추정하지 않고 구매일 요청

### 1.2 기간 계산과 무상 적용의 분리

- 분리 대상: 기간 계산 / 실제 무상 수리·부품비·기술료·출장비 적용
- 증빙·사용 조건을 임의로 참으로 저장하지 않음
- 미확인 비용: `CONFIRM_REQUIRED` 유지
- 주요 부품: 해당 부품 유형·정책·생산 시기 조건을 확인할 수 있을 때만 구매일 기준으로 기간 계산
- 부품 유형을 모른다는 이유로 제품 전체의 1년 기간 계산까지 막지 않음

### 1.3 적용 예

| 항목 | 값 |
| --- | --- |
| 모델 / 구매일 | S836MEE022 / 2026-05-05 |
| 제품 전체 정책 | 12개월 |
| 계산 결과 | `basisSource=PURCHASE`, `expiresOn=2027-05-05` |
| 2026-10-15 기준 상태 | `IN_WARRANTY` |

- 증빙·사용 조건이 미확인이면 비용 항목은 별도 확인 대상으로 남음
- 실행 결과: [등록→MCP→저장 이력→화면 검수](purchase_date_warranty_verification.md)

---

## 2. 고객에게 설명하는 구분

### 2.1 제품 전체 보증과 부품 보증

- `productWarranty`: 제품 전체의 일반 보증
- `partWarranties`: 특정 주요 부품의 별도 정책
- 제품 전체 보증 만료만으로 모든 부품의 보증이 끝났다고 안내하지 않음
- 별도 부품 보증이 있어도 다른 부품의 보증기간이 늘어나지 않음

### 2.2 예시(단위 테스트 전용)

- 개발자가 설명한 경우: 제품 2년/주요 부품 4년, 구매 후 3년 → 제품 전체 만료, 해당 부품 보증 유지
- 이 예시는 단위 테스트에만 포함
- 운영 정책은 아래 원문에서 확인한 기간과 적용 조건으로 별도 저장

### 2.3 비용 항목 표시

- 각 보증마다 부품비·기술료·출장비를 따로 안내
- 기간 안: 정상 사용·대상 모델·고장 부품·증빙 조건을 확인해야 하므로 `CONDITIONAL_FREE`
- 미확인: `CONFIRM_REQUIRED`, 0원으로 치환하지 않음

---

## 3. 정책 데이터와 날짜

### 3.1 정책 파일

- 위치: `backend/src/main/resources/warranty/policies.json`
- 출처: [LG 공식 요금·보증 안내][lg-warranty], 확인일 2026-10-13

| 품목 | 일반 제품 전체 정책 | 별도 부품 정책 후보 |
| --- | --- | --- |
| 냉장고 | 12개월 | 일반 컴프레서 36개월 / 조건부 리니어·인버터 120개월 |
| 세탁기 | 12개월 | 일반 모터 36개월 / 조건부 DD모터 120개월 |
| 건조기 | 12개월 | 조건부 인버터 컴프레서·모터 120개월 |
| 식기세척기 | 12개월 | 일반 모터 12개월 / 조건부 DD모터 120개월 |

### 3.2 부품 조건

- 생산 시기 제한: 원문 기준을 데이터로 보관
- 주요 부품 유형·필요한 생산일이 미확인이면 해당 부품의 정책 후보는 보여 주되 만료일/기간 중 판정은 만들지 않음
- 알려진 모델명 문자열만으로 부품 장착이나 호환을 확정하지 않음
- 사용 조건·증빙: 날짜 계산이 아닌 실제 무상 적용에 따로 반영

### 3.3 날짜 계산

- 구매일과 배송일이 달라도 구매일을 기산일로 사용
    - 이 앱의 현재 계산 기준이며 제조사의 모든 상황에 대한 무상 승인을 대신하지 않음
- 달력 월 덧셈과 말일 보정
- 만료일 당일까지 기간 중으로 계산
- 기준일: 서버의 Asia/Seoul Clock, 사용자·LLM이 변경할 수 없음

---

## 4. API와 화면

### 4.1 보증 API

두 API 모두 기존 JWT 인증을 사용합니다.

- `GET /api/warranty/policies`: 버전·출처·4품목 정책 조회
- `POST /api/warranty/check`: 사용자 확인 정보로 기간·조건·설명 계산, LLM 호출 없음

요청 예

```json
{
  "category": "WASHER",
  "modelName": null,
  "purchaseDate": "2023-10-13",
  "deliveryDate": null,
  "productionDate": "2023-09-01",
  "coreVariant": "DD_MOTOR",
  "productType": "GENERAL",
  "proofAvailable": true,
  "domesticNewHousehold": true
}
```

### 4.2 보증 확인 패널

- 확인 항목: 구매·배송·생산일, 부품 유형, 증빙·사용 조건
- 제품 전체/주요 부품 카드마다 기간·만료일·비용 항목·조건을 분리하고 공식 근거 링크
- 직접 조회 입력은 DB에 제품으로 자동 등록하지 않음

### 4.3 상담 API와 Agent

- `POST /api/chat`: 선택적으로 같은 구조의 `warranty` 필드 또는 직접 선택한 `productId`를 받음
    - 선택 품목과 다르면 400
- 새 Agent: 실제 MCP `check_warranty` 선택, 사용자 확인 정보는 인증된 상담/제품에서 읽음
- LLM의 도구 인자로 날짜/정책을 생성시키지 않음
- 구조화 입력이 없으면 확인이 필요한 정책 후보와 폼 안내
- 고객 설명: 서버 템플릿으로 생성, 보증 도구를 누락하면 `POLICY_FALLBACK`을 구분해 기록

### 4.4 연결 경로

- 기본 상담: Java MCP SDK → `mcp_server/homecare_mcp`의 실제 FastMCP HTTP
- `HOMECARE_AGENT_ENABLED=false`인 기존 경로: 네이티브 `@Tool` 유지
- 어느 경로에서도 기간 계산은 같은 `WarrantyService` 사용
- 전체 도구 설명: [mcp_implementation.md](mcp_implementation.md)

### 4.5 이력과 위험 안내

- 상담 응답의 `warranty` 카드와 설명은 메시지에 저장해 이력 조회 시 동일하게 표시
- 단순 보증 패널 조회는 이력 저장과 별개
- 위험 문구가 포함된 보증 질문: 계산보다 사용 중지 안내를 먼저 반환
    - 이 보수적 선행 검사는 전체 risk_case 13개·누적 점수 상태 머신의 완료를 의미하지 않음

---

## 5. FastMCP 보증 도구

### 5.1 도구

- `mcp_server/homecare_mcp/tools/check_warranty.py`: `check_warranty(conversation_id)` 제공, 사용자 수동 조회 `request`도 지원
- 허용하지 않는 인자: 정책, 기준일, 금액, 토큰
- `homecare_mcp/legacy/warranty_server.py`: 이전 단독 보증 도구의 호환 엔트리
- 새 Agent 실행: [MCP README](../../mcp_server/README.md)

```powershell
cd mcp_server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m homecare_mcp
```

### 5.2 인증

- 기본 stdio: 인증된 단일 로컬 사용자의 `HOMECARE_USER_TOKEN`(HomeCare 로그인 JWT)을 프로세스 환경으로 전달
    - 이 값은 OpenAI 키가 아님
- HTTP 모드: `HOMECARE_MCP_TRANSPORT=http`로 실행, 호출마다 Authorization Bearer 사용자 JWT 전달
    - HTTP에서 전역 사용자 토큰을 재사용하지 않음
- backend URL: 신뢰된 서버 환경의 `HOMECARE_BACKEND_URL`로 설정, 도구 인자로 URL을 받지 않음
- 운영에서는 내부 네트워크에서만 사용

---

## 6. 기존 OpenAI 설정과 DB 반영

### 6.1 OpenAI 설정

- 그대로 재사용한 것
    - 기존 `BackendApplication`의 `.env` 로딩
    - `application.properties`의 `OPENAI_API_KEY/OPENAI_MODEL`
    - 운영 `ops/deploy/write-runtime-env.py`의 SSM → `/opt/homecare/config/runtime.env` 연결
- 새 API 키를 만들거나 기존 비밀값을 복사·출력하지 않음
- 로컬 키가 없는 상태에서도 테스트는 기존 모의 OpenAI/H2 구성으로 수행
- 이번 작업에 포함하지 않은 것: 실서비스 인증·잔액 검증, 배포

### 6.2 DB 마이그레이션

운영 DB가 V001 상태이면 배포 전에 아래 순서로 1회 적용합니다.

1. V002: 보증 결과
2. V003: 내 제품·상담 상태·도구 결과
3. V004: 4품목 범위
4. V005: 도구 호출 기록

- 기존 테이블을 삭제하거나 v0.4의 17개 스키마를 덮어쓰지 않음
- 개발의 `ddl-auto=update`는 새 컬럼을 만들며, 운영의 validate 설정은 migration을 요구

---

## 7. 검증

### 7.1 백엔드(모의 API)

- 2년/4년 예시
- 만료일 당일·다음날, 윤년·구매일 우선
- NULL·증빙·사용 조건, 부품 생산일 조건
- 4품목
- 인증·실제 Spring AI Tool Calling·저장·위험 우선 안내

### 7.2 MCP와 UI

- MCP: 실제 FastMCP Client의 discovery/call, 인증 누락, 정책 인자 주입 거부, upstream 오류·timeout·안전한 오류 메시지
- UI: ESLint와 production build
- 입증하지 않는 것: 제조사 엔지니어의 최종 승인, 실모델 정확도, 사용자 효과

### 7.3 v2.0 추가 검증

- 추가한 테스트: MCP 8개 실행·소유권·상태·금액/승인 조치의 경계
- 백엔드 산출물: `backend/build/libs/app.jar`
- 사용 환경: OpenAI 호출은 로컬 모의 서버, DB는 H2
- 검증 범위 밖: 실 OpenAI, 운영 MySQL, 운영 배포
- 최신 검증 결과: [mcp_implementation.md](mcp_implementation.md)

[lg-warranty]: https://www.lge.co.kr/support/rates-warranty-guide
