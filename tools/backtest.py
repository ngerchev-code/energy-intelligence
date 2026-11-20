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

def trend_scaled(tr, test_days):
    """Price model: linear trend of daily mean price over the training window, extrapolated to the middle of the
    test window; training prices are scaled to that level (hourly shape and PV kept)."""
    days = days_of(tr); n = len(days)
    ys = [sum(h[1] for h in d) / len(d) for d in days]; xs = list(range(n))
    mx, my = sum(xs) / n, sum(ys) / n
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    level = my + b * (n - 1 + test_days / 2 - mx)
    f = level / my if my else 1.0
    return [(h[0], h[1] * f, h[2]) for h in tr]

def backtest(hs, a, train=90, test=30):
    days = days_of(hs); rows = []
    for s in range(0, len(days) - train - test + 1, test):
        tr = [h for d in days[s:s + train] for h in d]; te = [h for d in days[s + train:s + train + test] for h in d]
        real = sim_rev(te, a) / test
        rows.append(dict(start=s, real=real, static=static_rev(tr, a) / train, sim=sim_rev(tr, a) / train,
                         sim_trend=sim_rev(trend_scaled(tr, test), a) / train,
                         static_trend=static_rev(trend_scaled(tr, test), a) / train,
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

def lp_pv_day(day, a):
    """LP upper bound for one day of PV+BESS: per hour sold PV s, PV->battery cp, grid->battery cg, discharge d,
    curtailed k. s+cp+k = pv; s+d <= grid; cg <= grid; cp+cg <= P; d <= P; SoC window; daily cycle limit;
    s = 0 at negative prices when curtailment is on. Max sum p*(s + d - cg)."""
    from scipy.optimize import linprog
    n = len(day); P = a["bess_mw"]; G = a["grid"]; ec, ed = a["eta_c"], a["eta_d"]
    lo, hi = a["soc_min"] * a["bess_mwh"], a["soc_max"] * a["bess_mwh"]
    p = [h[1] for h in day]; pv = [h[2] * a["pv"] for h in day]
    V = 5 * n; S, CP, CG, D, K = 0, n, 2 * n, 3 * n, 4 * n
    c = [0.0] * V
    for t in range(n): c[S + t] = -p[t]; c[D + t] = -p[t]; c[CG + t] = p[t]
    Aeq, beq, A, b = [], [], [], []
    for t in range(n):
        r = [0.0] * V; r[S + t] = r[CP + t] = r[K + t] = 1; Aeq.append(r); beq.append(pv[t])
        r = [0.0] * V; r[S + t] = r[D + t] = 1; A.append(r); b.append(G)
        r = [0.0] * V; r[CP + t] = r[CG + t] = 1; A.append(r); b.append(P)
        r = [0.0] * V
        for k in range(t + 1): r[CP + k] = r[CG + k] = ec; r[D + k] = -1 / ed
        A.append(r); b.append(hi - lo); A.append([-x for x in r]); b.append(0.0)
    r = [0.0] * V
    for t in range(n): r[D + t] = 1 / ed
    A.append(r); b.append(a["cycles"] * (hi - lo))
    bounds = []
    for blk in range(5):
        for t in range(n):
            if blk == 0 and a["curtail_neg"] and p[t] < 0: bounds.append((0, 0))
            elif blk in (2,): bounds.append((0, G if a["grid_charging"] else 0))
            elif blk in (1, 3): bounds.append((0, P))
            else: bounds.append((0, None))
    res = linprog(c, A_ub=A, b_ub=b, A_eq=Aeq, b_eq=beq, bounds=bounds, method="highs")
    return -res.fun if res.success else None

def gap_pv(hs, a):
    b = dict(a, min_spread=0.0); g_tot = l_tot = 0.0; worst = 0.0
    for d in days_of(hs):
        acc = dict(pv=0, sold=0, rev_pv=0, shift=0, arb=0, curt=0, grid_ch=0, dis=0, dis_pv=0, dis_grid=0)
        R.sim_day(d, b, acc); g = acc["rev_pv"] + acc["shift"] + acc["arb"]; l = lp_pv_day(d, b)
        if l is None: continue
        g_tot += g; l_tot += l
        if l > 1: worst = max(worst, (l - g) / l)
    return g_tot, l_tot, worst

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
    for k, lbl in (("static", "static (train)"), ("sim", "simulation (train)"), ("static_trend", "static + price trend"),
                   ("sim_trend", "simulation + price trend"), ("static_in", "static on test data")):
        print(f"MAPE {lbl:22s}: {100 * statistics.mean(err(k)):5.1f} %  | mean bias {100 * statistics.mean(bias(k)):+5.1f} %")
    g, l, w = gap(hs, a)
    g2, l2, w2 = gap_pv(hs, a)
    print(f"greedy vs LP (PV+BESS, grid {a['grid']} MW): greedy {g2:.0f} €, LP {l2:.0f} €, gap {100 * (l2 - g2) / l2:.2f} %, worst day {100 * w2:.1f} %")
    g3, l3, w3 = gap_pv(hs, dict(a, grid=10.0))
    print(f"greedy vs LP (PV+BESS, grid 10 MW): greedy {g3:.0f} €, LP {l3:.0f} €, gap {100 * (l3 - g3) / l3:.2f} %, worst day {100 * w3:.1f} %")
    print(f"greedy vs LP (BESS only, {len(days_of(hs))} days): greedy {g:.0f} €, LP {l:.0f} €, gap {100 * (l - g) / l:.2f} %, worst day {100 * w:.1f} %")
