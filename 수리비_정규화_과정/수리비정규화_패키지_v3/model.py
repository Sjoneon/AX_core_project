# -*- coding: utf-8 -*-
"""
예상 수리비(한국, 부가세 포함) = 출장비(공식표) + 기술료(국내 사례 분포) + 부품비(해외가 × 시장보정계수)
normalize.py 실행 후 사용. [변수]/[함수] 표시.
"""
import statistics as st
import pandas as pd
from normalize import CODE_NAME, TRIP_NOW

OV = pd.read_csv("/home/claude/norm/out_overseas_normalized.csv")
DM = pd.read_csv("/home/claude/norm/out_domestic_normalized.csv")

# ───────── [변수] 국내 LG 부품가 관측치 (오늘가치, 부가세 포함 가정) ─────────
# (부품코드, 사례ID, 금액, 근거)
KR_PART = [
    ("W04", "W01", 113633, "메인보드 정가 110,000원(무상 처리 전 안내가), 2025-11"),
    ("D06", "D01", 142107, "회로기판 부품비 118,000원, 2020-07"),
    ("D07", "D06", 51793,  "베어링·롤러 부품비 48,000원, 2023-07"),
    ("D07", "D15", 10547,  "롤러 4개 부품비 10,000원, 2024-07 (작성자 기재, 낮아서 이상치 가능)"),
    ("R02", "R14", 44783,  "메인 기판 부품비 37,000원, 2019-07 (13년 된 구형 모델)"),
    ("DW05", "S02", 221495, "디스플레이 메인보드 정가 21만원(무상 처리 전 안내가), 2024-12"),
    ("DW05", "S03", 210948, "제어창 1차 견적 20만원대, 2024-02 (하한값)"),
    ("W08", "W11", 68500,  "워시타워 고무패킹 부품비 68,500원, 2026-06"),
]

def region(c):
    """[함수] 국가 → 비교 그룹"""
    return "US" if c == "미국" else ("EU" if c in ("독일", "영국") else None)

PARTS = OV[(OV["비용 구분"] == "부품비") & OV["한국 부가세 10% 포함(원화)"].notna()].copy()
PARTS["그룹"] = PARTS["국가"].map(region)
PARTS["코드"] = PARTS["부품코드"].astype(str).str.split("/").str[0]

def ov_price(code, grp):
    """[함수] 해당 코드·그룹 해외가(한국 부가세 포함 원화)의 중앙값과 건수"""
    v = PARTS[(PARTS["코드"] == code) & (PARTS["그룹"] == grp)]["한국 부가세 10% 포함(원화)"].tolist()
    return (st.median(v), len(v)) if v else (None, 0)

def ratios(exclude_case=None):
    """[함수] 국내/해외 가격비 — 그룹별 목록. exclude_case: 검증 시 해당 사례 제외(LOO)"""
    out = {"US": [], "EU": []}
    for code, cid, krw, _ in KR_PART:
        if cid == exclude_case: continue
        for g in out:
            p, _ = ov_price(code, g)
            if p: out[g].append(krw / p)
    return out

def q(v, p):
    """[함수] 단순 분위수"""
    s = sorted(v); k = (len(s) - 1) * p; f = int(k); c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)

def labor_dist(exclude_case=None):
    """[함수] 제품군별 국내 기술료(오늘가치) 분포 — LG+삼성, 고객과실·합산값 제외"""
    d = DM[DM["기술료(오늘가치)"].notna() & (~DM["고객과실"])]
    d = d[~d["ID"].isin(["R12", "SD01", exclude_case])]   # 기술료+부품 합산 표기 건 제외, 검증 시 자기 사례 제외
    res = {}
    for p, g in d.groupby("제품군"):
        v = g["기술료(오늘가치)"].tolist()
        res[p] = {"n": len(v), "p25": q(v, .25), "p50": q(v, .5), "p75": q(v, .75), "values": v}
    allv = d["기술료(오늘가치)"].tolist()
    res["전체"] = {"n": len(allv), "p25": q(allv, .25), "p50": q(allv, .5), "p75": q(allv, .75), "values": allv}
    return res

LAB = labor_dist()
# 식기세척기 기술료 표본(2건) 보완: 인도 LG 공식 방문수리 요금의 상대 난이도
# 식세기 1000루피 / 4개 품목 8개 요금 평균 725루피 = 1.38 → 국내 전체 기술료 분포에 곱함
INDIA_RATES = [550, 650, 750, 1000, 600, 650, 700, 800, 1100]   # 반자동~양문형 (식세기 포함 9개)
INDIA_DW_IDX = 1000 / st.mean([x for x in INDIA_RATES if x != 1000])

def labor(product, exclude_case=None):
    """[함수] 제품군 기술료 구간(P25, P50, P75)과 근거"""
    LB = labor_dist(exclude_case) if exclude_case else LAB
    if product == "식기세척기":
        w = LB["전체"]
        return (w["p25"] * INDIA_DW_IDX, w["p50"] * INDIA_DW_IDX, w["p75"] * INDIA_DW_IDX,
                f"국내 식세기 기술료 {LB.get('식기세척기',{}).get('n',0)}건뿐 → 국내 전체 기술료({w['n']}건) × 인도 공식 요금 상대지수 {INDIA_DW_IDX:.2f}")
    L = LB[product]
    return (L["p25"], L["p50"], L["p75"], f"국내 사례 {L['n']}건(LG+삼성) P25~P75")

