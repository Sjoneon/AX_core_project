"""Benchmark: HomeCare MCP startup with vs without __pycache__ (.pyc) bytecode cache.

Usage: python benchmark_pycache.py <mcp_server dir> <python exe> <runs> <out.json>
Each sample is a fresh process: import homecare_mcp + create_server() (tool registration).
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
# Populate a complete cache tree (stdlib + site-packages + homecare_mcp) once.
run({"PYTHONPYCACHEPREFIX": str(warm_prefix)}, [])

SCENARIOS = {
    # Every module (stdlib, dependencies, our code) compiled from .py on each start; nothing written.
    "S1_all_no_cache": ({"PYTHONPYCACHEPREFIX": str(empty_prefix)}, ["-B"], None),
    # Every module loaded from .pyc.
    "S2_all_cached": ({"PYTHONPYCACHEPREFIX": str(warm_prefix)}, [], None),
    # Only homecare_mcp compiled from source; dependencies cached (= Docker image: pip pre-compiles
    # site-packages, PYTHONDONTWRITEBYTECODE=1 stops writing our own .pyc).
    "S3_own_code_no_cache": ({}, ["-B"], clear_own_cache),
    # Normal local development: everything cached in __pycache__.
    "S4_own_code_cached": ({}, [], None),
}

# Make sure S4 has its own cache, then warm the OS file cache.
run({}, [])
for name, (env, flags, prep) in SCENARIOS.items():
    for _ in range(2):
        if prep: prep()
        run(env, flags)
    if name == "S3_own_code_no_cache":
        run({}, [])  # restore own cache for S4

samples = {name: [] for name in SCENARIOS}
order = [name for name in SCENARIOS for _ in range(runs)]
random.seed(20261013)
random.shuffle(order)  # interleave to spread background noise evenly
for name in order:
    env, flags, prep = SCENARIOS[name]
    if prep: prep()
    samples[name].append(run(env, flags))
    if prep: run({}, [])  # restore own cache so S4 stays warm

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
