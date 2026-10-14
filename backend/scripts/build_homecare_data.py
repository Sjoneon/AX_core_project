#!/usr/bin/env python3
"""Rebuild parts.json from the v3 cost CSV and refresh source-manifest.json file hashes.

Run from the repository root with the untouched original CSV (it is only read):
  python -I backend/scripts/build_homecare_data.py --cost-csv <path>/한국기준_예상수리비_표_v3.csv

Spring verifies every hash in the manifest "files" map at startup and refuses to start on a mismatch.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / 'src/main/resources'
VERSION = 'B30-COST-V3-20261013-R3'
HEADER = ['제품군', '부품코드', '부품', '작업 등급', '부품비 추정(중앙)', '기술료(등급 중앙)', '출장비(평일)',
          '해외 기반 총액(v2)', '국내 같은 부품 사례 수', '국내 같은 부품 중앙값', '국내 사례 가중치',
          '예상 총액 중앙', '80% 범위 하한', '80% 범위 상한', '야간·휴일 가산', '6~8월 가산(평일/야간)', '신뢰도']
CATEGORIES = {'세탁기': 'WASHER', '건조기': 'DRYER', '냉장고': 'REFRIGERATOR', '식기세척기': 'DISHWASHER'}
# The cost table has no core-part column. These groups match the separately warranted core parts
# (DD motor stator/rotor, compressor) exactly as in the previously reviewed conversion.
CORE_PART_CODES = {'W05', 'W06', 'R01'}
# Every file Spring reads. The manifest itself is the trust root packaged with the application.
VERIFIED_FILES = ['homecare/knowledge.json', 'homecare/models.json', 'homecare/parts.json', 'homecare/centers.json',
                  'homecare/route.json', 'homecare/approved-plans.json', 'homecare/repair-cases.json',
                  'warranty/policies.json']


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def whole(value: str, column: str) -> int:
    if not value.isdecimal():
        raise SystemExit(f'{column}: expected a non-negative whole number, got {value!r}')
    return int(value)


def read_parts(raw: bytes):
    rows = list(csv.reader(io.StringIO(raw.decode('utf-8-sig'))))
    if rows[0] != HEADER:
        raise SystemExit('Unexpected cost CSV header; review the source before converting.')
    parts, excluded = [], []
    for values in rows[1:]:
        row = dict(zip(HEADER, values, strict=True))
        code, grade = row['부품코드'], row['신뢰도']
        if grade.startswith('제외'):
            excluded.append({'part_code': code, 'part_name': row['부품'], 'reason': grade})
            continue
        if grade not in ('낮음', '중간', '높음'):
            raise SystemExit(f'{code}: unknown confidence grade {grade!r}')
        summer = row['6~8월 가산(평일/야간)'].split('/')
        if len(summer) != 2:
            raise SystemExit(f'{code}: summer surcharge must be "weekday/night-holiday"')
        low, median, high = (whole(row[c], c) for c in ('80% 범위 하한', '예상 총액 중앙', '80% 범위 상한'))
        if not low <= median <= high:
            raise SystemExit(f'{code}: range does not contain the median')
        parts.append({
            'id': code, 'category': CATEGORIES[row['제품군']], 'name': row['부품'], 'workGrade': row['작업 등급'],
            'partMedian': whole(row['부품비 추정(중앙)'], '부품비'), 'laborMedian': whole(row['기술료(등급 중앙)'], '기술료'),
            'baseVisit': whole(row['출장비(평일)'], '출장비'),
            'empiricalMedian': median, 'empiricalMin': low, 'empiricalMax': high,
            'calibrationCases': whole(row['국내 같은 부품 사례 수'], '사례 수'), 'confidenceGrade': grade,
            'nightHolidaySurcharge': whole(row['야간·휴일 가산'], '야간·휴일 가산'),
            'summerWeekdaySurcharge': whole(summer[0], '6~8월 평일 가산'),
            'summerNightHolidaySurcharge': whole(summer[1], '6~8월 야간·휴일 가산'),
            'isCore': code in CORE_PART_CODES, 'groupEstimateOnly': True, 'baseDate': '2026-10-08',
            'source': '첨부 한국기준_예상수리비_표_v3.csv; 후기 보정 총액은 구성 합계와 별도'})
    return parts, excluded


def write_json(path: Path, value) -> None:
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cost-csv', required=True, type=Path)
    args = parser.parse_args()
    raw = args.cost_csv.read_bytes()
    parts, excluded = read_parts(raw)
    write_json(RESOURCES / 'homecare/parts.json', parts)

    manifest_path = RESOURCES / 'homecare/source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    manifest['version'] = VERSION
    manifest['costGroups'] = len(parts)
    manifest['costSha256'] = sha256(raw)
    manifest['costSource'] = {
        'file': args.cost_csv.name, 'sha256': sha256(raw), 'rows': len(parts) + len(excluded), 'excluded': excluded,
        'basis': '평일 출장비 28,000원 포함 유상 수리 예상 총액(중앙·80% 범위)',
        'surcharge_rule': '평일 0원, 야간·휴일 = 야간·휴일 가산, 6~8월 평일 = 6~8월 가산 앞 값, '
                          '6~8월 야간·휴일 = 6~8월 가산 뒤 값(야간·휴일 가산과 중복 합산하지 않음). '
                          '출장비 고정 가산이므로 중앙·하한·상한에 같은 금액을 더한다.',
        'validation': {'cases': 20, 'median_absolute_error_percent': 11.2, 'range_hit_percent': 80,
                       'note': '등급·축소 강도·범위 폭을 같은 20건으로 골랐으므로 새 사례에서는 오차가 더 클 수 있음'}}
    unavailable = [item for item in manifest.get('unavailable', []) if not item.startswith('부품보유기간')]
    unavailable.append('부품보유기간 구조화 값: B30 원본(knowledge_effective_B30.jsonl, checksums_B30.json) 미확보로 제공하지 않음')
    manifest['unavailable'] = unavailable
    manifest['files'] = {name: sha256((RESOURCES / name).read_bytes()) for name in VERIFIED_FILES}
    write_json(manifest_path, manifest)
    print(f'{VERSION}: {len(parts)} parts, excluded {[e["part_code"] for e in excluded]}, '
          f'{len(VERIFIED_FILES)} verified files')


if __name__ == '__main__':
    main()
