# 구매일 기준 보증 계산·결과 검수

검수일: 2026-10-15 (Asia/Seoul). 상태: **로컬 구현·검수 완료, 커밋·배포 전**.

## 요구사항과 변경

보증 시작일을 등록 구매일로 간주한다. 구매일과 정책 기간이 있으면 제품 전체 보증 만료일·기간 상태를 계산하고, 증빙·가정용 사용 확인이 없다는 이유로 기간 전체를 미확인으로 처리하지 않는다. 배송일을 입력해도 기산일은 구매일이다.

[WarrantyService](../../backend/src/main/java/com/project/backend/warranty/WarrantyService.java)에서 기간 계산과 실제 무상 적용·비용 조건을 분리했다. 미확인 증빙·사용 조건을 참으로 저장하지 않고, 비용 항목은 별도 `CONFIRM_REQUIRED`로 유지한다. 부품 유형·생산 시기 조건이 필요한 주요 부품은 해당 조건을 확인한 경우에만 기간을 계산한다. 제품 전체 1년을 컴프레서 전체에 일괄 적용하거나 모든 부품의 실제 무상 여부를 확정하지 않는다.

[CheckWarrantyTool](../../backend/src/main/java/com/project/backend/mcp/tool/handler/CheckWarrantyTool.java)의 기산일 안내도 구매일 기준으로 맞췄다. [보증 카드](../../frontend/src/components/WarrantyResult.jsx)는 계산된 기간과 만료일을 표시하며, 그 아래 확인 문구를 “무상 수리와 비용 적용”에 관한 설명으로 구분한다. 정책 파일의 기간·금액·제조사 출처는 변경하지 않았다.

## 실제 검수 입력과 결과

사용자 계정을 조회하거나 실제 제품 정보를 수정하지 않았다. 제시된 모델·구매일과 같은 값으로 격리된 H2에 합성 제품을 등록했다. 증빙·가정용 확인 값은 등록 화면과 동일하게 비워 뒀다. 개인의 도로명·상세 주소는 사용하지 않았다.

| 항목 | 검수 결과 |
|---|---|
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

고객에게 보이는 제품 전체 보증 결과는 다음과 같다.

```text
등록 구매일 기준 제품 전체 보증기간 안입니다.
구매일 2026-05-05
제품 전체 보증 1년
보증기간 안
만료일: 2027-05-05
무상 수리와 비용 적용은 아래 조건을 추가로 확인해야 합니다.
```

“보증기간 안”은 현재 앱의 구매일 기준 기간 계산 결과다. 실제 무상 수리 승인이나 모든 비용 0원을 의미하지 않는다.

## 검수한 경로

[McpIntegrationTests](../../backend/src/test/java/com/project/backend/mcp/McpIntegrationTests.java)의 `registeredRefrigeratorPurchaseReachesMcpChatAndSavedHistory`는 다음 경로를 실제로 실행한다.

1. 인증된 `POST /api/products`로 모델·구매일을 등록한다.
2. 등록된 `productId`와 “냉장고가 시원하지 않아요.”로 `POST /api/chat`을 호출한다.
3. 실제 FastMCP HTTP와 Spring 보증 도구를 거친 응답의 구매일·기간 상태·만료일을 확인한다.
4. `check_warranty` 결과와 최종 `ChatResponse.warranty`가 같은지 확인한다.
5. `GET /api/conversations/{id}`에서 저장 이력을 다시 읽고 같은 보증 결과인지 확인한다.
6. 반환한 실제 JSON을 브라우저 검사에 전달해 새 답변·저장 이력의 실제 React 보증 카드를 각각 확인한다.

모델의 해석·도구 선택·본문 구성은 기존 스크립트 응답 서버를 사용한다. 보증 계산·제품 등록·MCP 전송·이력 저장은 실제 구현을 사용했다. 이 검사를 실제 모델의 전체 답변 품질 검증으로 계산하지 않는다. 증상 설명 반복, 센터 조회, 주요 부품 후보 카드 축약은 [기존 점검 문서](manufacturer_data_usage_audit.md)의 별도 후속 범위다.

## 결과와 증거

- Java 전체 307개 중 **305개 통과**, MySQL 전용 2개 제외, `bootJar` 성공.
- 프런트엔드 **25개 통과**, lint·운영 빌드 성공.
- 실제 Edge에서 캡처한 HTTP 응답과 저장 이력을 각각 렌더링해 **보증기간 안·2027-05-05**를 확인했다. 390px·1280px에서 가로 넘침이 없고 보증 카드 화면 캡처도 확인했다.
- 실제 MCP discovery 재생성 결과가 기존 계약과 같았다. 구 응답 이력의 `DELIVERY` 값은 호환을 위해 파서·화면에서 계속 읽는다. 새 계산은 `PURCHASE`를 반환한다.
- [구조화 검수 결과](../benchmarks/purchase_warranty_20261015.json), [데스크톱 화면](../benchmarks/purchase_warranty_20261015_desktop.png), [모바일 화면](../benchmarks/purchase_warranty_20261015_mobile.png).

검사에는 구매일·배송일이 다른 경우의 구매일 우선, 만료일 당일/다음날, 윤년 말일, 구매일 누락, 증빙 누락, 미확인 주요 부품 조건도 포함했다. 기존 상담 메시지는 당시 결과를 보존하며 이번 수정으로 과거 보증 결과를 일괄 다시 쓰지 않는다. 새 로직의 결과는 수정한 백엔드를 실행한 뒤 새 상담·보증 조회에서 적용된다.

## 재현 명령

저장소 루트에서 기존 Python 환경과 JDK 21을 사용한다. 검수 JSON 출력 폴더는 먼저 만들어 둔다.

```powershell
$env:JAVA_HOME='C:\Users\Roneon\.jdks\openjdk-21.0.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:HOMECARE_TEST_PYTHON=(Resolve-Path 'mcp_server/.venv/Scripts/python.exe').Path
New-Item -ItemType Directory -Path '.git/codex-validation/purchase-warranty' -Force | Out-Null
$env:HOMECARE_WARRANTY_VERIFICATION_OUTPUT=Join-Path (Get-Location) '.git/codex-validation/purchase-warranty/response.json'
Set-Location backend
.\gradlew.bat test bootJar --offline --no-daemon
Set-Location ../frontend
npm.cmd test
npm.cmd run lint
npm.cmd run build
npm.cmd run test:display:browser
```

`HOMECARE_WARRANTY_VERIFICATION_OUTPUT`은 합성 통합 테스트 응답을 브라우저에 전달하는 검수용 경로다. 브라우저 검사는 이를 구매일·기간·만료일의 기대값과 대조한다. OpenAI 실 API 호출, 실제 사용자 DB 변경, 운영 배포나 실행 중인 서비스 재시작은 수행하지 않았다.
