#!/usr/bin/env python3
"""Build homecare/error-guides.json from the reviewed official error guidance table.

Run from the repository root:
  python -I backend/scripts/build_error_guides.py
  python -I backend/scripts/build_error_guides.py --check   # verify only; no file is written

Rows of one entry share 품목, 설명서 and 표시. Every manual must be a manual of a model of that category in models.json,
a manual source must point into one of the entry's manuals, and a fact source must be an official fact of the category.
An item whose sentences only send the customer to LG service is marked as contact-only: it closes the self-check
instead of becoming a step.
"""
import argparse
import csv
import hashlib
import io
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'backend/src/main/resources'
TABLE = ROOT / '수리비_정규화_과정/오류코드_공식안내_v1.csv'
OUTPUT = 'homecare/error-guides.json'
VERSION = 'ERROR-GUIDES-V1-20261015'
PREFIX = {'WASHER': 'DFZ', 'DRYER': 'DFZ', 'DISHWASHER': 'CDZ'}
SERVICE = re.compile(r'(서비스\s*센터|LG센터|1544-7777|LG\s*문의|고객\s*지원\s*센터|챗봇 상담|출장 서비스)')
SENTENCE = re.compile(r'(?<=[.?])(?!\d)')
CODE = re.compile(r'[A-Z0-9]{1,6}|[가-힣]{2,12}')


def contact_only(action: str) -> bool:
    sentences = [s.strip() for s in SENTENCE.split(action) if s.strip()]
    return all(SERVICE.search(s) for s in sentences)


def entries(raw: bytes) -> list:
    models = json.loads((RESOURCES / 'homecare/models.json').read_bytes())
    facts = {d['id']: d for d in json.loads((RESOURCES / 'homecare/knowledge.json').read_bytes())}
    manuals = {}
    for model in models.values():
        for manual in model.get('manual_ids') or []:
            manuals.setdefault(manual, set()).add('REFRIGERATOR' if model['category'] == 'KIMCHI' else model['category'])
    grouped, errors = {}, []
    for number, row in enumerate(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))), start=2):
        where = f'line {number}'
        category, scope, shown = row['품목'], row['설명서'], row['표시']
        codes = [c for c in row['코드'].split(';') if c]
        if not codes or any(not CODE.fullmatch(c) for c in codes): errors.append(f'{where}: invalid codes {row["코드"]}')
        listed = [] if scope == '품목 공통' else scope.split(';')
        if scope == '품목 공통' and category != 'REFRIGERATOR': errors.append(f'{where}: only refrigerator guidance is category-wide')
        for manual in listed:
            if category not in manuals.get(manual, set()): errors.append(f'{where}: {manual} is not a {category} manual of a registered model')
        source = row['출처']
        if re.fullmatch(r'MFL\d+#topic_\d+', source):
            if source.split('#')[0] not in listed: errors.append(f'{where}: source {source} is not one of the entry manuals')
            manual, topic = source.split('#')
            url = f'https://gscs-manual.lge.com/{PREFIX.get(category, "CNZ")}/{manual}/ko-kr/main.html#{topic}'
        elif re.fullmatch(r'https://www\.lge\.co\.kr/support/solutions-\d+', source):
            url = source
        elif re.fullmatch(r'T2-\d{3}', source):
            fact = facts.get(source)
            if not fact or fact['category'] != category or fact.get('recordType') != 'T2_error_codes': errors.append(f'{where}: {source} is not a {category} error fact')
            url = json.loads(fact['text'])['record']['content']['source_url'] if fact else ''
        else:
            errors.append(f'{where}: unknown source {source}')
            url = ''
        if not row['조치'].strip(): errors.append(f'{where}: empty action')
        key = (category, scope, shown)
        entry = grouped.setdefault(key, {'category': category, 'scope': 'CATEGORY' if scope == '품목 공통' else 'MANUAL', 'manuals': listed,
                                         'shown': shown, 'codes': codes, 'canonical': row['대표 코드'] or None, 'meaning': row['의미'],
                                         'cause': row['원인'] or None, 'source': {'ref': source, 'url': url}, 'items': []})
        if (entry['codes'], entry['canonical'] or '', entry['meaning'], entry['cause'] or '') != (codes, row['대표 코드'], row['의미'], row['원인']):
            errors.append(f'{where}: rows of {shown} disagree on code, meaning or cause')
        if int(row['순서']) != len(entry['items']) + 1: errors.append(f'{where}: item order of {shown} is not consecutive')
        entry['items'].append({'check': row['확인'] or None, 'action': row['조치'], 'contact_only': contact_only(row['조치'])})
    # A label's canonical code must be a code some entry of the category shows, so the label and the code share links.
    shownCodes = {(e['category'], c) for e in grouped.values() for c in e['codes']}
    for e in grouped.values():
        if e['canonical'] and (e['category'], e['canonical']) not in shownCodes: errors.append(f'{e["shown"]}: canonical {e["canonical"]} is not shown by any {e["category"]} entry')
    # One displayed code stands for one canonical code within a category.
    mapped = {}
    for e in grouped.values():
        for c in e['codes'] if e['canonical'] else []:
            if mapped.setdefault((e['category'], c), e['canonical']) != e['canonical']: errors.append(f'{e["category"]} {c} maps to two canonical codes')
    if errors: raise SystemExit('\n'.join(errors))
    result = []
    for e in grouped.values():
        e['id'] = hashlib.sha256(f"{e['category']}|{';'.join(e['manuals']) or 'CATEGORY'}|{e['shown']}".encode()).hexdigest()[:12]
        result.append(e)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    raw = TABLE.read_bytes()
    built = entries(raw)
    output = (json.dumps(built, ensure_ascii=False, indent=1) + '\n').encode('utf-8')
    manifest_path = RESOURCES / 'homecare/source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    section = {'version': VERSION, 'table': TABLE.relative_to(ROOT).as_posix(), 'tableSha256': hashlib.sha256(raw).hexdigest(),
               'entries': len(built), 'items': sum(len(e['items']) for e in built)}
    if parser.parse_args().check:
        current = (RESOURCES / OUTPUT).read_bytes() if (RESOURCES / OUTPUT).exists() else b''
        ok = current == output and manifest.get('errorGuides') == section and manifest['files'].get(OUTPUT) == hashlib.sha256(output).hexdigest()
        print(f'{VERSION}: {len(built)} entries; check={ok}')
        sys.exit(0 if ok else 1)
    (RESOURCES / OUTPUT).write_bytes(output)
    manifest['errorGuides'] = section
    manifest['files'][OUTPUT] = hashlib.sha256(output).hexdigest()
    manifest_path.write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    print(f'{VERSION}: {len(built)} entries, {section["items"]} items')


if __name__ == '__main__':
    main()
