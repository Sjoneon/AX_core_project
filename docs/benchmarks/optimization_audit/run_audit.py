"""성능 최적화 후보 검수용 측정 실행기(검증 전용)

실행(저장소 루트):
    python -I docs/benchmarks/optimization_audit/run_audit.py run --work <임시 폴더>
    python -I docs/benchmarks/optimization_audit/run_audit.py summarize

run
    - 대상 커밋을 git archive로 임시 폴더에 내보내고, 그 사본에만 probes/의 검증 클래스를 넣어 실행
    - 저장소 작업 트리·서비스 코드·운영 자원은 변경하지 않음
    - 외부 API 호출 없음: 상담 흐름은 기존 통합 테스트의 모의 OpenAI 서버, 인덱스는 오프라인 계수 모델 사용
    - 필요 환경: JDK 21(Gradle 툴체인), mcp_server 요구 패키지가 설치된 Python(HOMECARE_TEST_PYTHON)
summarize
    - results/의 측정 원자료와 docs/benchmarks/의 기존 기록을 읽어 중앙값·p95·개선율을 계산
    - p95는 최근접 순위(ceil(0.95 × n)번째 값)
"""
import argparse
import datetime
import io
import json
import math
import os
import platform
import shutil
import statistics
import subprocess
import sys
import tarfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RESULTS = HERE / 'results'
PROBES = HERE / 'probes'
EXPORT_PATHS = ['backend', 'mcp_server', 'docs/mcp/retrieval_evaluation.json',
                'docs/benchmarks/manufacturer_retrieval_initial_20261014.json',
                'docs/benchmarks/manufacturer_retrieval_20261014.json']
MCP_PROBES = ['SelfCheckRequestProbe.java', 'SelfCheckRequestTimingProbe.java']
RETRIEVAL_PROBES = ['RetrievalAuditProbe.java', 'IndexCacheProbe.java']
FILTERS = {
    'self_check_rate_limit': 'com.project.backend.mcp.SelfCheckRequestProbe.probeRateLimit',
    'self_check_requests': 'com.project.backend.mcp.SelfCheckRequestTimingProbe.probeRequestsAndTiming',
    'retrieval': 'com.project.backend.mcp.retrieval.RetrievalAuditProbe',
    'index_cache': 'com.project.backend.mcp.retrieval.IndexCacheProbe',
}
# 라벨, 커밋, 실행할 측정
TARGETS = [
    ('before_dd95d9a', 'dd95d9a^', ['self_check_rate_limit', 'self_check_requests']),
    ('after_dd95d9a', 'dd95d9a', ['self_check_rate_limit', 'self_check_requests']),
    ('retrieval_9af5760', '9af5760', ['retrieval']),
    ('current', 'HEAD', ['self_check_rate_limit', 'self_check_requests', 'retrieval', 'index_cache']),
]


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True).stdout


def export(commit, target):
    """커밋의 필요한 경로만 tar로 받아 target에 풀기"""
    data = git('archive', '--format=tar', commit, *EXPORT_PATHS)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(target, filter='data')


