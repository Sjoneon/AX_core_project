# -*- coding: utf-8 -*-
"""
HomeCare AI — 해외·국내 수리비 한국 기준 정규화 & 예상 수리비 산출
기준일: 2026-10-08 (환율·물가). 기준값만 바꾸면 다시 계산된다.

[변수] = 설정값/데이터, [함수] = 계산 로직
"""
import re, csv
import pandas as pd

# ───────────────────────── [변수] 기준값 ─────────────────────────
REF_DATE = "2026-10-08"
# 매매기준율: KB국민은행 2026-10-08 2회차 고시 (한국무역협회 환율종합 페이지 게재)
FX = {"USD": 1340.00, "EUR": 1501.07, "GBP": 1770.94, "INR": 13.85}
FX_SRC = "https://www.kita.net/cmmrcInfo/ehgtGnrlzInfo/rltmEhgt.do (KB국민은행 2026-10-08 2회차)"
KR_VAT = 0.10           # 한국 부가가치세율
# 소비자물가지수(2020=100) 연평균. 2024=114.18(공식 보도), 각 연도 상승률로 역산·순산
# 2017 1.9%, 2018 1.5%, 2019 0.4%, 2020 0.5%, 2021 2.5%, 2022 5.1%, 2023 3.6%, 2024 2.3%, 2025 2.1%
CPI = {2016: 95.82, 2017: 97.64, 2018: 99.11, 2019: 99.50, 2020: 100.0, 2021: 102.50,
       2022: 107.73, 2023: 111.61, 2024: 114.18, 2025: 116.58, 2026: 120.43}
CPI_REF = 120.43        # 2026년 9월(최신 발표) 지수 — 오늘 기준값으로 사용
# LG 현재 공식 출장비 (lge.co.kr/support/rates-warranty-guide)
TRIP_NOW = {"평일": 28000, "야간·휴일": 33000, "성수기 평일": 33000, "성수기 야간·휴일": 38000}

# 과거 LG 평일 출장비 추정 이력 (출장비 미기재 사례 보정용 — 2023 이전은 사례값, 이후는 언론 보도)
def lg_trip_then(d):
    """[함수] 작성일 기준 당시 LG 평일 출장비 추정"""
    if not d or d == "미기재": return None
    y, m = int(d[:4]), int(d[5:7])
    if (y, m) < (2023, 1): return 18000
    if (y, m) < (2024, 9): return 20000
    if (y, m) < (2025, 12):
        return 30000 if (y == 2025 and 6 <= m <= 8) else 25000
    return 33000 if (y == 2026 and 6 <= m <= 8) else 28000

# ──────────────────── [변수] 부품코드 매핑 (국내 사례 → 해외 코드) ────────────────────
CODE_NAME = {
 "W01":"배수펌프","W02":"급수밸브","W03":"도어락 스위치","W04":"메인 PCB","W05":"DD모터 스테이터",
 "W06":"DD모터 로터","W07":"댐퍼","W08":"도어 가스켓","W09":"수위센서","W10":"히터",
 "D01":"드럼 벨트","D02":"히터(미국 전기식)","D03":"온도센서 키트","D04":"온도퓨즈","D05":"드럼 모터",
 "D06":"메인 PCB","D07":"드럼 롤러",
 "R01":"컴프레서","R02":"메인 PCB","R03":"증발기(냉동실) 팬모터","R04":"응축기(기계실) 팬모터",
 "R05":"제상 센서","R06":"온도센서","R07":"아이스메이커","R08":"급수밸브","R09":"제상 히터","R10":"도어 가스켓",
 "DW01":"배수펌프","DW02":"세척(순환)펌프","DW03":"급수밸브","DW04":"히터","DW05":"메인 PCB","DW06":"도어 가스켓",
}
PRODUCT_OF = lambda c: {"W":"세탁기","D":"건조기","R":"냉장고"}.get(c[0], "식기세척기") if not c.startswith("DW") else "식기세척기"

def map_code(product, part):
    """[함수] 국내 사례의 제품군·교체부품 문구 → 부품코드"""
    p = part or ""
    if product.startswith("세탁기"):
        if "가스켓" in p or "고무패킹" in p: return "W08"
        if "메인" in p or "PCB" in p or "기판" in p: return "W04"
        if "배수펌프" in p: return "W01"
        if "급수" in p: return "W02"
        if "댐퍼" in p: return "W07"
        if "진동센서" in p: return None
    if product.startswith("건조기"):
        if "롤러" in p: return "D07"
        if "PCB" in p or "기판" in p or "메인보드" in p: return "D06"
    if product.startswith("냉장고"):
        if "컴프레서" in p and "무상" not in p: return "R01"
        if "메인" in p or "기판" in p or "PCB" in p: return "R02"
        if "기계실" in p or (("팬모터" in p or "모터" == p.strip()) and "냉동" not in p): return "R04"
        if "냉동실 팬" in p: return "R03"
    if product.startswith("식기세척기"):
        if "배수모터" in p or "배수펌프" in p: return "DW01"
        if "메인" in p or "제어창" in p or "판넬" in p or "패널" in p: return "DW05"
    return None

def won(s):
    """[함수] '21만원', '20만원대', '110,000' → 정수 원"""
    m = re.search(r"(\d+(?:\.\d+)?)\s*만", s)
    if m: return int(float(m.group(1)) * 10000)
    m = re.findall(r"\d[\d,]{2,}", s)
    return int(m[0].replace(",", "")) if m else None

def list_price(v):
    """[함수] '110,000→0', '21만원→0'처럼 무상 처리 전 정가가 적힌 경우 정가 추출"""
    s = str(v or "")
    return won(s.split("→")[0]) if "→" in s else None

