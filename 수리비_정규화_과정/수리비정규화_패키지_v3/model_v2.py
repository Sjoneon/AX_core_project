# -*- coding: utf-8 -*-
"""
v2: 기술료를 '제품군'이 아니라 '작업 난이도 등급'으로 나눈다.
근거: LG전자 한국 요금 안내 — 수리비(기술료)는 '수리 난이도(시간)에 따른 순수 기술료'
      LG전자 중국 有偿服务收费标准 — 대수리/중수리/소수리로 부품별 등급을 공시 (lg.com/cn/support/service-policy-4)
[변수]/[함수] 표시.
"""
import pandas as pd
import model as M

# ───────── [변수] 부품코드 → 작업 등급 ─────────
# T1 전장·센서 교체(분해 적음) / T2 기계부품 교체(모터·펌프·밸브·팬·히터·롤러 등) / T3 대분해(베어링·드럼·냉매계통·세탁기 도어 가스켓)
TIER = {
 "W01":"T2","W02":"T2","W03":"T1","W04":"T1","W05":"T2","W06":"T2","W07":"T2","W08":"T3","W09":"T1","W10":"T2",
 "D01":"T2","D02":"T2","D03":"T1","D04":"T1","D05":"T2","D06":"T1","D07":"T2",
 "R01":"T3","R02":"T1","R03":"T2","R04":"T2","R05":"T1","R06":"T1","R07":"T1","R08":"T2","R09":"T2","R10":"T1",
 "DW01":"T2","DW02":"T2","DW03":"T2","DW04":"T2","DW05":"T1","DW06":"T1",
}
# ───────── [변수] 국내 기술료 관측치의 작업 등급 (교체부품 문구로 판정, 판정 불가는 제외) ─────────
OBS_TIER = {
 # LG
 "W10":"T1",  # 건조 써미스터(센서)
 "D01":"T1",  # 회로기판
 "D06":"T2",  # 베어링·롤러(건조기 롤러 교체)
 "D14":"T2",  # 배수펌프
 "D19":"T2",  # 배수 관련 부품
 "R06":"T3",  # 증발기(냉매계통)+모터
 "R11":"T2",  # 필라히터
 "R14":"T1",  # 메인 기판
 # 삼성
 "SW01":"T3", # 히터·다이어프램 — 세탁조 분해
 "SW03":"T3", # 베어링
 "SW07":"T1", "SW11":"T1", "SD03":"T1", "SD07":"T1", "SR02":"T1", "SR06":"T1", "SR25":"T1",  # 메인보드·PCB
 "SW12":"T2", # 자동세제 모터
 "SW13":"T2", # 배수펌프
 "SR10":"T2", # 히터 추가
 "SR11":"T2", # 컴프 팬모터
 "SR14":"T2", # 제상 관련 부품
 "SS06":"T1", # 조명 부품
 "SR12":"T1", # 와이파이 모듈
}
# 판정 제외: W12·SD10·SS05(부품 미기재), R13·R16·SR17(부품 없이 청소·조치), SR08(선 연장), W11(고객과실)

def q(v, p): return M.q(v, p)

def tier_labor(exclude_case=None):
    """[함수] 등급별 국내 기술료(오늘가치) 분포"""
    d = M.DM.set_index("ID")["기술료(오늘가치)"]
    out = {}
    for t in ("T1", "T2", "T3"):
        v = [d[i] for i, tt in OBS_TIER.items() if tt == t and i != exclude_case and i in d and pd.notna(d[i])]
        out[t] = {"n": len(v), "p25": q(v, .25), "p50": q(v, .5), "p75": q(v, .75), "values": v}
    return out

TL = tier_labor()

def predict2(code, exclude_case=None, trip=28000):
    """[함수] v2 예상 총액: 부품비(v1과 동일) + 등급별 기술료 + 출장비"""
    p = M.parts_estimate(code, exclude_case)
    if not p: return None
    L = tier_labor(exclude_case)[TIER[code]]
    return {"부품코드": code, "부품": M.CODE_NAME[code], "작업 등급": TIER[code], **p,
            "기술료 하한": round(L["p25"]), "기술료 중앙": round(L["p50"]), "기술료 상한": round(L["p75"]),
            "기술료 근거": f"국내 {TIER[code]} 사례 {L['n']}건",
            "출장비(평일)": trip,
            "총액 하한": round(trip + L["p25"] + p["부품비 하한"]),
            "총액 중앙": round(trip + L["p50"] + p["부품비 중앙"]),
            "총액 상한": round(trip + L["p75"] + p["부품비 상한"])}

if __name__ == "__main__":
    for t, v in TL.items(): print(t, v["n"], round(v["p25"]), round(v["p50"]), round(v["p75"]), [round(x) for x in v["values"]])
    VV = pd.read_csv(M.DATA_DIR / "out_validation.csv")
    rows = []
    for _, r in VV.iterrows():
        p1 = M.predict(r["부품코드"], exclude_case=r["ID"]); p2 = predict2(r["부품코드"], exclude_case=r["ID"])
        a = r["실제(오늘가치)"]
        rows.append({"ID": r["ID"], "부품코드": r["부품코드"], "등급": TIER[r["부품코드"]], "실제": a,
                     "v1 중앙": p1["총액 중앙"], "v1 오차": round((p1["총액 중앙"] - a) / a * 100, 1),
                     "v2 중앙": p2["총액 중앙"], "v2 오차": round((p2["총액 중앙"] - a) / a * 100, 1),
                     "v2 범위 안": p2["총액 하한"] <= a <= p2["총액 상한"],
                     "v2 범위폭%": round((p2["총액 상한"] - p2["총액 하한"]) / p2["총액 중앙"] * 50, 1)})
    R = pd.DataFrame(rows); print(R.to_string())
    for c in ("v1 오차", "v2 오차"):
        e = R[c].abs(); print(c, "±5", (e <= 5).sum(), "±10", (e <= 10).sum(), "±20", (e <= 20).sum(), "중앙", e.median(), "평균", round(e.mean(), 1))
    print("v2 범위 적중", R["v2 범위 안"].mean(), "범위폭 중앙 ±", R["v2 범위폭%"].median())