def run(args):
    work = Path(args.work).resolve()
    if ROOT in work.parents or work == ROOT:
        sys.exit('작업 폴더는 저장소 밖에 지정합니다.')
    python = Path(args.python or ROOT / 'mcp_server/.venv/Scripts/python.exe').resolve()
    if not python.exists():
        sys.exit(f'HOMECARE_TEST_PYTHON 후보가 없습니다: {python}')
    selected = [t for t in TARGETS if not args.only or t[0] in args.only]
    metadata = {'started': datetime.datetime.now().astimezone().isoformat(timespec='seconds'),
                'platform': platform.platform(), 'processor': platform.processor(),
                'python_for_mcp': python.name, 'targets': []}
    for label, commit, probes in selected:
        probes = [p for p in probes if not args.probes or p in args.probes]
        if not probes:
            continue
        resolved = git('rev-parse', commit).decode().strip()
        tree = work / label
        if tree.exists():
            shutil.rmtree(tree)
        tree.mkdir(parents=True)
        export(resolved, tree)
        # 대상 커밋에 있는 테스트 구성만 쓰도록 필요한 프로브만 복사
        if any(p.startswith('self_check') for p in probes):
            for name in MCP_PROBES:
                shutil.copy(PROBES / name, tree / 'backend/src/test/java/com/project/backend/mcp' / name)
        if any(not p.startswith('self_check') for p in probes):
            for name in RETRIEVAL_PROBES:
                shutil.copy(PROBES / name, tree / 'backend/src/test/java/com/project/backend/mcp/retrieval' / name)
        env = dict(os.environ, HOMECARE_TEST_PYTHON=str(python), AUDIT_LABEL=label)
        gradle = [str(tree / 'backend' / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))]
        for probe in probes:
            started = time.monotonic()
            command = [*gradle, 'test', '--rerun', '--no-daemon', '--console=plain', '--tests', FILTERS[probe]]
            completed = subprocess.run(command, cwd=tree / 'backend', env=env, capture_output=True, text=True,
                                       encoding='utf-8', errors='replace')
            (work / f'{label}__{probe}.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
            produced = tree / 'backend/build/optimization-audit' / f'{probe}.json'
            entry = {'label': label, 'commit': resolved, 'requested': commit, 'probe': probe,
                     'exit_code': completed.returncode, 'seconds': round(time.monotonic() - started, 1),
                     'result': produced.exists()}
            metadata['targets'].append(entry)
            print(json.dumps(entry, ensure_ascii=False), flush=True)
            if produced.exists():
                (RESULTS / label).mkdir(parents=True, exist_ok=True)
                shutil.copy(produced, RESULTS / label / f'{probe}.json')
    metadata['finished'] = datetime.datetime.now().astimezone().isoformat(timespec='seconds')
    RESULTS.mkdir(exist_ok=True)
    previous = RESULTS / 'run_metadata.json'
    history = json.loads(previous.read_text(encoding='utf-8')) if previous.exists() else []
    history.append(metadata)
    previous.write_text(json.dumps(history, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')


def p95(values):
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def stats(values, scale=1.0):
    if not values:
        return None
    scaled = [v / scale for v in values]
    return {'n': len(scaled), 'median': round(statistics.median(scaled), 2), 'p95': round(p95(scaled), 2),
            'min': round(min(scaled), 2), 'max': round(max(scaled), 2)}


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8')) if Path(path).exists() else None


def distinct(rows, key):
    values = []
    for row in rows:
        value = row[key]
        if value not in values:
            values.append(value)
    return values


def self_check(label):
    requests = load(RESULTS / label / 'self_check_requests.json')
    limited = load(RESULTS / label / 'self_check_rate_limit.json')
    if not requests:
        return None
    summary = {'environment': requests['environment'], 'warmup': requests['warmup'], 'repeat': requests['repeat'],
               'checks': requests['checks'], 'scenarios': {}}
    for scenario in distinct(requests['runs'], 'scenario'):
        runs = [r for r in requests['runs'] if r['scenario'] == scenario and not r['warmup']]
        summary['scenarios'][scenario] = {
            'protocol': distinct(runs, 'protocol'), 'steps_shown': distinct(runs, 'steps_shown'),
            'step_requests': distinct(runs, 'step_requests'), 'step_requests_total': distinct(runs, 'step_requests_total'),
            'rate_limited_path_requests': distinct(runs, 'rate_limited_path_requests'),
            'model_calls': distinct(runs, 'model_calls'), 'final_card': distinct(runs, 'final_card'),
            'final_reply_head': distinct(runs, 'final_reply_head'), 'closed': distinct(runs, 'closed'),
            'check_done': distinct(runs, 'check_done'), 'saved_messages': distinct(runs, 'saved_messages'),
            'step_phase_post_ms': stats([r['step_phase_post_us'] for r in runs], 1000),
            'step_phase_wall_ms': stats([r['step_phase_wall_us'] for r in runs], 1000),
        }
    if limited:
        summary['default_rate_limit'] = [{k: r[k] for k in ('scenario', 'protocol', 'http_429', 'forced_wait_s',
                                                            'rate_limited_path_requests', 'final_status', 'closed')}
                                         | {'step_phase_wall_s': round(r['step_phase_wall_us'] / 1e6, 1)}
                                         for r in limited['runs']]
    return summary


def retrieval_records():
    """기존 제조사 검색 평가 기록(실제 임베딩) 대조"""
    initial = load(ROOT / 'docs/benchmarks/manufacturer_retrieval_initial_20261014.json')
    final = load(ROOT / 'docs/benchmarks/manufacturer_retrieval_20261014.json')
    by_id = {c['id']: c for c in final['cases']}
    changed, same_query = [], 0
    for case in initial['cases']:
        other = by_id.get(case['id'])
        if other and other['query'] == case['query'] and other.get('model') == case.get('model'):
            same_query += 1
        if other and other['passed'] != case['passed']:
            changed.append({'id': case['id'], 'split': case['split'], 'initial': case['passed'], 'final': other['passed']})
    common = [c['id'] for c in initial['cases']]
    first = lambda run, ids: [c['first_query_ms'] for c in run['cases'] if c['id'] in ids and c['mode'] != 'LEXICAL_ONLY']
    return {
        'initial': {'passed': initial['passed'], 'total': initial['total'], 'index': initial['index']},
        'final': {'passed': final['passed'], 'total': final['total'], 'index': final['index']},
        'same_query_and_model_for_initial_cases': same_query, 'pass_changes': changed,
        'final_on_initial_25': sum(1 for c in final['cases'] if c['id'] in common and c['passed']),
        'first_query_ms_initial': stats(first(initial, common)),
        'first_query_ms_final_same_cases': stats(first(final, common)),
        'final_modes': {m: sum(1 for c in final['cases'] if c['mode'] == m) for m in distinct(final['cases'], 'mode')},
        'final_lexical_ids_recorded': {c['id']: c['lexical_ids'] for c in final['cases']},
        'final_hybrid_passed_ids': [c['id'] for c in final['cases'] if c['passed']],
    }


def retrieval(label, records):
    data = load(RESULTS / label / 'retrieval.json')
    if not data:
        return None
    lexical = data['lexical_only']
    hybrid = set(records['final_hybrid_passed_ids'])
    return {
        'data_version': data['data_version'], 'evaluation_version': data['evaluation_version'],
        'rejudged_stored_runs': {k: {kk: vv for kk, vv in v.items() if kk != 'rows'} for k, v in data['rejudged_stored_runs'].items()},
        'lexical_only_passed': lexical['passed'], 'total': lexical['total'],
        'lexical_failed_but_recorded_hybrid_passed': [c['id'] for c in lexical['cases'] if not c['passed'] and c['id'] in hybrid],
        'lexical_passed_ids': [c['id'] for c in lexical['cases'] if c['passed']],
        'lexical_local_time_ms': stats(data['lexical_local_time_us']['samples'], 1000),
        'environment': data['environment'],
    }


def recorded_index_metrics():
    """docs/benchmarks의 실제 임베딩 실행 기록에서 인덱스 준비 시간 수집"""
    rows = []
    for path in sorted((ROOT / 'docs/benchmarks').glob('*.json')):
        data = load(path)
        metrics = isinstance(data, dict) and (data.get('index_metrics') or data.get('index'))
        if metrics:
            rows.append({'file': path.name, 'fingerprint': metrics['fingerprint'][:12], 'cache_hit': metrics['cache_hit'],
                         'initialization_ms': metrics['initialization_ms'],
                         'build_initialization_ms': metrics.get('build_initialization_ms'),
                         'embedding_tokens': metrics['embedding_tokens'], 'unit_count': metrics['unit_count'],
                         'query_embedding_requests': metrics.get('query_embedding_requests')})
    by_fingerprint = {}
    for row in rows:
        group = by_fingerprint.setdefault(row['fingerprint'], {'builds_ms': set(), 'hits_ms': [], 'misses_ms': [], 'tokens': row['embedding_tokens']})
        if row['cache_hit']:
            group['hits_ms'].append(row['initialization_ms'])
            group['builds_ms'].add(row['build_initialization_ms'])
        else:
            group['misses_ms'].append(row['initialization_ms'])
    for group in by_fingerprint.values():
        group['builds_ms'] = sorted(b for b in group['builds_ms'] if b) or group['misses_ms']
        group['hit_stats'] = stats(group['hits_ms'])
        if group['builds_ms'] and group['hits_ms']:
            build = group['builds_ms'][0]
            group['reduction_vs_hit_median'] = round(1 - statistics.median(group['hits_ms']) / build, 4)
    return {'runs': rows, 'by_fingerprint': by_fingerprint}


def index_cache(label):
    data = load(RESULTS / label / 'index_cache.json')
    if not data:
        return None
    builds = [b for b in data['cold_builds'] if not b['warmup']]
    loads = [r for r in data['cache_loads'] if not r['warmup']]
    return {
        'data_version': data['data_version'], 'knowledge_units': data['knowledge_units'], 'dimensions': data['dimensions'],
        'cold_build_local_ms': stats([b['prepare_us'] for b in builds], 1000),
        'cold_build_embedding_requests': distinct(builds, 'embedding_requests'),
        'cold_build_embedded_texts': distinct(builds, 'embedded_texts'),
        'cache_bytes': distinct(builds, 'cache_bytes'),
        'cache_load_ms': stats([r['prepare_us'] for r in loads], 1000),
        'cache_load_constructor_ms': stats([r['constructor_us'] for r in loads], 1000),
        'cache_load_hits': distinct(loads, 'cache_hit'),
        'cache_load_embedding_requests': distinct(loads, 'document_embedding_requests'),
        'questions': data['questions'], 'loaded_results_equal_built_results': data['loaded_results_equal_built_results'],
        'tampered_payload': data['tampered_payload'], 'changed_model_name': data['changed_model_name'],
        'environment': data['environment'],
    }


def summarize(_args):
    records = retrieval_records()
    summary = {
        'generated': datetime.datetime.now().astimezone().isoformat(timespec='seconds'),
        'p95_method': 'nearest-rank: ceil(0.95*n)-th smallest',
        'self_check': {label: self_check(label) for label in ('before_dd95d9a', 'after_dd95d9a', 'current')},
        'retrieval_records': {k: v for k, v in records.items() if k not in ('final_lexical_ids_recorded', 'final_hybrid_passed_ids')},
        'retrieval_probe': {label: retrieval(label, records) for label in ('retrieval_9af5760', 'current')},
        'recorded_index_metrics': recorded_index_metrics(),
        'index_cache_probe': index_cache('current'),
    }
    (RESULTS / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=list) + '\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=1, default=list))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    runner = commands.add_parser('run')
    runner.add_argument('--work', required=True, help='내보낸 트리를 둘 저장소 밖 임시 폴더')
    runner.add_argument('--python', help='mcp_server 요구 패키지가 설치된 Python(기본: mcp_server/.venv)')
    runner.add_argument('--only', nargs='*', help='실행할 라벨만 지정')
    runner.add_argument('--probes', nargs='*', choices=sorted(FILTERS), help='실행할 측정만 지정')
    runner.set_defaults(handler=run)
    commands.add_parser('summarize').set_defaults(handler=summarize)
    parsed = parser.parse_args()
    parsed.handler(parsed)