def num(v):
    """[함수] '약 181,000', '110,000→0', '31,000(역산)' 같은 표기에서 숫자 추출(마지막 값 우선은 화살표만)"""
    if v is None: return None
    s = str(v).strip()
    if not s or s in ("미기재", "-", "포함", "별도") or "합산" in s or "포함" in s and not re.search(r"\d", s):
        return None
    if "→" in s: s = s.split("→")[-1]
    m = re.findall(r"\d[\d,]*", s)
    if not m: return None
    return int(m[0].replace(",", ""))

# ───────────────────────── 1. 해외 데이터 정규화 ─────────────────────────
def normalize_overseas(path):
    """[함수] 세전 현지가 → 오늘 환율 → 한국 부가세 포함 원화"""
    d = pd.read_csv(path)
    out = []
    for _, r in d.iterrows():
        cur = r["현지 통화"]; pre = r["세전 금액(현지)"]
        rate = FX.get(cur)
        ok = pd.notna(pre) and rate is not None
        krw_pre = round(pre * rate) if ok else None
        out.append({
            "번호": r["번호"], "국가": r["국가"], "품목": r["품목"], "부품코드": r["부품코드(초안)"],
            "부품명": r["부품명(한글)"], "부품번호": r["부품번호"], "비용 구분": r["비용 구분"],
            "현지 통화": cur, "표시 금액": r["표시 금액"], "세금 포함 여부": r["세금 포함 여부"],
            "제거한 현지 세율": r["적용 세율"], "세전 금액(현지)": pre,
            "환율(2026-10-08)": rate, "세전 금액(원화)": krw_pre,
            "한국 부가세 10% 포함(원화)": round(krw_pre * (1 + KR_VAT)) if ok else None,
            "파일 환율(10-07) 기준 원화": r["세전 금액(원화)"],
            "신뢰등급": r["신뢰등급"], "출처 URL": r["출처 URL"], "비고": r["비고"],
            "정규화 메모": ("" if ok else "금액 없음 또는 세전 역산 불가 — 계산 제외"),
        })
    return pd.DataFrame(out)

# ───────────────────────── 2. 국내 사례 정규화 ─────────────────────────
def normalize_domestic(path, brand):
    """[함수] 국내 사례: 기술료·부품비는 CPI로 오늘 가치, 출장비는 현재 공식 평일 요금으로 대체"""
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
    out = []
    for r in rows:
        d = r["작성일"]; y = int(d[:4]) if d and d[:4].isdigit() else None
        f = CPI_REF / CPI[y] if y in CPI else None
        tech, part, trip, total = num(r["기술료"]), num(r["부품비"]), num(r["출장비"]), num(r["총청구액(원)"])
        if part is not None and part < 100: part = None          # '2차 0' 같은 파싱 잡음 제거
        lp = list_price(r["부품비"])                              # 무상 처리 전 정가
        if lp is not None and lp < 1000: lp = None
        trip_then = trip if trip else (lg_trip_then(d) if brand == "LG" else None)
        excl = any(k in (r["보증관련"] + r["무상유상"]) for k in ["과실", "부주의", "파손"])
        quote = "견적" in r["총액비고"] and not r["수리vs교체"].startswith("수리")
        paid = total is not None and total > 0 and not r["무상유상"].startswith("무상") \
               and r["ID"] not in ("R04", "S03")   # R04=컴프레서 무상·청소비만, S03=출장비만
        # 총액 현재가 추정: (총액 - 당시 출장비) × CPI + 현재 평일 출장비
        now_total = None
        if paid and f and trip_then:
            body = max(total - trip_then, 0)
            now_total = round(body * f + TRIP_NOW["평일"])
        out.append({
            "브랜드": brand, "ID": r["ID"], "제품군": r["제품군"].split("(")[0], "교체부품": r["교체부품"],
            "부품코드": map_code(r["제품군"], r["교체부품"]), "작성일": d, "CPI 보정계수": round(f, 4) if f else None,
            "출장비(당시)": trip, "출장비(당시 추정)": trip_then if not trip else None,
            "기술료(당시)": tech, "기술료(오늘가치)": round(tech * f) if tech and f else None,
            "부품비(당시)": part, "부품비(오늘가치)": round(part * f) if part and f else None,
            "부품 정가(무상 전)": lp, "부품 정가(오늘가치)": round(lp * f) if lp and f else None,
            "총액(당시)": total, "총액(오늘가치·현재 출장비)": now_total,
            "무상유상": r["무상유상"], "유상 실결제": paid, "고객과실": excl, "견적만": quote,
            "출처": r["출처URL"],
        })
    return pd.DataFrame(out)

if __name__ == "__main__":
    ov = normalize_overseas("/home/claude/norm/overseas_v1.csv")
    lg = normalize_domestic("/home/claude/LG_AS_수리비_후기_사례.csv", "LG")
    ss = normalize_domestic("/home/claude/삼성_AS_수리비_후기_사례.csv", "삼성")
    ov.to_csv("/home/claude/norm/out_overseas_normalized.csv", index=False, encoding="utf-8-sig")
    pd.concat([lg, ss]).to_csv("/home/claude/norm/out_domestic_normalized.csv", index=False, encoding="utf-8-sig")
    print(ov[["국가","부품코드","부품번호","세전 금액(원화)","한국 부가세 10% 포함(원화)","파일 환율(10-07) 기준 원화"]].head(8))
    print(lg[lg["부품코드"].notna()][["ID","부품코드","교체부품","부품비(당시)","부품비(오늘가치)","기술료(오늘가치)","총액(당시)","총액(오늘가치·현재 출장비)"]].to_string())
