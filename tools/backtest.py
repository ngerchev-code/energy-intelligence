#!/usr/bin/env python3
"""
Validation of the PV+BESS estimation methods on real data (docs/VALIDATION_REPORT.md).

1. Rolling out-of-sample backtest: estimate revenue per day from a 90-day training window with
   (a) the static baseline and (b) the hourly simulation, then compare with the revenue the hourly dispatch
   earns on the next 30 days of real day-ahead prices (day-ahead prices are known before delivery).
2. Optimality gap of the greedy daily dispatch vs. a linear-programming optimum (BESS only, scipy HiGHS).

Usage: python tools/backtest.py <prices_365.json> <pv_profiles.json> [site]
"""
import json, math, statistics, sys
import pvbess_reference as R

def static_rev(hs, a):
    y = sum(h[2] * a["pv"] for h in hs); avg = sum(h[1] for h in hs) / len(hs)
    E = (a["soc_max"] - a["soc_min"]) * a["bess_mwh"]; k = math.ceil(E / a["bess_mw"]); b = 0.0
    days = {}
    for h in hs: days.setdefault(h[0], []).append(h[1])
    for d in days.values():
        s = sorted(d); lo = sum(s[:k]) / k; hi = sum(s[-k:]) / k
        b += max(0.0, E * a["eta_d"] * hi - E / a["eta_c"] * lo) * a["cycles"]
    return y * avg + b

def sim_rev(hs, a):
    acc = R.simulate(hs, a); return acc["rev_pv"] + acc["shift"] + acc["arb"]

def days_of(hs):
    out = {}
    for h in hs: out.setdefault(h[0], []).append(h)
    return [out[k] for k in sorted(out)]

def backtest(hs, a, train=90, test=30):
    days = days_of(hs); rows = []
    for s in range(0, len(days) - train - test + 1, test):
        tr = [h for d in days[s:s + train] for h in d]; te = [h for d in days[s + train:s + train + test] for h in d]
        real = sim_rev(te, a) / test
        rows.append(dict(start=s, real=real, static=static_rev(tr, a) / train, sim=sim_rev(tr, a) / train,
                         static_in=static_rev(te, a) / test))
    return rows

def lp_day(prices, a):
    from scipy.optimize import linprog
    n = len(prices); ec, ed = a["eta_c"], a["eta_d"]; P = a["bess_mw"]
    lo, hi = a["soc_min"] * a["bess_mwh"], a["soc_max"] * a["bess_mwh"]
    # vars: c_0..c_{n-1}, d_0..d_{n-1}; maximise sum(p*d - p*c)
    cost = [p for p in prices] + [-p for p in prices]
    A, b = [], []
    for t in range(n):   # lo <= lo + sum_{k<=t}(ec*c_k - d_k/ed) <= hi
        row = [ec if k <= t else 0 for k in range(n)] + [-1 / ed if k <= t else 0 for k in range(n)]
        A.append(row); b.append(hi - lo); A.append([-x for x in row]); b.append(0.0)
    A.append([0] * n + [1 / ed] * n); b.append(a["cycles"] * (hi - lo))     # daily cycle limit
    res = linprog(cost, A_ub=A, b_ub=b, bounds=[(0, P)] * (2 * n), method="highs")
    return -res.fun if res.success else None

def gap(hs, a):
    b = dict(a, pv=0.0, min_spread=0.0)
    g_tot = l_tot = 0.0; worst = 0.0
    for d in days_of(hs):
        acc = dict(pv=0, sold=0, rev_pv=0, shift=0, arb=0, curt=0, grid_ch=0, dis=0, dis_pv=0, dis_grid=0)
        R.sim_day(d, b, acc); g = acc["arb"]; l = lp_day([h[1] for h in d], b)
        if l is None: continue
        g_tot += g; l_tot += l
        if l > 1: worst = max(worst, (l - g) / l)
    return g_tot, l_tot, worst

if __name__ == "__main__":
    prices = json.load(open(sys.argv[1])); pvp = json.load(open(sys.argv[2]))
    name = sys.argv[3] if len(sys.argv) > 3 else "Плевен"
    site = next(s for s in pvp["sites"] if s["name"] == name)
    hs = R.hours(prices, site); a = R.BASE
    rows = backtest(hs, a)
    err = lambda k: [abs(r[k] - r["real"]) / r["real"] for r in rows]
    bias = lambda k: [(r[k] - r["real"]) / r["real"] for r in rows]
    print(f"site={name} days={len(days_of(hs))} folds={len(rows)} (train 90 d -> test 30 d)")
    for r in rows:
        print(f"  fold {r['start']:3d}: real {r['real']:8.0f} €/d | static {r['static']:8.0f} | sim {r['sim']:8.0f} | static on test {r['static_in']:8.0f}")
    for k, lbl in (("static", "static (train)"), ("sim", "simulation (train)"), ("static_in", "static on test data")):
        print(f"MAPE {lbl:22s}: {100 * statistics.mean(err(k)):5.1f} %  | mean bias {100 * statistics.mean(bias(k)):+5.1f} %")
    g, l, w = gap(hs, a)
    print(f"greedy vs LP (BESS only, {len(days_of(hs))} days): greedy {g:.0f} €, LP {l:.0f} €, gap {100 * (l - g) / l:.2f} %, worst day {100 * w:.1f} %")
