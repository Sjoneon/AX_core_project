# HomeCare AI

> 등록한 가전 제품과 증상을 바탕으로 자가점검 단계, 보증, 예상 수리비, 공식 A/S 경로를 안내하는 상담 서비스
> 지원 품목: 세탁기 · 건조기 · 냉장고(김치냉장고 포함) · 식기세척기

## 구성

```text
React 프론트엔드
 → Spring Boot 백엔드(OpenAI 모델을 쓰는 상담 Agent)
 → Java MCP 클라이언트
 → Python FastMCP 서버
 → Spring 도메인 API·정적 자료
```

| 폴더 | 내용 |
| --- | --- |
| [backend/](backend/README.md) | Spring Boot 백엔드 |
| [frontend/](frontend/README.md) | React 프론트엔드 |
| [mcp_server/](mcp_server/README.md) | Python FastMCP 서버 |
| [ops/](ops/README.md), [proxy/](proxy/README.md), [terraform-aws-ec2/](terraform-aws-ec2/README.md) | 배포·운영 |
| [docs/](docs/) | 설계·구현·검증 기록 |
| [수리비_정규화_과정/](수리비_정규화_과정/) | 수리비 정규화 패키지와 근거 표 |

**주요 문서**

- [상담 설계 결정 기록](docs/mcp/consultation_design_decisions.md): 결정, 이유, 구현 위치
- [MCP 도구 구현](docs/mcp/mcp_implementation.md): 도구 9개의 구조와 계약
- [실제 상담 평가와 회귀 검증](docs/mcp/consultation_validation.md): 실제 모델 상담 평가 결과

---

## 개발 방식

- 원본 팀 저장소: [gwonu1/core_project](https://github.com/gwonu1/core_project)

### 팀원이 구축한 기반

로그인, 채팅, AWS 인프라, 초기 CI/CD, 마이페이지는 팀원(gwonu1)이 구축했습니다.
프론트(react)의 화면(로그인, 회원가입, 메인, 마이페이지)는 팀원(hyeonjeong5788-lab)이 구축했습니다.

### 개발자가 맡은 일

- 지원 범위·상담 동작 결정(등록 제품 활용, 위험 확인 버튼, 구매일 기준 보증, 응답 카드 구성 등)
- 코드 리뷰(구조·함수·변수 이름·로직 검토)
- 실제 상담 출력 검수와 문제 제기(무관한 근거, JSON 노출, 제품 선택 반복, 센터 하드코딩 등)
- 평가 기준 제시(분류·도구 성공과 답변 적합성 구분)
- 단계별 개발·커밋 관리

**관련 기록**

- 결정과 이유: [상담 설계 결정 기록](docs/mcp/consultation_design_decisions.md)
- 검수에서 찾은 문제와 조치: [검수에서 찾은 문제](docs/mcp/consultation_design_decisions.md#4-검수에서-찾은-문제)
