"""HomeCare MCP의 __pycache__ 사용 여부별 시작 시간 비교(개발/테스트용)

실행: python benchmark_pycache.py <mcp_server dir> <python exe> <runs> <out.json>
표본마다 새 프로세스에서 homecare_mcp import와 create_server()의 도구 등록을 측정합니다.
"""
import json
import os
import platform
import random
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

root, py, runs, out = Path(sys.argv[1]).resolve(), sys.argv[2], int(sys.argv[3]), Path(sys.argv[4])
pkg = root / "homecare_mcp"
CHILD = ("import sys,time;t=time.perf_counter();from homecare_mcp import create_server;create_server();"
         "print((time.perf_counter()-t)*1000, len(sys.modules))")


def clear_own_cache():
    for d in pkg.rglob("__pycache__"):
        shutil.rmtree(d)


def run(env_extra, flags):
    """별도 Python 프로세스의 import·도구 등록 시간과 전체 소요 시간 측정(ms)"""
    env = {k: v for k, v in os.environ.items() if k not in ("PYTHONPYCACHEPREFIX", "PYTHONDONTWRITEBYTECODE")}
    env.update(env_extra)
    start = time.perf_counter()
    res = subprocess.run([py, *flags, "-c", CHILD], cwd=root, env=env, capture_output=True, text=True, check=True)
    wall = (time.perf_counter() - start) * 1000
    inproc, modules = res.stdout.split()
    return float(inproc), wall, int(modules)


tmp = Path(tempfile.mkdtemp(prefix="pycache-bench-"))
empty_prefix, warm_prefix = tmp / "empty", tmp / "warm"
empty_prefix.mkdir()
# 전체 모듈의 비교용 bytecode 캐시 생성
run({"PYTHONPYCACHEPREFIX": str(warm_prefix)}, [])

SCENARIOS = {
    # 전체 소스 재컴파일(캐시 읽기·쓰기 제외)
    "S1_all_no_cache": ({"PYTHONPYCACHEPREFIX": str(empty_prefix)}, ["-B"], None),
    # 미리 생성한 전체 모듈 캐시 사용
    "S2_all_cached": ({"PYTHONPYCACHEPREFIX": str(warm_prefix)}, [], None),
    # homecare_mcp만 소스에서 로드하고 의존성 캐시는 사용(Docker 실행 조건)
    # PYTHONDONTWRITEBYTECODE=1 설정으로 자체 .pyc 저장 제외
    "S3_own_code_no_cache": ({}, ["-B"], clear_own_cache),
    # 일반 로컬 개발 조건의 __pycache__ 사용
    "S4_own_code_cached": ({}, [], None),
}

# S4 자체 캐시 생성과 시나리오별 OS 파일 캐시 예열
run({}, [])
for name, (env, flags, prep) in SCENARIOS.items():
    for _ in range(2):
        if prep: prep()
        run(env, flags)
    if name == "S3_own_code_no_cache":
        run({}, [])  # S4 측정용 자체 캐시 복원

samples = {name: [] for name in SCENARIOS}
order = [name for name in SCENARIOS for _ in range(runs)]
random.seed(20261013)
random.shuffle(order)  # 배경 작업 영향을 분산하는 시나리오 순서 혼합
for name in order:
    env, flags, prep = SCENARIOS[name]
    if prep: prep()
    samples[name].append(run(env, flags))
    if prep: run({}, [])  # S4 예열 상태 유지를 위한 자체 캐시 복원

def summary(values):
    return {"median": round(statistics.median(values), 1), "mean": round(statistics.mean(values), 1),
            "stdev": round(statistics.stdev(values), 1), "min": round(min(values), 1), "max": round(max(values), 1)}

own_py = list(pkg.rglob("*.py"))
result = {
    "environment": {"os": platform.platform(), "python": subprocess.run([py, "-c", "import sys;print(sys.version)"],
                    capture_output=True, text=True).stdout.strip(), "cpu": platform.processor(),
                    "cpu_count": os.cpu_count(), "runs_per_scenario": runs},
    "own_code": {"py_files": len(own_py), "lines": sum(len(p.read_text(encoding="utf-8").splitlines()) for p in own_py),
                 "py_bytes": sum(p.stat().st_size for p in own_py)},
    "cache_size_bytes_total": sum(p.stat().st_size for p in warm_prefix.rglob("*.pyc")),
    "cache_files_total": sum(1 for _ in warm_prefix.rglob("*.pyc")),
    "scenarios": {name: {"modules_loaded": vals[0][2],
                         "import_and_register_ms": summary([v[0] for v in vals]),
                         "process_wall_ms": summary([v[1] for v in vals]),
                         "raw_import_ms": [round(v[0], 1) for v in vals]} for name, vals in samples.items()},
}
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
shutil.rmtree(tmp, ignore_errors=True)
print(json.dumps({k: {"modules": v["modules_loaded"], "import_ms": v["import_and_register_ms"],
                      "wall_ms": v["process_wall_ms"]} for k, v in result["scenarios"].items()}, indent=1))
print("own_code", result["own_code"], "cache", result["cache_files_total"], result["cache_size_bytes_total"])
print(result["environment"])
