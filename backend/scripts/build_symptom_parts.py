#!/usr/bin/env python3
"""Build homecare/symptom-parts.json from the reviewed symptom/error → part-group evidence table and the
observation → part-group table (answers to the questions a customer can check by looking or listening).

Run from the repository root:
  python -I backend/scripts/build_symptom_parts.py
  python -I backend/scripts/build_symptom_parts.py --check   # verify only; no file is written

Every row is checked before anything is written: the category's symptom code, the cost group, the repair case in the
cost package (국내사례_오늘가치_정규화.csv inside the v3 zip), and the official fact with its error code. Guide rows
(GUIDE-nn) point to the local source registry and are not re-fetched here. Observation rows must name a question and a
finding answer of ObservationQuestions.java (mirrored in FINDINGS).
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
OBSERVATIONS = ROOT / '수리비_정규화_과정/관찰_부품_근거_v1.csv'
GUIDES = ROOT / '수리비_정규화_과정/오류코드_공식안내_v1.csv'
CASES_ZIP = ROOT / '수리비_정규화_과정/수리비정규화_패키지_v3.zip'
OUTPUT = 'homecare/symptom-parts.json'
VERSION = 'SYMPTOM-PARTS-V3-20261015'
# Mirrors SymptomCatalog.java; a code outside this list cannot be stored by the consultation state.
SYMPTOMS = {'WASHER': {'VIBRATION', 'DRAIN', 'FILL', 'POWER', 'DOOR'}, 'DRYER': {'DRYING', 'NOISE', 'POWER', 'DOOR', 'ODOR'},
            'REFRIGERATOR': {'COOLING', 'NOISE', 'FROST', 'POWER', 'ODOR'}, 'DISHWASHER': {'WASHING', 'DRYING', 'DRAIN', 'FILL', 'POWER'}}
CASE_CATEGORY = {'세탁기': 'WASHER', '건조기': 'DRYER', '냉장고': 'REFRIGERATOR', '식기세척기': 'DISHWASHER'}
TYPES = {'REPAIR_CASE', 'OFFICIAL_ERROR', 'REPAIR_GUIDE', 'OFFICIAL_GUIDE'}
# Mirrors the finding answers of ObservationQuestions.java; neutral answers (normal, unknown) never link to a part.
FINDINGS = {('REFRIGERATOR', 'COOLING'): {'DISPLAY': {'FLASHING', 'OFF'}, 'SOUND': {'NO'}, 'FROST': {'BACK_WALL', 'DOOR'}}}


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


def guide_refs() -> set:
    """(category, "source:code") of the official error guidance: a manual id, a support page id or a fact id."""
    refs = set()
    for row in csv.DictReader(io.StringIO(GUIDES.read_bytes().decode('utf-8-sig'))):
        sources = [] if row['설명서'] == '품목 공통' else row['설명서'].split(';')
        sources.append(row['출처'].rsplit('/', 1)[-1].split('#')[0])
        codes = [row['대표 코드']] if row['대표 코드'] else [c for c in row['코드'].split(';') if c]
        refs.update((row['품목'], f'{source}:{c}') for source in sources for c in codes)
    return refs


def build() -> tuple[list, bytes, bytes]:
    raw, observed = EVIDENCE.read_bytes(), OBSERVATIONS.read_bytes()
    parts = {p['id']: p for p in json.loads((RESOURCES / 'homecare/parts.json').read_bytes())}
    facts = {d['id']: d for d in json.loads((RESOURCES / 'homecare/knowledge.json').read_bytes())}
    cases = repair_cases()
    guides = guide_refs()
    links, errors = {}, []

    def evidence(where, category, kind, value, part, kind_of_evidence, ref):
        if category not in SYMPTOMS: errors.append(f'{where}: unknown category {category}')
        if part not in parts or parts[part]['category'] != category: errors.append(f'{where}: {part} is not a {category} cost group')
        if kind_of_evidence not in TYPES: errors.append(f'{where}: unknown evidence type {kind_of_evidence}')
        if kind_of_evidence == 'REPAIR_CASE' and cases.get(ref) != category: errors.append(f'{where}: case {ref} is not a {category} case')
        if kind_of_evidence == 'OFFICIAL_ERROR':
            fact = facts.get(ref)
            if not fact or fact['category'] != category: errors.append(f'{where}: fact {ref} is not a {category} fact')
            elif kind == 'ERROR' and code(value) not in {code(c) for raw_code in fact.get('errorCodes', []) for c in re.split(r'[;,]', raw_code)}:
                errors.append(f'{where}: {value} is not an error code of {ref}')
        if kind_of_evidence == 'REPAIR_GUIDE' and not re.fullmatch(r'GUIDE-\d{2}', ref): errors.append(f'{where}: guide id must be GUIDE-nn')
        if kind_of_evidence == 'OFFICIAL_GUIDE' and ((category, ref) not in guides or kind != 'ERROR' or ref.rsplit(':', 1)[-1] != code(value)):
            errors.append(f'{where}: {ref} is not official guidance for {category} {value}')

    def add(key, fields, kind_of_evidence, ref, note):
        link = links.setdefault(key, {**fields, 'partName': parts.get(fields['partId'], {}).get('name'), 'evidence': []})
        link['evidence'].append({'type': kind_of_evidence, 'ref': ref, 'note': note})

    for number, row in enumerate(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))), start=2):
        category, kind, value, part, kind_of_evidence, ref = (row[k].strip() for k in ('품목', '연결', '코드', '부품코드', '근거 종류', '근거 ID'))
        where = f'{EVIDENCE.name} line {number}'
        if kind == 'SYMPTOM' and value not in SYMPTOMS.get(category, set()): errors.append(f'{where}: {value} is not a {category} symptom')
        if kind not in ('SYMPTOM', 'ERROR'): errors.append(f'{where}: link must be SYMPTOM or ERROR')
        evidence(where, category, kind, value, part, kind_of_evidence, ref)
        key = (category, kind, code(value) if kind == 'ERROR' else value, part)
        add(key, {'category': category, 'kind': kind, 'code': key[2], 'partId': part}, kind_of_evidence, ref, row['근거 요지'].strip())
    for number, row in enumerate(csv.DictReader(io.StringIO(observed.decode('utf-8-sig'))), start=2):
        category, symptom, question, answer, part, kind_of_evidence, ref = (row[k].strip() for k in ('품목', '증상', '질문', '답', '부품코드', '근거 종류', '근거 ID'))
        where = f'{OBSERVATIONS.name} line {number}'
        if answer not in FINDINGS.get((category, symptom), {}).get(question, set()): errors.append(f'{where}: {question}={answer} is not a {category} {symptom} finding')
        evidence(where, category, 'OBSERVATION', symptom, part, kind_of_evidence, ref)
        key = (category, 'OBSERVATION', symptom, part, question, answer)
        add(key, {'category': category, 'kind': 'OBSERVATION', 'code': symptom, 'question': question, 'answer': answer, 'partId': part},
            kind_of_evidence, ref, row['근거 요지'].strip())
    if errors: raise SystemExit('\n'.join(errors))
    for link in links.values():
        types = [e['type'] for e in link['evidence']]
        link.update(repairCases=types.count('REPAIR_CASE'), officialFacts=types.count('OFFICIAL_ERROR'), guides=types.count('REPAIR_GUIDE'))
    order = lambda l: (l['category'], l['kind'], l['code'], l.get('question', ''), l.get('answer', ''), l['partId'])
    return sorted(links.values(), key=order), raw, observed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    links, raw, observed = build()
    output = (json.dumps(links, ensure_ascii=False, indent=1) + '\n').encode('utf-8')
    manifest_path = RESOURCES / 'homecare/source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    section = {'version': VERSION, 'evidenceFile': EVIDENCE.relative_to(ROOT).as_posix(),
               'evidenceSha256': hashlib.sha256(raw).hexdigest(), 'observationFile': OBSERVATIONS.relative_to(ROOT).as_posix(),
               'observationSha256': hashlib.sha256(observed).hexdigest(), 'guideTable': GUIDES.relative_to(ROOT).as_posix(),
               'guideTableSha256': hashlib.sha256(GUIDES.read_bytes()).hexdigest(), 'links': len(links)}
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
