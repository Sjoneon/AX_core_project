# 구매일 기준 보증 계산·결과 검수

> 등록 구매일을 보증 시작일로 계산하도록 바꾼 변경과 실제 경로 검수 기록
> 검수일: 2026-10-15 (Asia/Seoul) · 상태(작성 당시): **로컬 구현·검수 완료, 커밋·배포 전**

**목차**

1. [요구사항과 변경](#1-요구사항과-변경)
2. [실제 검수 입력과 결과](#2-실제-검수-입력과-결과)
3. [검수한 경로](#3-검수한-경로)
4. [결과와 증거](#4-결과와-증거)
5. [재현 명령](#5-재현-명령)

---

## 1. 요구사항과 변경

### 1.1 요구사항

- 보증 시작일: 등록 구매일
- 구매일과 정책 기간이 있으면 제품 전체 보증 만료일·기간 상태 계산
- 증빙·가정용 사용 확인이 없다는 이유로 기간 전체를 미확인으로 처리하지 않음
- 배송일을 입력해도 기산일은 구매일

### 1.2 변경

- [WarrantyService][warranty-service]: 기간 계산과 실제 무상 적용·비용 조건 분리
    - 미확인 증빙·사용 조건을 참으로 저장하지 않음
    - 비용 항목은 별도 `CONFIRM_REQUIRED`로 유지
    - 부품 유형·생산 시기 조건이 필요한 주요 부품은 해당 조건을 확인한 경우에만 기간 계산
    - 제품 전체 1년을 컴프레서 전체에 일괄 적용하거나 모든 부품의 실제 무상 여부를 확정하지 않음
- [CheckWarrantyTool][check-warranty-tool]: 기산일 안내를 구매일 기준으로 변경
- [보증 카드][warranty-result]: 계산된 기간과 만료일 표시, 그 아래 확인 문구를 "무상 수리와 비용 적용" 설명으로 구분
- 변경하지 않은 것: 정책 파일의 기간·금액·제조사 출처

---

## 2. 실제 검수 입력과 결과

### 2.1 검수 입력

- 사용자 계정 조회나 실제 제품 정보 수정 없음
- 제시된 모델·구매일과 같은 값으로 격리된 H2에 합성 제품 등록
- 증빙·가정용 확인 값: 등록 화면과 동일하게 비워 둠
- 개인의 도로명·상세 주소는 사용하지 않음

### 2.2 검수 결과

| 항목 | 검수 결과 |
| --- | --- |
| 모델 | S836MEE022 |
| 등록 구매일·보증 시작일 | 2026-05-05 |
| 계산 기준일 | 2026-10-15 |
| 제품 전체 정책 | 12개월 |
| 제품 전체 만료일 | **2027-05-05** |
| 기간 상태 | **IN_WARRANTY / 보증기간 안** |
| `basisSource` | PURCHASE |
| 증빙·국내 가정용 확인 입력 | null / null 유지 |
| 부품비·기술료·출장비 | CONFIRM_REQUIRED / 추가 확인 필요 |
| 주요 부품 유형 미확인 | 해당 부품 기간을 임의 확정하지 않음 |

고객에게 보이는 제품 전체 보증 결과

```text
등록 구매일 기준 제품 전체 보증기간 안입니다.
구매일 2026-05-05
제품 전체 보증 1년
보증기간 안
만료일: 2027-05-05
무상 수리와 비용 적용은 아래 조건을 추가로 확인해야 합니다.
```

- "보증기간 안": 현재 앱의 구매일 기준 기간 계산 결과
- 실제 무상 수리 승인이나 모든 비용 0원을 의미하지 않음

---

## 3. 검수한 경로

[McpIntegrationTests][mcp-it]의 `registeredRefrigeratorPurchaseReachesMcpChatAndSavedHistory`가 아래 경로를 실제로 실행합니다.

1. 인증된 `POST /api/products`로 모델·구매일 등록
2. 등록된 `productId`와 "냉장고가 시원하지 않아요."로 `POST /api/chat` 호출
3. 실제 FastMCP HTTP와 Spring 보증 도구를 거친 응답의 구매일·기간 상태·만료일 확인
4. `check_warranty` 결과와 최종 `ChatResponse.warranty`가 같은지 확인
5. `GET /api/conversations/{id}`로 저장 이력을 다시 읽어 같은 보증 결과인지 확인
6. 반환한 실제 JSON을 브라우저 검사에 전달해 새 답변·저장 이력의 실제 React 보증 카드를 각각 확인

- 기존 스크립트 응답 서버를 쓴 부분: 모델의 해석, 도구 선택, 본문 구성
- 실제 구현을 쓴 부분: 보증 계산, 제품 등록, MCP 전송, 이력 저장
- 이 검사를 실제 모델의 전체 답변 품질 검증으로 계산하지 않음
- 별도 후속 범위: 증상 설명 반복, 센터 조회, 주요 부품 후보 카드 축약([기존 점검 문서](manufacturer_data_usage_audit.md))

---

## 4. 결과와 증거

### 4.1 검사 결과

- Java 전체 307개 중 **305개 통과**, MySQL 전용 2개 제외, `bootJar` 성공
- 프런트엔드 **25개 통과**, lint·운영 빌드 성공
- 실제 Edge 렌더링: 캡처한 HTTP 응답과 저장 이력 모두 **보증기간 안·2027-05-05** 확인
    - 390px·1280px에서 가로 넘침 없음, 보증 카드 화면 캡처 확인
- 실제 MCP discovery 재생성 결과: 기존 계약과 동일
- 구 응답 이력의 `DELIVERY` 값: 호환을 위해 파서·화면에서 계속 읽음, 새 계산은 `PURCHASE` 반환

### 4.2 포함한 검사

- 구매일·배송일이 다른 경우의 구매일 우선
- 만료일 당일/다음날, 윤년 말일
- 구매일 누락, 증빙 누락
- 미확인 주요 부품 조건

### 4.3 기존 상담에 대한 영향

- 기존 상담 메시지는 당시 결과를 보존하며, 과거 보증 결과를 일괄 다시 쓰지 않음
- 새 로직의 결과는 수정한 백엔드를 실행한 뒤 새 상담·보증 조회부터 적용

### 4.4 증거 파일

- [구조화 검수 결과][result-json]
- [데스크톱 화면][desktop-png]
- [모바일 화면][mobile-png]

---

## 5. 재현 명령

저장소 루트에서 기존 Python 환경과 JDK 21을 사용합니다. 검수 JSON 출력 폴더를 먼저 만듭니다.

```powershell
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_TEST_PYTHON=(Resolve-Path 'mcp_server/.venv/Scripts/python.exe').Path
New-Item -ItemType Directory -Path '.git/validation/purchase-warranty' -Force | Out-Null
$env:HOMECARE_WARRANTY_VERIFICATION_OUTPUT=Join-Path (Get-Location) '.git/validation/purchase-warranty/response.json'
Set-Location backend
.\gradlew.bat test bootJar --offline --no-daemon
Set-Location ../frontend
npm.cmd test
npm.cmd run lint
npm.cmd run build
npm.cmd run test:display:browser
```

- `HOMECARE_WARRANTY_VERIFICATION_OUTPUT`: 합성 통합 테스트 응답을 브라우저에 전달하는 검수용 경로
- 브라우저 검사는 이 응답을 구매일·기간·만료일의 기대값과 대조
- 수행하지 않은 것: OpenAI 실 API 호출, 실제 사용자 DB 변경, 운영 배포, 실행 중인 서비스 재시작

[warranty-service]: ../../backend/src/main/java/com/project/backend/warranty/WarrantyService.java
[check-warranty-tool]: ../../backend/src/main/java/com/project/backend/mcp/tool/handler/CheckWarrantyTool.java
[warranty-result]: ../../frontend/src/components/WarrantyResult.jsx
[mcp-it]: ../../backend/src/test/java/com/project/backend/mcp/McpIntegrationTests.java
[result-json]: ../benchmarks/purchase_warranty_20261015.json
[desktop-png]: ../benchmarks/purchase_warranty_20261015_desktop.png
[mobile-png]: ../benchmarks/purchase_warranty_20261015_mobile.png
