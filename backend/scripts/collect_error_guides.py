#!/usr/bin/env python3
"""Collect LG's official error-message guidance into 수리비_정규화_과정/오류코드_공식안내_v1.csv.

Run from the repository root (network access; downloads are cached in .homecare-cache/ and recorded in the local
source registry so a page is not collected twice):
  python backend/scripts/collect_error_guides.py

Washer, dryer and dishwasher: the "에러 메시지" topic of every manual in models.json, read from the manual's search
index (res/js/indexer.js, which holds each topic's full text). A manual's table applies to every model it covers.
Refrigerators: the manuals have no code table, so the code-specific LG support pages and the reviewed official
facts (T2-xxx) are used.

Each manual entry is split into "check? action" items in LG's own words. The labels, their equivalent codes and the
meanings below were reviewed against the manuals: a text label ("배수 안됨") and a code ("OE") share an entry when the
manuals give them the same guidance word for word.
"""
import csv
import hashlib
import io
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from source_registry import SourceRegistry  # noqa: E402

OUTPUT = ROOT / '수리비_정규화_과정/오류코드_공식안내_v1.csv'
CACHE = ROOT / '.homecare-cache/error-guides'
MODELS = ROOT / 'backend/src/main/resources/homecare/models.json'
PREFIX = {'WASHER': 'DFZ', 'DRYER': 'DFZ', 'DISHWASHER': 'CDZ'}
HEADER = ['품목', '설명서', '표시', '코드', '대표 코드', '의미', '원인', '순서', '확인', '조치', '출처']