def parts_estimate(code, exclude_case=None):
    """[함수] 부품비 추정: 유럽가 있으면 유럽가×KR/EU 계수, 없으면 미국가×KR/US 계수"""
    R = ratios(exclude_case)
    kr_obs = [k for c, cid, k, _ in KR_PART if c == code and cid != exclude_case]
    eu, n_eu = ov_price(code, "EU"); us, n_us = ov_price(code, "US")
    if eu and R["EU"]:
        base, grp, r = eu, "EU", R["EU"]
    elif us and R["US"]:
        base, grp, r = us, "US", R["US"]
    else:
        return None
    est = {"기준 해외가(원)": round(base), "기준 그룹": grp, "해외 표본": n_eu if grp == "EU" else n_us,
           "보정계수 중앙값": round(st.median(r), 3), "보정계수 범위": (round(q(r, .25), 3), round(q(r, .75), 3)),
           "부품비 하한": round(base * q(r, .25)), "부품비 중앙": round(base * st.median(r)),
           "부품비 상한": round(base * q(r, .75)), "국내 관측치": kr_obs}
    return est

def predict(code, exclude_case=None, trip=TRIP_NOW["평일"]):
    """[함수] 예상 총액 (하한·중앙·상한)"""
    p = parts_estimate(code, exclude_case)
    if not p: return None
    prod = {"W": "세탁기", "D": "건조기", "R": "냉장고"}[code[0]] if not code.startswith("DW") else "식기세척기"
    l25, l50, l75, lnote = labor(prod, exclude_case)
    return {"제품군": prod, "부품코드": code, "부품": CODE_NAME[code], **p,
            "기술료 하한": round(l25), "기술료 중앙": round(l50), "기술료 상한": round(l75), "기술료 근거": lnote,
            "출장비(평일)": trip,
            "총액 하한": round(trip + l25 + p["부품비 하한"]), "총액 중앙": round(trip + l50 + p["부품비 중앙"]),
            "총액 상한": round(trip + l75 + p["부품비 상한"])}

def confidence(row):
    """[함수] 신뢰도: 국내 관측치 있음=중간, 유럽가 기반=중간, 미국가만=낮음; 미국형 전용 부품=참고불가"""
    if row["부품코드"] == "D02": return "제외(미국형 전기식 건조기 전용 부품)"
    if row["국내 관측치"]: return "중간"
    if row["기준 그룹"] == "EU":
        de = PARTS[(PARTS["코드"] == row["부품코드"]) & (PARTS["국가"] == "독일")]
        return "중간" if len(de) else "낮음(영국 비공식 판매처가만)"
    return "낮음"

if __name__ == "__main__":
    R = ratios()
    print("KR/US", [round(x, 3) for x in R["US"]], "median", round(st.median(R["US"]), 3))
    print("KR/EU", [round(x, 3) for x in R["EU"]], "median", round(st.median(R["EU"]), 3))
    for k, v in LAB.items(): print(k, v["n"], round(v["p25"]), round(v["p50"]), round(v["p75"]))
    rows = []
    for code in CODE_NAME:
        r = predict(code)
        if r:
            r["신뢰도"] = confidence(r); rows.append(r)
    T = pd.DataFrame(rows)
    T["국내 관측치"] = T["국내 관측치"].apply(lambda v: ", ".join(f"{x:,}" for x in v))
    T["보정계수 범위"] = T["보정계수 범위"].apply(lambda t: f"{t[0]}~{t[1]}")
    T.to_csv("/home/claude/norm/out_estimate_table.csv", index=False, encoding="utf-8-sig")
    print(T[["부품코드","부품","기준 그룹","기준 해외가(원)","부품비 하한","부품비 중앙","부품비 상한","총액 하한","총액 중앙","총액 상한","신뢰도"]].to_string())

    # ───────── 검증: 국내 LG 유상 사례 총액(오늘가치) vs 예측 (LOO) ─────────
    V = DM[(DM["브랜드"] == "LG") & DM["유상 실결제"] & DM["부품코드"].notna()
           & DM["총액(오늘가치·현재 출장비)"].notna() & (~DM["ID"].isin(["W01", "R17"]))]
    out = []
    for _, r in V.iterrows():
        p = predict(r["부품코드"], exclude_case=r["ID"])
        if not p: continue
        a = r["총액(오늘가치·현재 출장비)"]
        out.append({"ID": r["ID"], "부품코드": r["부품코드"], "교체부품": r["교체부품"], "실제(오늘가치)": a,
                    "예측 중앙": p["총액 중앙"], "예측 범위": f"{p['총액 하한']:,}~{p['총액 상한']:,}",
                    "오차율": round((p["총액 중앙"] - a) / a * 100, 1),
                    "범위 안": p["총액 하한"] <= a <= p["총액 상한"], "고객과실": r["고객과실"]})
    VV = pd.DataFrame(out)
    VV.to_csv("/home/claude/norm/out_validation.csv", index=False, encoding="utf-8-sig")
    print(VV.to_string())
    print("n", len(VV), "중앙 절대오차율", st.median(abs(VV["오차율"])), "범위 적중", VV["범위 안"].mean())
