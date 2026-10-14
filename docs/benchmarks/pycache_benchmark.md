# HomeCare MCP 서버 기동 시간: `__pycache__`(.pyc) 유무 비교

측정일 2026-10-13. 원자료는 [pycache_benchmark_20261013.json](pycache_benchmark_20261013.json), 측정 스크립트는 [mcp_server/scripts/benchmark_pycache.py](../../mcp_server/scripts/benchmark_pycache.py)다.

## 측정 조건

- 환경: Windows 11 (10.0.26200), Intel 16 논리 코어, Python 3.13.2 (`mcp_server/.venv`). 운영 이미지는 Python 3.12 slim이라 절대값은 다를 수 있다.
- 측정 대상: 새 프로세스에서 `from homecare_mcp import create_server; create_server()` 실행(import + 도구 8개 등록). HTTP 대기 시간은 포함하지 않는다.
- 시나리오별 30회씩 120회를 섞어서 실행했다. 시나리오마다 예열 2회를 먼저 했다.
- 캐시 제어: `PYTHONPYCACHEPREFIX`(캐시 위치 변경)와 `-B`(캐시 쓰기 금지)를 사용했다. 기존 `__pycache__`는 지우지 않았다.
- 로드된 모듈: 1,328개(표준 라이브러리 + fastmcp·pydantic·httpx 등 + 자체 코드 21개 파일, 454줄).
- 전체 캐시 크기: .pyc 1,231개, 15.1MB.

## 결과 1: 전체 캐시 유무 (표준 라이브러리 + 의존성 + 자체 코드)

| 시나리오 | import+등록 중앙값 | 평균 ± 표준편차 | 프로세스 전체 중앙값 |
|---|---|---|---|
| S1 캐시 없음 (모든 모듈을 매번 .py에서 컴파일) | **2,425.8 ms** | 2,439.9 ± 108.2 ms | 2,645.4 ms |
| S2 캐시 있음 (모든 모듈을 .pyc에서 로드) | **1,217.2 ms** | 1,240.5 ± 74.9 ms | 1,421.7 ms |

캐시가 있으면 기동 시간이 약 1,209ms(49.8%) 줄었다(약 2.0배 빠름). 구간이 겹치지 않는다(S1 최솟값 2,294ms > S2 최댓값 1,405ms).

## 결과 2: 자체 코드(`homecare_mcp`)만 캐시 유무

| 시나리오 | import+등록 중앙값 | 평균 ± 표준편차 |
|---|---|---|
| S3 자체 코드만 캐시 없음 (운영 Docker 이미지 조건) | 1,248.7 ms | 1,248.1 ± 64.8 ms |
| S4 모두 캐시 있음 (로컬 개발 기본) | 1,177.2 ms | 1,190.6 ± 62.9 ms |

자체 코드 21개 파일만 컴파일해서 걸린 순수 시간(같은 프로세스 안에서 200회 반복):

| 항목 | 중앙값 |
|---|---|
| .py → 바이트코드 컴파일 | 2.36 ms |
| .pyc 로드(unmarshal) | 0.08 ms |

S3과 S4의 중앙값 차이는 약 71ms다. 그러나 순수 컴파일 비용은 2.36ms뿐이다. 둘 다 캐시가 있는 S2와 S4도 캐시 위치만 달랐는데 약 40ms 차이가 났다. 따라서 이 71ms는 대부분 프로세스·파일 시스템 쪽 잡음으로 보는 것이 맞다. 자체 코드의 캐시 효과는 기동 1회에 약 2ms 수준이다.

## 해석

- 기동 시간을 좌우하는 것은 의존성(fastmcp·pydantic·httpx 등 1,300여 개 모듈)의 바이트코드 캐시다. 이 캐시가 있으면 기동 시간이 약 절반으로 줄어든다.
- 운영 이미지에서는 `pip install`이 의존성을 설치하면서 .pyc를 미리 만들어 이 이득을 이미 얻는다. `PYTHONDONTWRITEBYTECODE=1`은 자체 코드의 .pyc만 쓰지 않게 하고, 그 비용은 기동 1회당 약 2ms다.
- 캐시를 쓰지 않는 대신 컨테이너 안에 파일을 쓰지 않는다. 그래서 읽기 전용 파일 시스템이나 다른 사용자 권한으로 실행해도 문제가 없다.
- 이 수치는 기동 시간에만 해당한다. 기동 후 도구 호출의 처리 속도는 .pyc 유무와 관계없다(같은 바이트코드를 실행한다).

## 재현

```powershell
cd mcp_server
.\.venv\Scripts\python scripts\benchmark_pycache.py . .\.venv\Scripts\python.exe 30 result.json
```
