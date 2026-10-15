#!/usr/bin/env python3
"""Build homecare/symptom-parts.json from the reviewed symptom/error → part-group evidence table.

Run from the repository root:
  python -I backend/scripts/build_symptom_parts.py
  python -I backend/scripts/build_symptom_parts.py --check   # verify only; no file is written

Every row is checked before anything is written: the category's symptom code, the cost group, the repair case in the
cost package (국내사례_오늘가치_정규화.csv inside the v3 zip), and the official fact with its error code. Guide rows
(GUIDE-nn) point to the local source registry and are not re-fetched here.
"""
import argparse
import csv
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'backend/src/main/resources'
EVIDENCE = ROOT / '수리비_정규화_과정/증상_부품_근거_v1.csv'
CASES_ZIP = ROOT / '수리비_정규화_과정/수리비정규화_패키지_v3.zip'
OUTPUT = 'homecare/symptom-parts.json'
VERSION = 'SYMPTOM-PARTS-V1-20261015'
# Mirrors SymptomCatalog.java; a code outside this list cannot be stored by the consultation state.
SYMPTOMS = {'WASHER': {'VIBRATION', 'DRAIN', 'FILL', 'POWER', 'DOOR'}, 'DRYER': {'DRYING', 'NOISE', 'POWER', 'DOOR', 'ODOR'},
            'REFRIGERATOR': {'COOLING', 'NOISE', 'FROST', 'POWER', 'ODOR'}, 'DISHWASHER': {'WASHING', 'DRYING', 'DRAIN', 'FILL', 'POWER'}}
CASE_CATEGORY = {'세탁기': 'WASHER', '건조기': 'DRYER', '냉장고': 'REFRIGERATOR', '식기세척기': 'DISHWASHER'}
TYPES = {'REPAIR_CASE', 'OFFICIAL_ERROR', 'REPAIR_GUIDE'}


def code(value: str) -> str:
    return re.sub(r'\s+', '', value).upper()


def repair_cases() -> dict:
    with zipfile.ZipFile(CASES_ZIP) as archive:
        for info in archive.infolist():
            raw = info.filename.encode('cp437') if not info.flag_bits & 0x800 else info.filename.encode()
            name = raw.decode('utf-8', 'replace')
            if name.endswith('국내사례_오늘가치_정규화.csv'):
                rows = csv.DictReader(io.StringIO(archive.read(info).decode('utf-8-sig')))
                return {f"{row['브랜드']}/{row['ID']}": CASE_CATEGORY[row['제품군']] for row in rows}
    raise SystemExit('국내사례_오늘가치_정규화.csv is missing from the cost package zip')


def build() -> tuple[list, bytes]:
    raw = EVIDENCE.read_bytes()
    parts = {p['id']: p for p in json.loads((RESOURCES / 'homecare/parts.json').read_bytes())}
    facts = {d['id']: d for d in json.loads((RESOURCES / 'homecare/knowledge.json').read_bytes())}
    cases = repair_cases()
    links, errors = {}, []
    for number, row in enumerate(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))), start=2):
        category, kind, value, part, kind_of_evidence, ref = (row[k].strip() for k in ('품목', '연결', '코드', '부품코드', '근거 종류', '근거 ID'))
        where = f'line {number}'
        if category not in SYMPTOMS: errors.append(f'{where}: unknown category {category}')
        if kind == 'SYMPTOM' and value not in SYMPTOMS.get(category, set()): errors.append(f'{where}: {value} is not a {category} symptom')
        if kind not in ('SYMPTOM', 'ERROR'): errors.append(f'{where}: link must be SYMPTOM or ERROR')
        if part not in parts or parts[part]['category'] != category: errors.append(f'{where}: {part} is not a {category} cost group')
        if kind_of_evidence not in TYPES: errors.append(f'{where}: unknown evidence type {kind_of_evidence}')
        if kind_of_evidence == 'REPAIR_CASE' and cases.get(ref) != category: errors.append(f'{where}: case {ref} is not a {category} case')
        if kind_of_evidence == 'OFFICIAL_ERROR':
            fact = facts.get(ref)
            if not fact or fact['category'] != category: errors.append(f'{where}: fact {ref} is not a {category} fact')
            elif kind == 'ERROR' and code(value) not in {code(c) for raw_code in fact.get('errorCodes', []) for c in re.split(r'[;,]', raw_code)}:
                errors.append(f'{where}: {value} is not an error code of {ref}')
        if kind_of_evidence == 'REPAIR_GUIDE' and not re.fullmatch(r'GUIDE-\d{2}', ref): errors.append(f'{where}: guide id must be GUIDE-nn')
        key = (category, kind, code(value) if kind == 'ERROR' else value, part)
        link = links.setdefault(key, {'category': category, 'kind': kind, 'code': key[2], 'partId': part,
                                      'partName': parts.get(part, {}).get('name'), 'evidence': []})
        link['evidence'].append({'type': kind_of_evidence, 'ref': ref, 'note': row['근거 요지'].strip()})
    if errors: raise SystemExit('\n'.join(errors))
    for link in links.values():
        types = [e['type'] for e in link['evidence']]
        link.update(repairCases=types.count('REPAIR_CASE'), officialFacts=types.count('OFFICIAL_ERROR'), guides=types.count('REPAIR_GUIDE'))
    return sorted(links.values(), key=lambda l: (l['category'], l['kind'], l['code'], l['partId'])), raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    links, raw = build()
    output = (json.dumps(links, ensure_ascii=False, indent=1) + '\n').encode('utf-8')
    manifest_path = RESOURCES / 'homecare/source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    section = {'version': VERSION, 'evidenceFile': EVIDENCE.relative_to(ROOT).as_posix(),
               'evidenceSha256': hashlib.sha256(raw).hexdigest(), 'links': len(links)}
    if parser.parse_args().check:
        current = (RESOURCES / OUTPUT).read_bytes() if (RESOURCES / OUTPUT).exists() else b''
        ok = current == output and manifest.get('symptomParts') == section and manifest['files'].get(OUTPUT) == hashlib.sha256(output).hexdigest()
        print(f'{VERSION}: {len(links)} links; check={ok}')
        sys.exit(0 if ok else 1)
    (RESOURCES / OUTPUT).write_bytes(output)
    manifest['symptomParts'] = section
    manifest['files'][OUTPUT] = hashlib.sha256(output).hexdigest()
    manifest_path.write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    print(f'{VERSION}: {len(links)} links from {sum(len(l["evidence"]) for l in links)} evidence rows')


if __name__ == '__main__':
    main()