# display label in the manual -> (codes, canonical code or '' when every code stands for itself, meaning)
W = {
    '탈수 안됨': (['탈수안됨'], 'UE', '탈수 안됨(세탁물 치우침)'), 'UE': (['UE'], 'UE', '탈수 안됨(세탁물 치우침)'),
    '급수 안됨': (['급수안됨'], 'IE', '급수 안됨'), 'IE': (['IE'], 'IE', '급수 안됨'),
    '배수 안됨': (['배수안됨'], 'OE', '배수 안됨'), 'OE': (['OE'], 'OE', '배수 안됨'),
    '문 안닫힘 / 문 안잠김': (['문안닫힘', '문안잠김'], 'DE', '문 닫힘·잠김 이상'),
    '문 안닫힘 / 문 안잠김 / 문 열림': (['문안닫힘', '문안잠김', '문열림'], 'DE', '문 닫힘·잠김 이상'),
    'dE1, dEz': (['DE1', 'DE2'], 'DE', '문 닫힘·잠김 이상'), 'dE, dE1, dEz': (['DE', 'DE1', 'DE2'], 'DE', '문 닫힘·잠김 이상'),
    'dE': (['DE'], 'DE', '문 닫힘 이상'), 'dE4': (['DE4'], 'DE4', '문 잠김 인식 이상'), 'dL': (['DL'], 'DL', '문 잠김 이상'),
    '물높이 높음': (['물높이높음'], 'FE', '물높이 높음(급수 이상)'), 'FE': (['FE'], 'FE', '물높이 높음(급수 이상)'),
    '물높이 감지 안됨': (['물높이감지안됨'], 'PE', '물높이 감지 안됨(수위 센서 이상)'), 'PE': (['PE'], 'PE', '물높이 감지 안됨(수위 센서 이상)'),
    '모터 회전 이상': (['모터회전이상'], 'LE', '모터 회전 이상'), 'LE': (['LE'], 'LE', '모터 회전 이상'),
    'LE~1~LE~1': (['LE'], 'LE', '모터 회전 이상'),
    '온도 감지 안됨': (['온도감지안됨'], 'TE', '온도 감지 안됨(물 가열 안 됨)'), 'tE': (['TE'], 'TE', '온도 감지 안됨(물 가열 안 됨)'),
    '진동 센서 이상': (['진동센서이상'], 'VS', '진동 센서 이상'), 'vs': (['VS'], 'VS', '진동 센서 이상'),
    '탁도 감지 안됨': (['탁도감지안됨'], 'TS', '탁도 감지 안됨'), 'ts': (['TS'], 'TS', '탁도 감지 안됨'),
    '동결 감지': (['동결감지'], 'FF', '동결 감지'), 'FF': (['FF'], 'FF', '동결 감지'),
    '스팀 동작 안됨': (['스팀동작안됨'], '스팀동작안됨', '스팀 동작 안됨'),
    'tcL': (['TCL'], 'TCL', '통살균 알림'), 'tc ~ L ~ tc ~ L': (['TCL'], 'TCL', '통세척 알림'),
    '[L': (['CL'], 'CL', '버튼 잠금'), 'CF': (['CF'], 'CF', '냉각 동작 표시'), 'AE': (['AE'], 'AE', '누수 감지'),
    'PF': (['PF'], 'PF', '정전 알림'), 'LE7': (['LE7'], 'LE7', '작동 이상'),
}
D = {
    '배수 안됨': (['배수안됨'], 'OE', '배수 안됨(펌프 이상·동결)'), 'OE': (['OE'], 'OE', '배수 안됨(펌프 이상·동결)'),
    '문 안닫힘': (['문안닫힘'], 'DE1', '문 안닫힘'), 'dE1': (['DE1'], 'DE1', '문 안닫힘'),
    '문 안잠김': (['문안잠김'], 'DE2', '문 안잠김'), 'dEz, dE4': (['DE2', 'DE4'], 'DE2', '문 안잠김'),
    'LE1': (['LE1'], 'LE1', '최대 용량 초과'),
    '온도 센서 이상': (['온도센서이상'], 'TE1', '온도 센서 이상'),
    'tE1, tEz, tE4, tEs, tE6': (['TE1', 'TE2', 'TE4', 'TE5', 'TE6'], 'TE1', '온도 센서 이상'),
    '드럼 온도 이상': (['드럼온도이상'], 'F1', '드럼 온도 이상'), 'F1': (['F1'], 'F1', '드럼 온도 이상'),
    'LE': (['LE'], 'LE', '세탁물 과다'), '드럼 모터 이상': (['드럼모터이상'], '드럼모터이상', '드럼 모터 이상'),
    '컴프레서 이상': (['컴프레서이상'], 'LE2', '컴프레서 이상'), 'LEz, AE': (['LE2', 'AE'], 'LE2', '컴프레서 이상'),
    '팬 모터 이상': (['팬모터이상'], 'LE3', '팬 모터 이상'), 'LE3': (['LE3'], 'LE3', '팬 모터 이상'),
    '적외선 센서 이상': (['적외선센서이상'], '1RE', '적외선 센서 이상'), '1rE': (['1RE'], '1RE', '적외선 센서 이상'),
    '내부 순환팬 이상': (['내부순환팬이상'], '8F1', '내부 순환팬 이상'), '8F1': (['8F1'], '8F1', '내부 순환팬 이상'),
    '필터 장착': (['필터장착'], '1F', '필터 미장착'), '1F': (['1F'], '1F', '필터 미장착'),
    '[L': (['CL'], 'CL', '버튼 잠금'), '[=]~ENd': (['END'], 'END', '구김방지 동작'),
    '스팀 동작 안됨': (['스팀동작안됨'], '스팀동작안됨', '스팀 동작 안됨'),
    '서비스 커버 빠짐': (['서비스커버빠짐'], '서비스커버빠짐', '서비스 커버 미장착'),
    '스팀 물보충': (['스팀물보충'], '스팀물보충', '스팀 물 보충'),
}
DW = {
    'IE (IE, 1E)': (['IE', '1E'], 'IE', '급수 안됨'), 'IE': (['IE', '1E'], 'IE', '급수 안됨'),
    'OE (OE, 0E)': (['OE', '0E'], 'OE', '배수 안됨'), 'OE': (['OE', '0E'], 'OE', '배수 안됨'),
    'AE FE (AE, FE)': (['AE', 'FE'], '', '수평 설치 불량 또는 누수'), 'AE FE': (['AE', 'FE'], '', '수평 설치 불량 또는 누수'),
    'NE (nE)': (['NE'], 'NE', '노즐 회전 이상'), 'NE': (['NE'], 'NE', '노즐 회전 이상'),
    'tE (tE)': (['TE'], 'TE', '물 온도 감지 이상'), 'tE': (['TE'], 'TE', '물 온도 감지 이상'),
    'HE tE (HE, tE)': (['HE', 'TE'], '', '물이 데워지지 않음'), 'HE tE': (['HE', 'TE'], '', '물이 데워지지 않음'),
    'bE AE (bE, AE)': (['BE', 'AE'], '', '거품 또는 수평 설치 불량'), 'bE AE': (['BE', 'AE'], '', '거품 또는 수평 설치 불량'),
    'LE (LE)': (['LE'], 'LE', '세척 모터 이상'), 'LE': (['LE'], 'LE', '세척 모터 이상'),
    'F31 (F31)': (['F31'], 'F31', '열풍 모터 이상'), 'F 3 1': (['F31'], 'F31', '열풍 모터 이상'),
    'F3s (F35)': (['F35'], 'F35', '열풍 온도 감지 이상'), 'F 3 s': (['F35'], 'F35', '열풍 온도 감지 이상'),
    'F37 (F37)': (['F37'], 'F37', '자동 문열림 이상'), 'F 3 7': (['F37'], 'F37', '자동 문열림 이상'),
    'A1 H1 ÂÂ 11 ÄÄ ÅÅ ÃÃ (AI, A1, HI, H1)': (['AI', 'A1', 'HI', 'H1'], '', '오염도 감지 알림'),
}
# Labels in the order they appear in each manual's error topic.
ORDER = {
    'MFL71792815': (W, ['탈수 안됨', '급수 안됨', '배수 안됨', '문 안닫힘 / 문 안잠김', '물높이 높음', '물높이 감지 안됨', '모터 회전 이상', '온도 감지 안됨', '진동 센서 이상', '탁도 감지 안됨', '동결 감지', '스팀 동작 안됨']),
    'MFL71792818': (W, ['탈수 안됨', '급수 안됨', '배수 안됨', '문 안닫힘 / 문 안잠김 / 문 열림', '물높이 높음', '물높이 감지 안됨', '모터 회전 이상', '온도 감지 안됨', '진동 센서 이상', '탁도 감지 안됨', '동결 감지']),
    'MFL71792819': (W, ['UE', 'IE', 'OE', 'dE1, dEz', 'dE4', 'FE', 'PE', 'LE', 'tE', 'vs', 'FF', 'tcL', '[L', 'CF', 'AE', 'PF', 'ts']),
    'MFL71831233': (W, ['UE', 'IE', 'OE', 'dE', 'dL', 'LE~1~LE~1', '[L', 'LE FE tE AE FF E3', 'tc ~ L ~ tc ~ L']),
    'MFL71964311': (W, ['UE', 'IE', 'OE', 'dE, dE1, dEz', 'dE4', 'FE', 'PE', 'LE', 'tE', 'vs', 'FF', 'tcL', '[L', 'CF', 'AE', 'PF']),
    'MFL71995305': (W, ['UE', 'IE', 'OE', 'dE, dE1, dEz', 'dE4', 'FE', 'PE', 'LE', 'tE', 'vs', 'FF', 'tcL', '[L', 'CF', 'AE', 'PF']),
    'MFL71995306': (W, ['UE', 'IE', 'OE', 'dE, dE1, dEz', 'dE4', 'FE', 'PE', 'LE', 'tE', 'vs', 'FF', 'tcL', '[L', 'CF', 'AE', 'PF']),
    'MFL71995312': (W, ['UE', 'IE', 'OE', 'dE, dE1, dEz', 'dE4', 'FE', 'PE', 'LE', 'tE', 'vs', 'FF', 'tcL', '[L', 'CF', 'AE', 'PF', 'LE7']),
    'MFL71831427': (D, ['OE', 'dE1', 'dEz, dE4', 'LE1', 'tE1, tEz, tE4, tEs, tE6', 'F1', 'LE', 'LEz, AE', 'LE3', '1rE', '8F1', '1F', '[L', '[=]~ENd']),
    'MFL71831431': (D, ['배수 안됨', '문 안닫힘', '문 안잠김', '온도 센서 이상', '드럼 온도 이상', '드럼 모터 이상', '컴프레서 이상', '팬 모터 이상', '스팀 동작 안됨', '적외선 센서 이상', '내부 순환팬 이상', '필터 장착', '스팀 물보충']),
    'MFL71831442': (D, ['배수 안됨', '문 안닫힘', '문 안잠김', '온도 센서 이상', '드럼 온도 이상', '드럼 모터 이상', '컴프레서 이상', '팬 모터 이상', '스팀 동작 안됨', '필터 장착', '서비스 커버 빠짐']),
    'MFL71831451': (D, ['배수 안됨', '문 안닫힘', '문 안잠김', '온도 센서 이상', '드럼 온도 이상', '드럼 모터 이상', '컴프레서 이상', '팬 모터 이상', '스팀 동작 안됨', '필터 장착', '서비스 커버 빠짐']),
    'MFL71959103': (DW, ['IE', 'OE', 'AE FE', 'NE', 'tE', 'HE tE', 'bE AE', 'LE', 'F 3 1', 'F 3 s', 'F 3 7']),
    'MFL71959117': (DW, ['IE (IE, 1E)', 'OE (OE, 0E)', 'AE FE (AE, FE)', 'NE (nE)', 'tE (tE)', 'HE tE (HE, tE)', 'bE AE (bE, AE)', 'LE (LE)', 'F31 (F31)', 'F3s (F35)', 'F37 (F37)', 'A1 H1 ÂÂ 11 ÄÄ ÅÅ ÃÃ (AI, A1, HI, H1)']),
    'MFL71959119': (DW, ['IE (IE, 1E)', 'OE (OE, 0E)', 'AE FE (AE, FE)', 'NE (nE)', 'tE (tE)', 'HE tE (HE, tE)', 'bE AE (bE, AE)', 'LE (LE)', 'F37 (F37)', 'A1 H1 ÂÂ 11 ÄÄ ÅÅ ÃÃ (AI, A1, HI, H1)']),
    'MFL71959121': (DW, ['IE (IE, 1E)', 'OE (OE, 0E)', 'AE FE (AE, FE)', 'NE (nE)', 'tE (tE)', 'HE tE (HE, tE)', 'bE AE (bE, AE)', 'LE (LE)', 'F37 (F37)', 'A1 H1 ÂÂ 11 ÄÄ ÅÅ ÃÃ (AI, A1, HI, H1)']),
}
# TR15WV5 lists six codes in one row; the manual's sentences split into a contact-only part and the FF part.
SPLIT = {('MFL71831233', 'LE FE tE AE FF E3'): [
    ('LE FE tE AE E3', ['LE', 'FE', 'TE', 'AE', 'E3'], '', 'LE·FE·tE·AE·E3 표시', [('해당 표시창에 위 에러가 표시되었나요?', 'LG전자 서비스 센터(1544-7777)에 연락하세요.')]),
    ('FF', ['FF'], 'FF', '동결 감지', [('', 'FF는 겨울철 동결 관리하기를 따라 조치하세요. 만약 FF가 계속 표시된다면 LG전자 서비스 센터(1544-7777)에 문의하세요.')]),
]}
# Refrigerators: code-specific LG support pages (collected here) and reviewed official facts already in knowledge.json.
PAGE_CL = 'https://www.lge.co.kr/support/solutions-20153004133823'
PAGE_27 = 'https://www.lge.co.kr/support/solutions-20155399741079'
CODES_27 = 'CO Od Sd CI IF CF IU dH d1 d2 d H1 H2 FS rS F1 F2 US LS E1 E2 r dS IS Id gS gF'.split()
RESET_27 = '냉장고의 전원 플러그를 벽면 콘센트에서 뽑으세요. 5분 정도 기다려 주세요. 냉장고를 다시 벽면 콘센트에 꽂으세요. 오류 코드가 사라졌는지 확인하십시오.'
FRIDGE = [
    ('Er CH / Er CL', ['ERCH', 'ERCL'], '', '냉각 부족', '냉매 부족 또는 냉매 누출; 압축기, 밸브 또는 기타 내부 사이클 구성 요소 문제; 내부 사이클의 막힘; 장시간 열어둔 냉장고나 냉동고 문', PAGE_CL, [
        ('', '냉장고와 냉동실 문이 열려 있었다면 완전히 닫으세요. 내부가 식을 때까지 기다리세요. 오류 코드가 사라졌는지 확인하세요.'),
        ('', '냉장고의 전원 플러그를 벽면 콘센트에서 뽑으세요. 5분간 기다린 후, 다시 콘센트에 꽂으세요. 에러 코드가 사라졌는지 확인하세요.'),
        ('', '안내해 드린 방법으로도 문제가 지속된다면, 홈페이지의 챗봇 상담이나 출장 서비스를 통해 전문적인 지원을 받아보세요.')]),
    ('Er ' + ' / Er '.join(CODES_27), ['ER' + c.upper() for c in CODES_27], '', '내부 시스템 이상', '부품 고장, 제어 회로 문제, 전선 단선 등 내부 시스템 문제', PAGE_27, [
        ('', RESET_27),
        ('', '안내해 드린 방법으로도 문제가 지속된다면, 홈페이지의 챗봇 상담이나 출장 서비스를 통해 전문적인 지원을 받아보세요.')]),
    ('Er dH / Er d1 / Er d2 / Er d / Er H1 / Er H2', ['ERDH', 'ERD1', 'ERD2', 'ERD', 'ERH1', 'ERH2'], '', '냉각판 결빙 관련 이상', '냉각판에 있는 얼음', PAGE_27, [
        ('', '냉장고 안의 음식물을 모두 꺼내고, 냉장고 문 앞 바닥에 마른 수건을 깔아 두세요. 그런 다음, 벽면 콘센트에서 전원 코드를 뽑고 문을 최소 12시간 동안 열어 두십시오.')]),
]
# Official facts already reviewed in knowledge.json (T2 rows); their numbered user actions become the items.
FACTS = {'T2-030': (['ERFF'], '냉동실 팬 이상'), 'T2-031': (['ERRF'], '냉장실 팬 이상'), 'T2-032': (['FDH'], '제상 이상'),
         'T2-033': (['RDH'], '제상 이상'), 'T2-045': (['H1'], '상칸 좌측 제상 이상'), 'T2-046': (['H2'], '상칸 우측 제상 이상'),
         'T2-047': (['FF'], '상칸 냉동 팬 이상'), 'T2-048': (['UF'], '중칸·하칸 팬 이상'), 'T2-049': (['LF'], '상칸 좌측 팬 이상'),
         'T2-050': (['RF'], '상칸 우측 팬 이상')}

