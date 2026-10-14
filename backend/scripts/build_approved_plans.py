"""Build only the explicitly reviewed UE/bundled-laundry branch; never bulk approve model candidates.

python scripts/build_approved_plans.py --b30 <B30 directory> --manual-zip <official MFL71831233 ZIP>
The ZIP must match manuals_B30.json. No network, original KB edits or cost rebuilds.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def sha(blob):
    return hashlib.sha256(blob).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--b30', type=Path, required=True)
    parser.add_argument('--manual-zip', type=Path, required=True)
    args = parser.parse_args()
    checks = json.loads((args.b30 / 'checksums_B30.json').read_text(encoding='utf-8'))['files']
    inputs = {}
    for name in ['knowledge_effective_B30.jsonl', 'manuals_B30.json', 'model_code_lookup_B30.json', 'review_links_B30.json']:
        blob = (args.b30 / name).read_bytes()
        if sha(blob) != checks[name]:
            raise ValueError(f'B30 checksum mismatch: {name}')
        inputs[name] = sha(blob)
    rows = [json.loads(line) for line in (args.b30 / 'knowledge_effective_B30.jsonl').read_text(encoding='utf-8').splitlines()]
    row = next(r for r in rows if r['id'] == 'T2-065')
    if row['original_record_sha256'] != 'ae71963120069770989afb6e86b4b7848c5d2925694317a25afc6a066ceb011c':
        raise ValueError('Reviewed source record changed; a new action review is required')
    if row['current_QA_review'] is not None or row['latest_applicability_update'] is not None or row['procedure_supplements']:
        raise ValueError('Review overlays changed; re-review the action before rebuilding')
    record = row['record']
    branch = next(b for b in record['cause_branches'] if b['id'] == 'TR15WV5-T2-065-bundled')
    if branch['condition'] != '세탁물이 뭉침' or branch['error_code'] != 'UE' or not record['model_application_verified']:
        raise ValueError('Reviewed branch changed')
    manuals = json.loads((args.b30 / 'manuals_B30.json').read_text(encoding='utf-8'))
    manual = next(m for m in manuals if m['manual_id'] == 'MFL71831233')
    if manual['sha256'] != 'c3c9c3b31d2e79da40a7e029a97f4b8f820a5b1b38c24bb71ad46030f51e28e3':
        raise ValueError('Reviewed official manual changed; review text and diagrams again')
    if sha(args.manual_zip.read_bytes()) != manual['sha256']:
        raise ValueError('Official manual ZIP checksum mismatch')
    models = json.loads((args.b30 / 'model_code_lookup_B30.json').read_text(encoding='utf-8'))
    if 'MFL71831233' not in models['TR15WV5']['manual_ids'] or 'TR15WV5' not in manual['models']:
        raise ValueError('Exact model/manual mismatch')
    topic_checks = {
        'topic_44': ['세탁물이 뭉쳐', 'UE', '동작/일시정지'],
        'topic_9': ['인형', '방수성 천', '완전히 멈춘'],
        'topic_16': ['멈출 때까지', '손을'],
        'topic_20': ['동작/일시정지', '제품이 정지한'],
        'topic_23': ['3초 이상', '탈수단독'],
    }
    sources = []
    with ZipFile(args.manual_zip) as archive:
        for topic, terms in topic_checks.items():
            name = f'ko-kr/topics/{topic}.html'
            blob = archive.read(name)
            if any(term not in blob.decode('utf-8') for term in terms):
                raise ValueError(f'Reviewed manual changed: {topic}')
            sources.append({'path': name, 'sha256': sha(blob), 'source': manual['view_url'] + '#' + topic})
        diagram = 'ko-kr/res/fig/t_00148364_r3/control_panel_vbt_ko_01.svg'
        sources.append({'path': diagram, 'sha256': sha(archive.read(diagram)), 'source': manual['view_url'] + '#topic_20',
                        'review': '2026-10-14 원문 SVG 화면 확인: 동작/일시정지, 헹굼·탈수단독 표시. 실물 일치 여부는 매 상담에서 확인.'})
    source = manual['view_url']
    followup = '추가 조작을 반복하지 말고 LG전자 공식 상담으로 제품 상태를 확인해 주세요. 원문의 A/S 필요 여부 공란은 안전함이나 서비스 불필요를 뜻하지 않습니다.'
    questions = [
        ('MODEL_LABEL', '제품 라벨의 전체 모델명이 TR15WV5와 정확히 일치하나요?', 'topic_20'),
        ('UE_OBSERVED', '현재 표시창에서 UE 오류를 직접 확인했나요?', 'topic_44'),
        ('BUNDLED_LAUNDRY', '세탁 가능한 일반 의류 여러 장이 뭉친 상태인가요? 이불·인형·방수성 천·기능성 의류·내용물이 든 베개/쿠션·한 장만 넣은 니트·세탁망 단독·용량 초과는 이 안내에서 제외합니다.', 'topic_9'),
        ('SAFE_STATE', '연기·타는 냄새·불꽃·누수·이상한 소리·감전 징후가 없고, 손이 마른 상태이며 주변 어린이·반려동물이 안전하게 떨어져 있나요?', 'topic_9'),
        ('STOPPED_ACCESS', '세탁통이 완전히 멈춰 있고 문 유리가 온전하며, 문을 억지로 열거나 잠금장치를 해제하지 않아도 접근 가능한 상태인가요? 회전 중이거나 상태를 모르겠으면 아니요 또는 모르겠어요로 답해 주세요.', 'topic_16'),
        ('CONTROL_DIAGRAM', '아래 원문 기능조작부 그림을 직접 확인했나요? 실물에 동작/일시정지 버튼과 헹굼 버튼의 탈수단독 표시가 일치하고 버튼 잠금이 해제되어 있나요?', 'topic_20'),
    ]
    plan = {
        'id': 'TR15WV5-UE-BUNDLED', 'revision': '1', 'category': 'WASHER', 'modelCodes': ['TR15WV5'],
        'errorCode': 'UE', 'actionApproved': True, 'source': source + '#topic_44', 'baseDate': '2026-10-14',
        'review': {'status': 'APPLICATION_REVIEWED_CONDITIONAL', 'manufacturerApproval': False,
                   'sourceRecordId': row['id'], 'sourceRecordSha256': row['original_record_sha256'],
                   'branchId': branch['id'], 'manualId': manual['manual_id'], 'manualZipSha256': manual['sha256'],
                   'b30Checksums': inputs, 'sources': sources,
                   'scope': 'HomeCare 실행 경로 검토. OEM 승인·실물 시험 아님. 원문/B30 후보 정책은 변경하지 않음.',
                   'excluded': ['다른 UE 분기', 'TR15MV5 및 다른 모델', '진동 조치', '수평 조절', '분해', '금지 세탁물']},
        'preconditions': [{'id': id, 'text': text, 'source': source + '#' + topic, 'failure_guidance': followup} for id, text, topic in questions],
        'steps': [
            {'step_code': 'TR15WV5-UE-SPREAD', 'text': '동작/일시정지 버튼으로 UE 표시를 해제하세요. 세탁통이 완전히 멈춘 것을 다시 확인한 뒤 문을 열고 뭉친 세탁물을 고르게 펴 주세요.',
             'caution': '세탁통이 조금이라도 회전하면 손을 넣지 마세요. 문을 억지로 열지 마세요. 문을 열어도 멈추지 않거나 위험 징후가 생기면 진행을 중지하고 공식 서비스에 문의하세요.', 'source': source + '#topic_44'},
            {'step_code': 'TR15WV5-UE-SPIN', 'text': '세탁기 문을 닫고 헹굼 버튼을 3초 이상 눌러 탈수단독을 선택한 뒤 동작/일시정지 버튼으로 작동하세요.',
             'caution': '앞 단계에서 세탁물을 고르게 편 경우에만 진행하세요. 조작부나 현재 상태가 달라졌으면 진행하지 마세요. UE가 반복되면 추가 조작 대신 공식 서비스에 문의하세요.', 'source': source + '#topic_23'},
        ], 'followup': followup,
    }
    root = Path(__file__).resolve().parents[1] / 'src/main/resources/homecare'
    original_hash = sha((root / 'knowledge.json').read_bytes())
    target = root / 'approved-plans.json'
    target.write_text(json.dumps([plan], ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    manifest_path = root / 'source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    manifest['version'] = 'B30-COST-V3-20261014-R5'
    manifest['files']['homecare/approved-plans.json'] = sha(target.read_bytes())
    manifest['approvedPlanCount'] = 1
    manifest['actionReview'] = {'scope': 'TR15WV5 / UE / bundled laundry only', 'reviewDate': '2026-10-14',
                                'manufacturerApproval': False, 'bulkModelApprovalAllowed': False}
    manifest['unavailable'] = [item.replace('B30 원본(knowledge_effective_B30.jsonl, checksums_B30.json) 미확보로 제공하지 않음',
                                          'B30 원본 확보, 제조일 기준 값·충돌 검토 및 구조화 미완료') for item in manifest['unavailable']]
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    if sha((root / 'knowledge.json').read_bytes()) != original_hash:
        raise ValueError('Original knowledge must remain unchanged')
    print('Registered 1 conditional application-reviewed plan; original knowledge unchanged.')


if __name__ == '__main__':
    main()
