# B30 자료 사용과 냉장고 상담 답변 점검

> 서비스가 실제로 읽는 B30 자료 범위와, 냉장고 상담 답변이 대부분 "확인 필요"로 나온 원인·조치 기록
> 확인일: 2026-10-15 (Asia/Seoul) · 자료 버전: `B30-COST-V3-20261014-R5`
> 대상: 로컬 `main`의 `3b01ad6` 이후 작업본, 원격 반영본 `mcp_feature_integration`의 `66521d3`

**목차**

- [판정](#판정)
1. [실제 읽는 자료](#1-실제-읽는-자료)
2. [B15·B16 ID가 나오는 이유](#2-b15b16-id가-나오는-이유)
3. [검색을 실제 코드로 재현한 결과](#3-검색을-실제-코드로-재현한-결과)
4. [제시된 냉장고 답변이 대부분 "확인 필요"인 이유](#4-제시된-냉장고-답변이-대부분-확인-필요인-이유)
5. [누락 117건을 처리하는 기준](#5-누락-117건을-처리하는-기준)
6. [해결 순서와 완료 기준](#6-해결-순서와-완료-기준)
7. [재확인 방법과 범위](#7-재확인-방법과-범위)
8. [4절 해결 반영](#8-4절-해결-반영)

---

## 판정

- **결론**
    - "B15·B16 파일만 읽는다"는 주장은 사실이 아님
    - B30 원본 전체가 서비스에 연결된 상태도 아님
    - 자료를 찾아도 답변에 필요한 정보가 충분히 전달되지 않는 문제 있음
- 사례: 개발자가 제시한 `S836MEE022`의 "냉장고가 시원하지 않아요."
    - B30으로 자료 이름을 바꾸거나 건수를 늘리는 것만으로는 해결되지 않음
    - 영향을 주는 요소: 원문 선택, 설명 필드의 답변 전달, 자가조치 승인, 등록 정보 연결, 보증 판정, 서비스 후속 호출, 화면 표시
    - 당시 답변: 증상 이름 반복 뒤 확인 필요와 접수 안내를 길게 나열
    - 잘못된 실행 안내를 피한 것과 질문에 유용하게 답한 것은 별도 기준
- 증거: [점검 증거 JSON][audit-json]에 확인 수량과 실제 Java 재현 결과 저장, 과거 평가 결과를 이번 실행 결과로 재사용하지 않음

---

## 1. 실제 읽는 자료

```text
외부 B30 통합본 knowledge_effective_B30.jsonl: 1,659건
    ↓ 서비스로 가져온 부분집합 — 최초 변환 경로의 재현 코드 없음
backend/src/main/resources/homecare/knowledge.json: 1,542건
    ↓ structure_knowledge.py
homecare/knowledge-structured.json: 1,542건
    ↓ ToolDataCatalog → 적용 조건 필터 → KnowledgeRetriever
search_knowledge / get_diagnostic_plan
    ↓ AnswerContext → GroundedAnswerGenerator + 결과 카드
고객 답변과 저장 이력
```

### 1.1 생성기와 런타임

- [ToolDataCatalog][tool-data-catalog]: 패키지 내부 파일을 읽음
    - 바탕화면 B30 폴더를 런타임에 직접 조회하지 않음
    - 새 원본 파일을 추가해도 자동 재수집하지 않음
- [구조화 생성기][structure-script]: 이미 가져온 `knowledge.json`을 투영
- [비용 생성기][cost-script]: 비용 CSV 변환, B30 지식 전체를 가져오지 않음
- [승인 계획 생성기][plans-script]: B30 일부 원문을 검토해 특정 계획 생성
- B30 전체 → 서비스 지식의 최초 변환을 재현하는 생성기: 현재 저장소에 없음
    - 누락은 ID 차집합으로 확인
    - 당시 변환이 어떤 조건으로 각 행을 버렸는지는 실행 코드로 확정할 수 없음

### 1.2 대조 결과

| 확인 항목 | 이번 결과 |
| --- | ---: |
| 바탕화면 B30 체크섬 목록의 파일 검증 | 58개 모두 일치 |
| 서비스 매니페스트의 파일 검증 | 9개 모두 일치 |
| B30 원본 / 서비스 지식 / 구조화 지식 | 1,659 / 1,542 / 1,542건 |
| 서비스 1,542건의 `text`를 파싱해 B30 전체 래퍼와 비교 | 불일치 0건 |
| 서비스에만 있는 사실 ID | 0건 |
| 원본 대비 서비스에 없는 사실 | 117건 |
| 그중 원본 품목이 `KIMCHI`인 사실 | 97건 |
| 판매 코드 집합 | 원본·서비스 모두 동일한 52개 |

- 전체 래퍼 비교 범위: 원문 `record`, 현재 QA, 최신 적용 보완, 유효 모델 목록, 절차 보완, 사용 정책
- 비교 방식: JSON 공백 모양이 아니라 파싱한 내용 전체 대조
- 로컬·반영 작업 공간의 기존 JAR에도 동일한 지식 1,542건 포함
    - 운영 서버 프로세스가 해당 JAR을 실행 중이라는 확인은 아님

---

## 2. B15·B16 ID가 나오는 이유

- 외부 `README_B30.md`의 B30 설명: B29 자료·검토·적용표를 함께 읽는 오프라인 통합본
- B30에서 새 제조사 사실을 추가하지 않았고 원래 사실 ID 유지
- **통합본 버전 B30과 사실 ID의 최초 배치 접두사는 서로 다름**
- `B30-` 사실 ID는 원본에도 없음

| 원래 ID 접두사 | B30 원본 | 서비스 | 누락 |
| --- | ---: | ---: | ---: |
| B15 | 152 | 136 | 16 |
| B16 | 159 | 137 | 22 |
| B17 | 287 | 283 | 4 |
| B18 | 45 | 45 | 0 |
| B19 | 60 | 58 | 2 |
| B20 | 19 | 15 | 4 |
| B21 | 46 | 46 | 0 |
| B22 | 29 | 21 | 8 |
| B23 | 53 | 38 | 15 |
| B24 | 126 | 106 | 20 |
| B25 | 49 | 44 | 5 |
| B26 | 23 | 16 | 7 |
| B27 | 22 | 22 | 0 |
| B28 | 43 | 43 | 0 |
| B29 | 95 | 86 | 9 |
| T1〜T5 합계 | 446 | 446 | 0 |
| T6 | 5 | 0 | 5 |
| 합계 | 1,659 | 1,542 | 117 |

### 2.1 검토 자료 수량

- 예: `B15-MFL71959119-warranty`의 최신 적용 보완에 `B29-MFL71959119-core-warranty` 연결
- B15·B16 ID를 가진 서비스 사실 중 최신 적용 보완이 있는 행: 17건

| 항목 | 원본 | 서비스 |
| --- | ---: | ---: |
| 현재 QA가 있는 행 | 46 | 36 |
| 최신 적용 보완 | 45 | 39 |
| 절차 보완 연결 | 19 | 19 |

- 원본 전체의 검토 수량을 서비스 전체 사용 수량으로 표시하지 않음

### 2.2 검색 우선순위

- `S836MEE022`의 냉각 약화에 맞는 현재 사실이 B15에 있는 것은 정상
- 같은 모델의 B29 사실이 있다는 이유로 다른 주제의 보증·서비스 지침을 냉각 원인의 근거로 우선하지 않음
- [검색기][retriever]: 모델·품목·주제와 일치를 먼저 검사하며, 배치 번호가 큰 자료를 우선하지 않음
- 점수와 모델 적용 상태까지 같으면 ID 순서로 정렬
- 최신 의미 관계 처리를 배치 번호 내림차순으로 대신하지 않음

---

## 3. 검색을 실제 코드로 재현한 결과

- 방법: 현재 작업본과 원격 반영본을 각각 컴파일하고 `KnowledgeRetriever.lexical` 직접 실행
- 사용하지 않은 것: 모델 API, 벡터 검색, 사용자 DB
- 아래 표는 진단 사례이며 검색 품질 통과율이 아님

| 모델 / 질문 | 현재 작업본 결과 | 확인한 의미 |
| --- | --- | --- |
| S836MEE022 / 냉장고가 시원하지 않아요. | B15의 `no_cooling`, `weak_cooling` | 당시 답변에 나온 두 설명과 연결됨 |
| S836MEE022 / 냉장고 안에서 냄새가 나요. | B16의 `topic_46-000` | B16은 이 질문에는 맞는 냄새 자료 |
| FX24ENER / 탈수할 때 세탁기가 많이 흔들려요. | B17의 `topic_66-009` | 진동 조건 자료 사용 |
| RD21ESE / 배수가 안 돼요. | B29의 `drain-tank`, `hose-thaw`, `notice-1` | B29 자료도 실제 검색됨, 검색 성공이 조치 승인은 아님 |
| RD21ESE / 문이 안 닫혀요. | B29의 `notice-2` | 동일하게 후기 배치 사용 확인 |
| F12WVAR / 세탁 중 빨래 추가 방법이 궁금해요. | B28의 `add-laundry` | B28 자료도 실제 검색됨 |
| F12WVAR / 섬유유연제가 나오지 않아요. | 없음 | 해당 제목 계열 자료가 있어도 자연어 검색을 통과하지 못하는 범위가 있음 |
| M876GBB231 / 전체 용량은 몇 리터인가요? | 없음 | 제품 사양 레코드의 존재와 실제 응답 가능성을 구분해야 함 |

### 3.1 작업본과 원격의 차이

- **원격 `66521d3`의 어휘 검색: 제시된 냉각 질문에 B16 냄새 문서 반환**
- 작업본의 미커밋 `KnowledgeTextMatcher` 수정
    - "냉장고 안"을 냉각 실패로 오인하지 않음
    - "정지·저하" 등 냉각 표현 인식
- 작업본 결과: B15의 적절한 두 사실 반환
- 이 수정은 앞선 1~8단계 푸시에서 제외된 별도 변경이며, 아직 원격 반영본에 없음
- 이번 조사에서 새로 구현한 수정으로 계산하지 않음

### 3.2 재현의 한계

- 실제 화면의 조회는 임베딩 상태·검색어 변환·설정에 따라 달라질 수 있음
- 위 어휘 재현만으로 화면 전체가 같은 경로를 거쳤다고 확정하지 않음
- 배포 시 함께 확인할 값: 원격 커밋, 검색 모드, 선택 문서 ID

---

## 4. 제시된 냉장고 답변이 대부분 "확인 필요"인 이유

- 등록 화면 확인 값: `S836MEE022`, 구매일 `2026-05-05`, 제주 지역
- 개인의 도로명·우편번호·상세 주소는 이 문서와 증거 파일에 복제하지 않음

### 4.1 설명할 정보가 최종 답변에서 누락

B30 원문과 서비스 래퍼의 두 사실은 서로 일치합니다.

| 사실 | 원문 증상 | 원문의 `action_summary` | 구조화 결과 |
| --- | --- | --- | --- |
| `B15-MFL71401523-no_cooling` | 냉장·냉동 정지 | 전원 연결·전원 플러그 상태 확인 | `actionDescriptions`에 보존 |
| `B15-MFL71401523-weak_cooling` | 냉각 성능 저하 | 보관 온도·문 닫힘·식품 배치·주위 설치 환경 확인 | `actionDescriptions`에 보존 |

- 두 행의 `causes`: 비어 있음
- [AnswerContext][answer-context]: 증상·원인·일부 분기 조건만 답변 사실로 만들고 `actionDescriptions`는 사용하지 않음
- 결과: 증상 이름만 남고 "원인을 특정할 수 없어 공식 점검" 문장 반복
- [구조화 생성기][structure-script]: 조치 설명을 검색 본문에는 포함하지만 화면 본문에는 같은 방식으로 넣지 않음
- 문제의 성격: 원문 부재가 아니라 **검색용 자료 → 설명용 사실 사이의 전달·검토 범위가 좁은 문제**
- 주의: 모든 `action_summary`를 실행 안내로 풀지 않음
    - 구분 대상: 설명 가능한 관찰 항목, 사용자 확인 질문, 전원 조작·청소·분해 등 실행 지시
    - 필요한 것: 모델 조건과 출처를 붙인 검토된 설명 계약
    - 전원 관련 확인을 임의의 플러그 조작 지시로 바꾸지 않음

### 4.2 실행 승인 없음이 서비스 안내 전체를 호출

- 승인 계획: 현재 `TR15WV5 / UE / 세탁물 뭉침` 조건부 1건
- S836MEE022는 실행 계획이 없어 `get_diagnostic_plan`이 `NOT_VERIFIED` 반환
- `NOT_VERIFIED`가 뜻하지 않는 것: "제품이 위험하다", "모든 원인을 모른다", "설명도 못 한다"
- [RepairFollowupPolicy][followup-policy]: `NOT_VERIFIED`이면 보증·센터·전달 요약 자동 조회
    - 고객이 수리 접수를 요청하지 않아도 응답이 이 정보로 채워짐
- [ConsultationMessage][consultation-message]: 보증 결과가 있으면 전체 보증 카드 표시
- [ToolResults][tool-results]: 각 도구 결과 추가 표시
- 채팅 본문의 반복을 줄여도 카드 전체는 남음

### 4.3 수정 전: 구매일이 있어도 기간 계산을 막는 조건 결합

- 이 절의 원인·합성 재현: 구매일 기준 변경 **이전** 상태(증거 JSON도 당시 결과 보존)
- 이후 개발자 요구에 따라 [구매일 기준 계산][warranty-impl] 구현, 변경 후 결과는 [별도 검수 기록][purchase-warranty]
- 입력 경로
    - [등록 화면][product-dialog]과 [ProductRequest][product-request]: 모델·구매일·주소를 받음
    - 받지 않는 값: 증빙 여부, 국내 신품·가정용 사용 여부, 주요 부품 유형
    - 새 제품을 [UserProduct.update][user-product]로 저장하면 해당 보증 입력은 `null`
- 수정 전 [WarrantyService.row][warranty-service]: 구매일뿐 아니라 적용 조건과 증빙까지 참이어야 `expiresOn` 계산
    - 하나라도 미확인이면 만료일·기간 상태·요금 상태가 함께 `UNKNOWN/CONFIRM_REQUIRED`
    - 결과: 등록 구매일은 보이지만 보증 카드는 모두 미확인
- 합성 재현(사용자 DB 대신 같은 등록 메서드로 합성 제품 생성)
    - 구매일은 보증 서비스까지 전달, 조건 2개가 `null`이면 만료일 없음
    - 같은 입력에 증빙·국내 가정용 조건을 명시적으로 참으로 주면 만료일 `2027-05-05`, `IN_WARRANTY`
    - 이 고객의 조건을 실제 확인했다는 의미는 아님
- 개발자 결정: 현재 구매일을 보증 시작일로 간주
    - 분리: **구매일 기준 기간 상태·만료일**과 **실제 무상 수리의 적용 조건**
    - 배송일이나 증빙 유무로 시작일을 바꾸거나 기간 계산을 막지 않음
    - 해당 사례의 안내: "등록 구매일 기준 제품 전체 보증기간 안입니다. 만료일은 2027-05-05입니다." 무상 적용·비용 조건은 별도 표시

### 4.4 주소는 저장되지만 센터 검색용 지역으로 미연결

- 신규 `UserProduct`의 `deliverySido`, `deliverySigungu`: 빈 문자열로 초기화
- 일반 제품 등록: `address`는 저장하지만 두 필드는 채우지 않음
- [센터 도구][find-centers-tool]: 주소 문자열이 아닌 두 지역 필드로 검색
- 합성 등록 결과: `address_present=true`인데 시·도·시·군·구가 모두 빈 값
- 화면에 주소가 있다고 해서 센터 도구가 제주를 검색했다고 볼 수 없음
- 실제 저장 행이 기존 전용 API로 생성됐다면 지역 값이 있을 수 있으므로 해당 고객 DB의 공란은 단정하지 않음
- 일반 등록 경로의 누락: 실제 코드와 합성 재현으로 확인
- 별도 문제: `centers.json`이 대치·분당·북수원 3곳뿐이라 제주를 정상 전달해도 결과 없음
- 두 문제 모두 처리가 필요하며, "검색 결과 없음"을 "제주에 A/S 센터가 없음"으로 표시하지 않음

---

## 5. 누락 117건을 처리하는 기준

| 분류 | 건수 |
| --- | ---: |
| 원본 `KIMCHI` 사실 | 97 |
| T6 경로 | 5 |
| B23 모델·설명서 연결 | 7 |
| B29 순위 참고 | 5 |
| 범위/공통 QA | 3 |
| 합계 | 117 |

- KIMCHI 외 20건: T6 경로, B23 모델·설명서 연결, B29 순위 참고, 범위/공통 QA
- 상세 ID: 증거 JSON
- `models.json`의 김치냉장고 코드 5개는 `KIMCHI`, 지원 상담 enum은 4품목
    - 원본 분류를 무조건 일반 냉장고로 바꿔 가져오지 않음
- 모든 누락 행을 고객 검색 문서로 넣는 것도 해결책이 아님
    - 순위 참고나 QA 자체는 수리 설명이 아님
    - 모델 연결과 경로는 해당 도메인의 참조 자료로 쓰는 편이 맞을 수 있음
- 현재 서비스 래퍼 4건이 누락된 `B23-MAPPING-RG20WN/RG20VN`을 참조
- 참조마다 보조 이력인지 필수 실행 근거인지 구분해 복원·제외 이유 기록 필요

---

## 6. 해결 순서와 완료 기준

| 우선순위 | 할 일 | 완료를 확인하는 기준 |
| --- | --- | --- |
| P0 | 작업본 냉각 어휘 수정의 독립 검증·별도 반영 | 동일 질문에서 S836MEE022의 냉각 사실 선택, 냄새·실내등 오선택 제외. 원격에서도 같은 결과 확인 |
| P0 | 등록 주소와 상담용 지역 연결 | 주소 검색의 시·도·시·군·구 코드·명칭을 저장하고 기존 공란은 미확인으로 구분. 수도권·제주 각각 확인 |
| P0 | 센터 3곳을 전국 조회처럼 사용하는 경로 교체 | [A/S 운영 설계][service-channel]의 출장 우선·공식 연결 방식 적용 |
| P1 | 냉각 약화 설명과 관찰 질문 연결 | 증상 명칭만 반복하지 않고 원문에 있는 확인 항목을 설명. 실제 원인이나 실행 승인을 추측하지 않음 |
| P1 | 구매일 기준 기간과 무상 조건 분리 | 날짜 계산·비용 조건 분리는 [후속 검수][purchase-warranty]에 기록. 컴프레서 후보 3종 전체 펼침 축약은 별도 후속 범위 |
| P1 | 질문 의도에 맞는 후속 흐름과 카드 축약 | 실행 승인 미확인만으로 접수·보증·요약을 모두 펼치지 않음. 접수 요청 또는 설명 후 미해결 시 확장 |
| P2 | B30 전체의 결정적 수입 생성기와 제외 명세 | 1,659개 ID 각각에 사용 위치/제외 이유. 117건과 참조 공백을 설명하고 재생성·해시 대조 가능 |
| P2 | 자료 유형별 설명 투영 보완 | 사양·정상 동작·조작 조건 등에서 원본의 중요한 필드가 빠지는지 별도 평가 |

냉장고 사례의 목표 답변은 검토된 설명과 **다음에 확인할 관찰 질문 하나**를 먼저 제공하는 것입니다.

1. 등록 모델을 확인했다는 짧은 안내
2. 해당 설명서에서 냉각 약화와 관련해 다루는 보관 온도·문 닫힘·식품 배치·설치 환경 설명
3. "냉동실도 함께 약한가요, 냉장실만 그런가요?"처럼 관찰 상태 질문

- 위 순서는 설계 목표이며, 이 문서 작성 시점에 응답 로직을 변경한 것은 아님

---

## 7. 재확인 방법과 범위

### 7.1 확인 명령

원본 경로를 실제 환경에 맞게 지정한 뒤 ID 접두사와 래퍼 보존을 확인합니다.

```powershell
@'
import json, collections
from pathlib import Path
b30 = next(p for p in Path.cwd().parent.glob('LG */LG_*B30*') if p.is_dir())
source = {r['id']: r for r in map(json.loads, (b30/'knowledge_effective_B30.jsonl').read_text(encoding='utf-8').splitlines())}
local = json.loads(Path('backend/src/main/resources/homecare/knowledge.json').read_bytes())
print('source / local:', len(source), len(local))
print('prefixes:', dict(collections.Counter(r['id'].split('-')[0] for r in local)))
print('missing:', len(set(source)-{r['id'] for r in local}))
print('wrapper mismatches:', sum(json.loads(r['text']) != source[r['id']] for r in local))
'@ | python -
```

- 증거 JSON: 실행한 Java 소스와 임시 Gradle 작업 포함, 작업 공간 경로를 맞추면 검색 9개 사례와 합성 등록·보증 계산 재현 가능
- 기존 구조화 재현 검사: `python -I backend/scripts/structure_knowledge.py --check`

### 7.2 점검 범위

- 수행한 범위: 자료·코드·JAR 대조, 두 코드 상태에서 각각 9개 사례의 실제 검색기 재현, 합성 등록·보증 계산, 공식 서비스 안내 확인
- 확인하지 않은 범위: 실제 사용자 DB, 운영 프로세스, 모델의 전체 답변 재생성, 제품 실물, 개인별 무상 수리 자격
- 기존 미커밋 검색·화면 변경은 이 점검에서 수정·커밋·푸시하지 않음

---

## 8. 4절 해결 반영

- 기준: 2026-10-15 로컬 작업본(작성 당시 미커밋)

### 8.1 설명 누락(4.1)

- 변경: [ManualCheckItems][manual-check-items]가 `actionDescriptions`와 분기 `action_summary` 중 "…를 확인한다" 형태의 짧은 관찰 항목만 골라 REFERENCE 설명에 추가
    - 넣지 않는 것: 조작·청소·분해·재시작·문의 문장, 연결어미가 섞인 절차 문장, `stop_selfcare_and_contact_service=true` 레코드
- 확인한 결과
    - 현재 데이터에서 통과하는 16개 항목은 `ManualCheckItemsTests`의 검토 목록과 정확히 같아야 함
    - 데이터가 바뀌어 새 항목이 생기면, 검토해 목록에 추가하기 전까지 테스트 실패

### 8.2 관찰 질문(4.1)

- 변경: 냉장고 COOLING 증상은 설명 뒤 "냉장실과 냉동실이 모두 전혀 차갑지 않나요, 아니면 차갑긴 하지만 약한가요?"를 한 번 질문
    - 근거: 설명서의 냉장·냉동 정지 / 냉각 성능 저하 구분
    - 답은 `COOLING_STATE`로 저장되어 확인 증상과 상담 요약에 포함
    - 첫 문장에 "전혀/약해요"가 있으면 묻지 않음, 모르겠다·서비스 요청이면 질문 종료
- 확인한 결과(S836MEE022)
    - 포함: "정전 여부와 전원 플러그 연결", "보관 온도, 문 닫힘, 식품 배치와 주위 설치 환경"
    - 제외: 냄새 자료의 1℃ 낮추기·밀폐 보관·청소

### 8.3 자동 확장(4.2)

- 변경: `NOT_VERIFIED`만으로 보증·센터·요약을 호출하지 않음
    - 확장 조건: 같은 증상을 이전 답변에서 이미 설명했고(저장 이력의 `get_diagnostic_plan`) 남은 관찰 질문이 없을 때
    - 서비스·비용 요청과 검토 결과(ALL_DONE·NO_SELF_CARE·선행 조건)는 기존과 동일
- 확인한 결과: 첫 냉각 질문의 도구 호출 5회 → 2회

### 8.4 보증(4.3)

- 변경: 구매일 기준 계산(개발자 결정) 유지
- 확인한 결과: 통합 검사에서 관찰 질문에 답한 두 번째 턴이 IN_WARRANTY·만료일 2027-05-05를 저장 이력까지 전달

### 8.5 지역(4.4)

- 변경
    - 제품 등록·수정 API가 주소 검색 결과의 `deliverySido/deliverySigungu`를 받아 정규화해 저장
    - 직접 입력으로 기존 주소를 바꾸면 지역 삭제
    - 주소 없는 지역·잘못된 시·도는 400
    - 등록 화면과 마이페이지에 A/S 지역 표시
- 확인한 결과: 제주·서울 등록, 같은 주소 유지, 직접 입력 교체 검사

### 8.6 센터(4.4)

- 변경
    - `find_service_centers`는 센터 3곳을 조회하지 않음
    - 반환 값: `directory_status=NOT_INTEGRATED`, `region_status`, 공식 출장 접수·센터 찾기·전화, `booking_status=NOT_SUBMITTED`
    - 지역이 없으면 `NEEDS_INPUT`(`CONFIRM_REGION`)
    - 새 상태값을 추가하지 않아 MCP 공통 계약은 유지하고 도구 설명만 재생성
    - 과거 저장 결과는 "저장 당시의 센터 안내"로 표시
- 확인한 결과: 서울 강남구도 대치센터를 반환하지 않음

### 8.7 검증과 남은 범위

- 검증: Java 314개(MySQL 전용 2개 건너뜀), Python 21개, 프론트엔드 27개·lint·build 통과
- 확인하지 않은 것: 실제 OpenAI 모델 응답, 운영 서버 재시작 후 화면
- 남은 범위
    - 기존 제품의 빈 지역은 주소 문자열을 쪼개 자동 보정하지 않음, 마이페이지에서 주소 검색으로 다시 저장 필요
    - 관찰 질문은 냉장고 냉각 증상에만 있음
    - 확인 항목은 16개로 좁음, 확대는 검토 목록 추가로 진행
    - `centers.json`은 매니페스트 검증 대상이라 파일은 유지하되 조회에 쓰지 않음
    - 행정구역 코드 저장은 DB 변경이 필요해 보류

[audit-json]: ../benchmarks/manufacturer_data_usage_audit_20261015.json
[tool-data-catalog]: ../../backend/src/main/java/com/project/backend/mcp/tool/ToolDataCatalog.java
[structure-script]: ../../backend/scripts/structure_knowledge.py
[cost-script]: ../../backend/scripts/build_homecare_data.py
[plans-script]: ../../backend/scripts/build_approved_plans.py
[retriever]: ../../backend/src/main/java/com/project/backend/mcp/retrieval/KnowledgeRetriever.java
[answer-context]: ../../backend/src/main/java/com/project/backend/mcp/agent/AnswerContext.java
[followup-policy]: ../../backend/src/main/java/com/project/backend/mcp/agent/RepairFollowupPolicy.java
[manual-check-items]: ../../backend/src/main/java/com/project/backend/mcp/agent/ManualCheckItems.java
[consultation-message]: ../../frontend/src/components/ConsultationMessage.jsx
[tool-results]: ../../frontend/src/components/ToolResults.jsx
[product-dialog]: ../../frontend/src/ProductDialog.jsx
[product-request]: ../../backend/src/main/java/com/project/backend/product/ProductRequest.java
[user-product]: ../../backend/src/main/java/com/project/backend/product/UserProduct.java
[warranty-service]: ../../backend/src/main/java/com/project/backend/warranty/WarrantyService.java
[find-centers-tool]: ../../backend/src/main/java/com/project/backend/mcp/tool/handler/FindServiceCentersTool.java
[warranty-impl]: warranty_implementation.md
[purchase-warranty]: purchase_date_warranty_verification.md
[service-channel]: service_channel_operating_plan.md