SENTENCE = re.compile(r'(?<=[.?])(?!\d)')
CAUSE = re.compile(r'(나타납니다|현상입니다|인식되지 않습니다|작동하지 않습니다|작동되지 않을 경우)\.?$')


def fetch(url: str, name: str, used_for: str, registry: SourceRegistry) -> bytes:
    path = CACHE / name
    if path.exists():
        return path.read_bytes()
    request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
    CACHE.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body)
    registry.record(url, used_for, body, 'FETCHED')
    time.sleep(1)
    return body


def error_topic(js: str) -> tuple[str, str]:
    for m in re.finditer(r'indexer\[\d+\]\s*=\s*\{"urlHash":`([^`]*)`,"topicId":`[^`]*`,"title":`([^`]*)`,"content":`([^`]*)`\};', js):
        if re.fullmatch(r'에러 메[시세]지', m.group(2)):
            return m.group(1), m.group(3)
    raise SystemExit('error topic missing')


def clean(text: str) -> str:
    text = text.replace('\\/', '/').replace('\\n', ' ').replace('\\t', ' ').replace('해결방안 펼치기', ' ')
    return re.sub(r'\(\s+', '(', re.sub(r'\s+', ' ', text)).strip()


def items(segment: str) -> tuple[list, list]:
    found, causes = [], []
    for sentence in (s.strip() for s in SENTENCE.split(segment)):
        if not sentence:
            continue
        if sentence.endswith('?'):
            found.append([sentence, []])
        elif CAUSE.search(sentence):
            causes.append(sentence)
        elif found:
            found[-1][1].append(sentence)
        else:
            found.append(['', [sentence]])
    return [(check, ' '.join(action)) for check, action in found if check or action], causes


