# 제품 전체·주요 부품 보증 구현

2026-10-13 · 대상: 냉장고(김치냉장고 포함), 세탁기, 건조기, 식기세척기

## 고객에게 설명하는 구분

`productWarranty`는 제품 전체의 일반 보증이고, `partWarranties`는 특정 주요 부품의 별도 정책이다. 제품 전체 보증 만료만으로 모든 부품의 보증이 끝났다고 하지 않는다. 별도 부품 보증이 있어도 다른 부품의 보증기간이 늘어나는 것은 아니다.

사용자가 설명한 제품 2년/주요 부품 4년의 경우 구매 후 3년은 제품 전체 만료·해당 부품 보증 유지다. 이 예시는 단위 테스트에만 넣었다. 운영 정책은 아래 원문에서 확인한 기간과 적용 조건으로 별도 저장한다.

각 보증은 기간뿐 아니라 부품비·기술료·출장비를 따로 안내한다. 기간 안인 경우에도 정상 사용·대상 모델·고장 부품·증빙 조건을 확인해야 하므로 `CONDITIONAL_FREE`로 표시한다. 미확인은 `CONFIRM_REQUIRED`이며 0원으로 치환하지 않는다.

## 정책 데이터와 날짜

정책 파일: `backend/src/main/resources/warranty/policies.json`. 출처: [LG 공식 요금·보증 안내](https://www.lge.co.kr/support/rates-warranty-guide), 확인일 2026-10-13.

| 품목 | 일반 제품 전체 정책 | 별도 부품 정책 후보 |
|---|---|---|
| 냉장고 | 12개월 | 일반 컴프레서 36개월 / 조건부 리니어·인버터 120개월 |
| 세탁기 | 12개월 | 일반 모터 36개월 / 조건부 DD모터 120개월 |
| 건조기 | 12개월 | 조건부 인버터 컴프레서·모터 120개월 |
| 식기세척기 | 12개월 | 일반 모터 12개월 / 조건부 DD모터 120개월 |

생산 시기 제한은 원문 기준을 데이터로 보관한다. 부품 유형·생산일·사용 조건 미확인은 정책 후보로 보여 주되 실제 만료일/보증 중 판정을 만들지 않는다. 알려진 모델명 문자열만으로 부품 장착이나 호환을 확정하지 않는다.

구매일과 배송일이 다를 때는 입력된 배송일을 기산일로 사용한다. 구입 증빙이 없으면 제조일을 임의 추정하는 기능은 제공하지 않는다. 달력 월 덧셈과 말일 보정, 만료일 당일까지 보증 중으로 계산한다. 기준일은 서버의 Asia/Seoul Clock이며 사용자·LLM이 변경할 수 없다.

## API와 화면

두 API 모두 기존 JWT 인증을 사용한다.

- `GET /api/warranty/policies`: 버전·출처·4품목 정책 조회.
- `POST /api/warranty/check`: 사용자 확인 정보로 기간·조건·설명을 계산. LLM 호출 없음.

요청 예:

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

보증 확인 패널에서 구매·배송·생산일과 부품 유형, 증빙·사용 조건을 확인한다. 제품 전체/주요 부품 카드마다 기간·만료일·비용 항목·조건을 분리하고 공식 근거를 링크한다. 직접 조회 입력은 DB에 제품으로 자동 등록하지 않는다.

`POST /api/chat`은 선택적으로 같은 구조의 `warranty` 필드 또는 직접 선택한 `productId`를 받는다. 선택 품목과 다르면 400이다. 새 Agent는 실제 MCP `check_warranty`를 선택하며 사용자 확인 정보는 인증된 상담/제품에서 읽는다. LLM의 도구 인자에 날짜/정책을 생성시키지 않는다. 구조화 입력이 없으면 확인이 필요한 정책 후보와 폼을 안내한다. 고객 설명은 서버 템플릿으로 만들며 보증 도구를 누락하면 `POLICY_FALLBACK`을 구분해 기록한다.

기본 상담은 Java MCP SDK → `mcp_server/homecare_mcp`의 실제 FastMCP HTTP로 연결했다. `HOMECARE_AGENT_ENABLED=false`인 기존 경로에서는 네이티브 `@Tool`을 유지한다. 어느 경로에서도 기간 계산은 같은 `WarrantyService`를 사용한다. 전체 도구 설명은 [mcp_implementation.md](mcp_implementation.md)를 따른다.

상담 응답의 `warranty` 카드와 설명은 메시지에 저장해 이력 조회 시 동일하게 보여 준다. 단순 보증 패널 조회는 이력 저장과 별개다. 위험 문구가 포함된 보증 질문은 계산보다 사용 중지 안내를 먼저 반환한다. 이 보수적 선행 검사는 전체 risk_case 13개·누적 점수 상태 머신의 완료를 의미하지 않는다.

## FastMCP 보증 도구

`mcp_server/homecare_mcp/tools/check_warranty.py`는 `check_warranty(conversation_id)`를 제공하며 사용자 수동 조회 `request`도 지원한다. 정책·기준일·금액·토큰 인자는 허용하지 않는다. `homecare_mcp/legacy/warranty_server.py`는 이전 단독 보증 도구의 호환 엔트리다. 새 Agent 실행은 [MCP README](../../mcp_server/README.md)를 따른다.

```powershell
cd mcp_server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m homecare_mcp
```

기본 stdio에서는 인증된 단일 로컬 사용자의 `HOMECARE_USER_TOKEN`(HomeCare 로그인 JWT)을 프로세스 환경으로 전달한다. 이 값은 OpenAI 키가 아니다. HTTP 모드는 `HOMECARE_MCP_TRANSPORT=http`로 실행하고 호출마다 Authorization Bearer 사용자 JWT를 전달한다. HTTP에서 전역 사용자 토큰을 재사용하지 않는다. backend URL은 신뢰된 서버 환경의 `HOMECARE_BACKEND_URL`로 설정하고 도구 인자로 URL을 받지 않는다. 운영에서는 내부 네트워크에서만 사용한다.

## 기존 OpenAI 설정과 DB 반영

기존 `BackendApplication`의 `.env` 로딩, `application.properties`의 `OPENAI_API_KEY/OPENAI_MODEL`, 운영 `ops/deploy/write-runtime-env.py`의 SSM → `/opt/homecare/config/runtime.env` 연결을 그대로 재사용한다. 새 API 키를 만들거나 기존 비밀값을 복사·출력하지 않았다. 로컬 키가 없는 상태에서도 테스트는 기존 모의 OpenAI/H2 구성으로 수행한다. 실서비스 인증·잔액 검증이나 배포는 이번 작업에 포함되지 않는다.

운영 DB가 V001 상태이면 배포 전에 V002(보증 결과) → V003(내 제품·상담 상태·도구 결과) → V004(4품목 범위) → V005(도구 호출 기록)를 1회 적용해야 한다. 기존 테이블을 삭제하거나 v0.4의 17개 스키마를 덮어쓰지 않는다. 개발의 `ddl-auto=update`는 새 컬럼을 만들며 운영의 validate 설정은 migration을 요구한다.

## 검증

백엔드: 2년/4년 예시, 만료일 당일·다음날, 윤년·배송일, NULL·증빙·사용 조건, 부품 생산일 조건, 4품목, 인증·실제 Spring AI Tool Calling·저장·위험 우선 안내를 모의 API로 검증한다.

MCP: 실제 FastMCP Client의 discovery/call, 인증 누락, 정책 인자 주입 거부, upstream 오류·timeout·안전한 오류 메시지를 검증한다. UI는 ESLint와 production build로 확인한다. 제조사 엔지니어의 최종 승인·실모델 정확도·사용자 효과를 입증하는 테스트는 아니다.

v2.0에서는 MCP 8개 실행·소유권·상태·금액/승인 조치의 경계 테스트를 추가했다. 백엔드 산출물은 `backend/build/libs/app.jar`다. OpenAI 호출은 로컬 모의 서버, DB는 H2를 사용했다. 실 OpenAI·운영 MySQL·운영 배포는 검증 범위 밖이다. 최신 검증 결과는 [mcp_implementation.md](mcp_implementation.md)를 따른다.
