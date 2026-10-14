# MCP·보증 구현 검증 기록

2026-10-13 · 4품목 범위 수정 후 재검증 · 로컬 Windows, JDK 21, Python 3.13.2, Node 24

| 항목 | 결과 | 검증 방식 |
|---|---|---|
| 기존 인증·상담·보증 API | 62개 통과 | Spring Boot + 로컬 모의 OpenAI + H2 |
| 보증 계산 | 15개 통과 | 고정 Clock, 4품목, 전체/부품 분리, 2년/4년 TEST_ONLY, 만료일·윤년·증빙·생산 조건 |
| 실제 MCP HTTP 통합 | 5개 통과 | Java SDK → 실제 Python FastMCP → 실제 Spring HTTP, 8개 호출·소유권·상태 버전·Agent 선택·이력 |
| 도구 경계 | 12개 통과 | TEST_ONLY 금액·승인 계획, 구성 합계/v3 분리, NULL·확신도·VALIDATION 제외, 한 단계, 4품목 가스 경고, 미확인 증상·미등록 제품 안전 차단 |
| Python MCP | 14개 통과 | 실제 FastMCP Client discovery/call, 입력 타입·정책/금액 주입 차단, 인증·안전한 오류 |
| React | lint / production build 통과 | ESLint + Vite |
| 백엔드 산출물 | `test bootJar` 통과 | `backend/build/libs/app.jar` |
| Docker compose | 과거 구성 검사 통과, 이번 재실행 없음 | 로컬 compose, 운영 compose + MCP override, `config --quiet --no-interpolate` |
| DB 초기화 호환·권한 | 10개 통과 | `python -m unittest discover -s ops/tests -p test_rds.py -q`, 과거 V001 checksum 보존·임의 SQL 변경 거부 |
| 변경 파일 | diff 공백 검사 통과 | `git diff --check` |

백엔드 합계 94개다. 테스트용 OpenAI 응답이 도구를 선택했음을 검증했고, 응답에 넣은 가짜 가격·기간이 고객 설명에 표시되지 않는지도 검사했다. 실제 OpenAI의 도구 선택 정확도를 증명하는 결과는 아니다. HTTP 통합 검증에서는 도구 이름만 등록한 모형 서버를 사용하지 않고 실제 FastMCP 서버 프로세스를 실행했다.

추가로 기존 운영 스크립트 테스트 21개를 Windows에서 실행했다. 심볼릭 링크 권한, POSIX 파일 권한(0600), Bash/Windows 경로 차이로 1 failure·6 errors가 발생했다. 해당 스크립트를 이 환경에 맞춰 바꾸지 않았으며 Linux CI에서 재확인이 필요하다. 이 실패를 운영 배포 성공으로 보고하지 않는다. Docker daemon이 실행 중이지 않아 이미지 실행 검증은 하지 않았다.

실행 명령과 Python 경로 설정은 [MCP README](../../mcp_server/README.md)에 있다. 운영 MySQL migration, 실 OpenAI 호출, 실제 고객 데이터·수리비 재학습, 사용자 테스트, 실제 서비스 예약·배포는 수행하지 않았다.

자료 제한과 운영 전 준비는 [mcp_implementation.md](mcp_implementation.md)를 따른다. 실행 승인 계획·후기 fallback의 정상 결과는 시험 자료로 검증했으며 이를 실제 승인 데이터가 준비된 것으로 간주하지 않는다.

## 4품목 범위 정합성 확인

API enum·보증 정책·MCP discovery 계약·화면·RAG 자료를 세탁기, 건조기, 냉장고(김치 포함), 식기세척기로 통일했다. 서비스 지식은 원문 후보 1건을 추가 제외한 1,542개이며 manifest의 수량·파생 파일 SHA-256과 일치한다. 원문을 편집해 제조사 사실을 변경하지 않았고 제외 ID·사유를 기록했다. 가상 RAG 자료는 매뉴얼 4개·상담 항목 16개·평가 질문 22개로 모든 근거 ID 참조가 유효하다. 소스·문서·생성된 웹 번들·JAR 내 매뉴얼의 범위 정합성을 확인했다. 문서 상대 링크에도 누락이 없다.

4품목 범위 수정 당시 실행한 명령은 backend `clean test bootJar`, Python MCP `unittest -q test_server test_warranty_server`(패키지 분리 전 배치), frontend `npm run lint` 및 `npm run build`, 운영 초기화 시험 `unittest discover -s ops/tests -p test_rds.py -q`다. 현재 패키지 구조에서는 `mcp_server`에서 `.\.venv\Scripts\python.exe -m unittest discover -s tests -t . -q`로 실행한다. 미지원 품목/유형은 모델·upstream 호출 전에 거부하는 회귀를 포함한다. V004 SQL은 준비만 했으며 실제 MySQL/RDS 적용을 검증하지 않았다. 이 문서는 당시 실행 기록이며, 문서 정리 작업에서 애플리케이션 테스트를 다시 실행한 결과는 아니다.