def manual_rows(category: str, manuals: list, topic: str, text: str) -> list:
    table, order = ORDER[manuals[0]]
    body = clean(text)
    body = body[body.index('다를 수 있습니다.') + len('다를 수 있습니다.'):].strip()
    starts, at = [], 0
    for label in order:
        spaced = r'\s*'.join(re.escape(part) for part in label.split())
        m = re.compile(spaced).search(body, at)
        if not m:
            raise SystemExit(f'{manuals[0]}: label {label} not found')
        starts.append((label, m.start(), m.end()))
        at = m.end()
    rows = []
    for i, (label, _, end) in enumerate(starts):
        segment = body[end: starts[i + 1][1] if i + 1 < len(starts) else len(body)].strip()
        if (manuals[0], label) in SPLIT:
            parts = SPLIT[(manuals[0], label)]
        else:
            codes, canonical, meaning = table[label]
            found, causes = items(segment)
            parts = [(label, codes, canonical, meaning, found, causes)]
        for part in parts:
            shown, codes, canonical, meaning, found = part[:5]
            causes = part[5] if len(part) > 5 else []
            for n, (check, action) in enumerate(found, start=1):
                rows.append([category, ';'.join(manuals), shown, ';'.join(codes), canonical, meaning, ' '.join(causes), n, check, action, f'{manuals[0]}#{topic}'])
    return rows


