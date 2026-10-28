# -*- coding: utf-8 -*-
"""
v3 = v2(작업 등급별 기술료) + 국내 같은 부품 실제 총액과의 축소 결합(k=2) + 80% 경험적 범위
- 축소 결합: 국내(LG+삼성) 같은 부품코드 유상 사례 n건의 총액 중앙값과 v2 예측을 로그 공간에서
  가중 평균. 가중치 = n/(n+2). 국내 사례가 없으면 v2 그대로.
- 범위: 20건 LOO 예측의 로그 잔차 절댓값 80% 분위수 q → [중앙/e^q, 중앙×e^q]
[변수]/[함수] 표시.
"""
import numpy as np
import pandas as pd
import model as M
import model_v2 as V

K = 2                       # [변수] 축소 강도 (클수록 해외 기반 v2 쪽)
COVER = 0.80                # [변수] 범위 목표 적중률
SURCHARGE = {"평일": 0, "야간·휴일": 5000, "성수기 평일": 5000, "성수기 야간·휴일": 10000}   # [변수] 출장비 가산

POOL = M.DM[M.DM["부품코드"].notna() & M.DM["유상 실결제"] & M.DM["총액(오늘가치·현재 출장비)"].notna() & (~M.DM["고객과실"])]
POOL = POOL[~POOL["ID"].isin(["W01", "R17"])]   # W01=부품 무상(출장·기술료만), R17=총액 값 오기

def predict3(code, exclude_case=None):
    """[함수] v3 중앙 예측과 근거"""
    b = V.predict2(code, exclude_case)
    if not b: return None
    o = POOL[(POOL["부품코드"] == code) & (POOL["ID"] != exclude_case)]["총액(오늘가치·현재 출장비)"]
    n = len(o)
    mid = b["총액 중앙"] if n == 0 else float(np.exp((n / (n + K)) * np.log(o.median()) + (K / (n + K)) * np.log(b["총액 중앙"])))
    return {**b, "국내 같은 부품 사례 수": n, "국내 같은 부품 중앙값": round(o.median()) if n else None,
            "국내 사례 가중치": round(n / (n + K), 2), "v3 중앙": round(mid)}

def loo():
    """[함수] 20건 LOO 예측과 로그 잔차"""
    VV = pd.read_csv(M.DATA_DIR / "out_validation.csv")
    rows = []
    for _, r in VV.iterrows():
        p = predict3(r["부품코드"], exclude_case=r["ID"]); a = r["실제(오늘가치)"]
        rows.append({"ID": r["ID"], "부품코드": r["부품코드"], "교체부품": r["교체부품"], "작업 등급": p["작업 등급"],
                     "실제(오늘가치)": a, "v1 예측": r["예측 중앙"], "v1 오차율": r["오차율"],
                     "v3 예측": p["v3 중앙"], "v3 오차율": round((p["v3 중앙"] - a) / a * 100, 1),
                     "로그잔차": np.log(a / p["v3 중앙"])})
    return pd.DataFrame(rows)

def band_q(L):
    """[함수] 각 사례를 뺀 나머지 잔차로 범위 폭을 정하고(LOO) 적중률 측정, 전체 잔차로 최종 폭 산출"""
    r = L["로그잔차"].abs().values; n = len(r); hits = []
    for i in range(n):
        rr = np.sort(np.delete(r, i)); qi = rr[min(int(np.ceil(n * COVER)) - 1, n - 2)]
        hits.append(r[i] <= qi)
    q_all = np.sort(r)[min(int(np.ceil((n + 1) * COVER)) - 1, n - 1)]
    return q_all, float(np.mean(hits))

if __name__ == "__main__":
    L = loo(); q, hit = band_q(L)
    e1, e3 = L["v1 오차율"].abs(), L["v3 오차율"].abs()
    summ = []
    for name, e in (("v1(처음 모델)", e1), ("v3(등급 기술료+국내 축소)", e3)):
        summ.append({"모델": name, "±5% 이내": f"{(e<=5).sum()}/20", "±10% 이내": f"{(e<=10).sum()}/20",
                     "±20% 이내": f"{(e<=20).sum()}/20", "중앙 절대오차(%)": round(e.median(), 1), "평균 절대오차(%)": round(e.mean(), 1)})
    S = pd.DataFrame(summ); print(S.to_string(index=False))
    print("80% 범위 배수 e^q =", round(np.exp(q), 3), " LOO 적중률 =", hit)
    L.drop(columns=["로그잔차"]).to_csv(M.DATA_DIR / "out_validation_v3.csv", index=False, encoding="utf-8-sig")
    S.to_csv(M.DATA_DIR / "out_model_compare.csv", index=False, encoding="utf-8-sig")
    rows = []
    for code in M.CODE_NAME:
        p = predict3(code)
        if not p: continue
        m = p["v3 중앙"]; lo, hi = m / np.exp(q), m * np.exp(q)
        conf = "제외(미국형 전기식 건조기 전용 부품)" if code == "D02" else (
               "중간" if p["국내 같은 부품 사례 수"] >= 2 or p["국내 관측치"] else
               ("중간" if p["기준 그룹"] == "EU" and M.confidence(p).startswith("중간") else "낮음"))
        rows.append({"제품군": {"W":"세탁기","D":"건조기","R":"냉장고"}.get(code[0], "식기세척기") if not code.startswith("DW") else "식기세척기",
                     "부품코드": code, "부품": p["부품"], "작업 등급": p["작업 등급"],
                     "부품비 추정(중앙)": p["부품비 중앙"], "기술료(등급 중앙)": p["기술료 중앙"], "출장비(평일)": 28000,
                     "해외 기반 총액(v2)": p["총액 중앙"], "국내 같은 부품 사례 수": p["국내 같은 부품 사례 수"],
                     "국내 같은 부품 중앙값": p["국내 같은 부품 중앙값"], "국내 사례 가중치": p["국내 사례 가중치"],
                     "예상 총액 중앙": round(m), "80% 범위 하한": round(lo), "80% 범위 상한": round(hi),
                     "야간·휴일 가산": 5000, "6~8월 가산(평일/야간)": "5000/10000", "신뢰도": conf})
    T = pd.DataFrame(rows); T.to_csv(M.DATA_DIR / "out_estimate_table_v3.csv", index=False, encoding="utf-8-sig")
    print(T[["부품코드","부품","작업 등급","국내 같은 부품 사례 수","해외 기반 총액(v2)","예상 총액 중앙","80% 범위 하한","80% 범위 상한","신뢰도"]].to_string())
    for t, v in V.TL.items(): print(t, v["n"], round(v["p25"]), round(v["p50"]), round(v["p75"]))
