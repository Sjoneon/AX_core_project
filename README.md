# HomeCare AI

등록한 가전 제품과 증상을 바탕으로 자가점검 단계, 보증, 예상 수리비, 공식 A/S 경로를 안내하는 상담 서비스입니다. 지원 품목은 세탁기·건조기·냉장고(김치냉장고 포함)·식기세척기입니다.

구성: React 프론트엔드 → Spring Boot 백엔드(OpenAI 모델을 쓰는 상담 Agent) → Java MCP 클라이언트 → Python FastMCP 서버 → Spring 도메인 API·정적 자료

| 폴더 | 내용 |
|---|---|
| [backend/](backend/README.md) | Spring Boot 백엔드 |
| [frontend/](frontend/README.md) | React 프론트엔드 |
| [mcp_server/](mcp_server/README.md) | Python FastMCP 서버 |
| [ops/](ops/README.md), [proxy/](proxy/README.md), [terraform-aws-ec2/](terraform-aws-ec2/README.md) | 배포·운영 |
| [docs/](docs/) | 설계·구현·검증 기록 |
| [수리비_정규화_과정/](수리비_정규화_과정/) | 수리비 정규화 패키지와 근거 표 |

주요 문서: [상담 설계 결정 기록](docs/mcp/consultation_design_decisions.md) · [상담 흐름·카드 개편 구현 기록](docs/mcp/consultation_card_flow.md) · [MCP 단계별 작업 계획과 기록](docs/mcp/mcp_completion_plan.md) · [검증 가이드](docs/guides/verification_guide.md) · [남은 작업](docs/mcp/remaining_tasks.md)

## 개발 방식

<!-- 개발자 직접 검토·수정 필요 -->

원본 팀 저장소는 [gwonu1/core_project](https://github.com/gwonu1/core_project)입니다.

### 팀원이 구축한 기반

로그인, 채팅, AWS 인프라, 초기 CI/CD, 마이페이지는 팀원(gwonu1)이 구축했습니다.

<!-- 확인 필요: git 이력의 팀원 커밋에는 위 목록 밖의 작업도 있다.
     RAG 기능 초기 구현(daa5f81), 스트리밍 응답(3b1d20c), 이전 대화 맥락 유지(6ba8707),
     DB 마이그레이션 자동화(dc283b4), Vercel 배포(1a81e3e), MCP 배포 호환 수정(b1a4049, 421065a).
     어디까지 적을지 개발자가 정한다. -->

### AI 코딩 도구 활용과 역할 분담

구현에는 AI 코딩 도구를 활용했습니다.

개발자가 맡은 일:

- 지원 범위·상담 동작 결정(등록 제품 활용, 위험 확인 버튼, 구매일 기준 보증, 응답 카드 구성 등)
- AI가 생성한 코드 리뷰(구조·함수·변수 이름·로직 검토)
- 실제 상담 출력 검수와 문제 제기(무관한 근거, JSON 노출, 제품 선택 반복, 센터 하드코딩 등)
- 평가 기준 제시(분류·도구 성공과 답변 적합성 구분)
- 단계별 개발·커밋 관리

AI 코딩 도구는 개발자의 지시와 리뷰에 따라 코드 작성을 보조했습니다.

결정과 이유는 [상담 설계 결정 기록](docs/mcp/consultation_design_decisions.md)에, 검수에서 찾은 문제와 조치는 같은 문서의 [검수에서 찾은 문제](docs/mcp/consultation_design_decisions.md#3-검수에서-찾은-문제)에 있습니다.

<!-- 근거 확인 메모(초안 작성 시):
     - 저장소 문서에 근거가 있는 항목: 구매일 기준 보증(warranty_implementation.md "사용자 요구사항에 따라"),
       응답 카드·흐름(consultation_flow_target.png "사용자가 준 흐름도"), 4품목 범위(repair_development_guide.md "사용자의 범위 확정"),
       답변 적합성 기준(consultation_validation.md "사용자 요청에 따라"), 무관한 근거·JSON 노출·센터 3곳 문제의 기록.
     - 저장소 문서에서 근거를 찾지 못한 항목: "AI가 생성한 코드 리뷰"의 구체 사례, "제품 선택 반복" 문제 기록.
       (가장 가까운 기록은 "닉네임 때문에 묻지 않고 첫 냉장고가 선택됨"이다.)
     - "등록 제품 활용"은 mcp_completion_plan.md에 "작업 시 유지할 사항"으로 적혀 있다. -->