def fact_items(action: str) -> list:
    return [(m.group(2).strip()) for m in re.finditer(r'(\d)\)\s*(.*?)(?=\s*\d\)\s|$)', action)]


def main() -> None:
    registry = SourceRegistry()
    models = json.loads(MODELS.read_text(encoding='utf-8'))
    groups = {}
    for model in models.values():
        if model['category'] not in PREFIX:
            continue
        for manual in model.get('manual_ids') or []:
            url = f"https://gscs-manual.lge.com/{PREFIX[model['category']]}/{manual}/ko-kr/res/js/indexer.js"
            js = fetch(url, f'{manual}-indexer.js', f'error_guides:{manual}', registry).decode('utf-8', 'replace')
            topic, text = error_topic(js)
            key = hashlib.sha256(clean(text).encode()).hexdigest()
            groups.setdefault((model['category'], key), {'manuals': set(), 'topic': topic, 'text': text})['manuals'].add(manual)
    rows = []
    for (category, _), group in sorted(groups.items(), key=lambda g: (g[0][0], sorted(g[1]['manuals']))):
        manuals = sorted(group['manuals'])
        if any(m not in ORDER for m in manuals):
            raise SystemExit(f'no reviewed label order for {manuals}')
        rows += manual_rows(category, manuals, group['topic'], group['text'])
    for page in (PAGE_CL, PAGE_27):
        fetch(page, page.rsplit('/', 1)[1] + '.html', 'error_guides:refrigerator', registry)
    for shown, codes, canonical, meaning, cause, page, found in FRIDGE:
        for n, (check, action) in enumerate(found, start=1):
            rows.append(['REFRIGERATOR', '품목 공통', shown, ';'.join(codes), canonical, meaning, cause, n, check, action, page])
    facts = {d['id']: d for d in json.loads((ROOT / 'backend/src/main/resources/homecare/knowledge.json').read_bytes())}
    for fact_id, (codes, meaning) in FACTS.items():
        content = json.loads(facts[fact_id]['text'])['record']['content']
        for n, action in enumerate(fact_items(content['user_action']), start=1):
            rows.append(['REFRIGERATOR', '품목 공통', content['error_code'], ';'.join(codes), '', meaning, content['cause'], n, '', action, fact_id])
    registry.save()
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator='\n')
    writer.writerow(HEADER)
    writer.writerows(rows)
    OUTPUT.write_bytes(b'\xef\xbb\xbf' + buffer.getvalue().encode('utf-8'))
    print(f'{len(rows)} rows from {len(groups)} manual tables, {len(FRIDGE)} support-page entries and {len(FACTS)} official facts')


if __name__ == '__main__':
    main()
