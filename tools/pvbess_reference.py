#!/usr/bin/env python3
"""
Independent Python re-implementation of the app's PV+BESS model (logic/PvBess.kt), used to cross-check the
Kotlin numbers on the same real data. Usage: python tools/pvbess_reference.py <prices_365.json> <pv_profiles.json> [site]
"""
import datetime as dt, json, math, sys
from zoneinfo import ZoneInfo

SOFIA = ZoneInfo("Europe/Sofia")
BASE = dict(pv=10.0, bess_mw=20.0, bess_mwh=40.0, grid=20.0, soc_min=0.10, soc_max=0.90, eta_c=0.94, eta_d=0.94,
            cycles=1.0, min_spread=10.0, curtail_neg=True, grid_charging=True, pv_deg=0.005, bess_deg=0.02,
            capex_pv=550.0, capex_bess=250.0, capex_grid=1_000_000.0, opex_pv=10.0, opex_bess=8.0, life=20,
            disc=0.08, debt=0.60, rate=0.06, loan=10, pf=1.0, vf=1.0, cf=1.0, of=1.0)
EPS = 1e-9


def hours(prices, site):
    out = []
    for t, price in prices["hours"]:
        u = dt.datetime.fromisoformat(t[:-1] + ":00+00:00")
        doy = u.timetuple().tm_yday
        if (u.year % 4 == 0 and (u.year % 100 or u.year % 400 == 0)) and doy >= 60:
            doy -= 1
        idx = (doy - 1) * 24 + u.hour
        kw = site["kw_per_kwp"][idx] if idx < len(site["kw_per_kwp"]) else 0.0
        out.append((u.astimezone(SOFIA).date().toordinal(), price, kw))
    count = {}
    for h in out: count[h[0]] = count.get(h[0], 0) + 1
    keep = set(sorted(d for d, c in count.items() if c >= 23)[-365:])   # complete days, last 365
    return [h for h in out if h[0] in keep]


def sim_day(day, a, acc):
    n = len(day)
    p = [d[1] * a["pf"] for d in day]
    pv = [d[2] * a["pv"] * a["vf"] for d in day]
    pvd = [0.0 if a["curtail_neg"] and p[i] < 0 else min(pv[i], a["grid"]) for i in range(n)]
    free = [pv[i] - pvd[i] for i in range(n)]
    cf, cp, cg, dis = [0.0] * n, [0.0] * n, [0.0] * n, [0.0] * n
    lo, hi = a["soc_min"] * a["bess_mwh"], a["soc_max"] * a["bess_mwh"]
    soc = [lo] * n
    rt = a["eta_c"] * a["eta_d"]
    daily, out = a["cycles"] * (hi - lo), 0.0
    blocked = set()
    if a["bess_mw"] > EPS and hi - lo > EPS:
        while True:
            best = None
            for i in range(n):
                if dis[i] > EPS: continue
                ct = cf[i] + cp[i] + cg[i]
                if a["bess_mw"] - ct <= EPS: continue
                if a["grid_charging"] and p[i] < 0 and a["grid"] - cg[i] > EPS: src = 2
                elif free[i] - cf[i] > EPS: src = 0
                elif pvd[i] - cp[i] > EPS: src = 1
                elif a["grid_charging"] and a["grid"] - cg[i] > EPS: src = 2
                else: continue
                cost = 0.0 if src == 0 else p[i]
                for j in range(i + 1, n):
                    if cf[j] + cp[j] + cg[j] > EPS or (i, j) in blocked: continue
                    if min(a["bess_mw"] - dis[j], a["grid"] - (pvd[j] - cp[j] + dis[j])) <= EPS: continue
                    pr = p[j] - cost / rt
                    if best is None or pr > best[0]: best = (pr, i, j, src)
            if best is None or best[0] < a["min_spread"]: break
            _, i, j, src = best
            ms = max(soc[i:j])
            rem = [free[i] - cf[i], pvd[i] - cp[i], a["grid"] - cg[i]][src]
            room = a["bess_mw"] - (cf[i] + cp[i] + cg[i])
            d = min(a["bess_mw"] - dis[j], a["grid"] - (pvd[j] - cp[j] + dis[j]), min(rem, room) * rt,
                    (hi - ms) * a["eta_d"], (daily - out) * a["eta_d"])
            if d <= 1e-6: blocked.add((i, j)); continue
            ch = d / rt
            [cf, cp, cg][src][i] += ch
            dis[j] += d; out += d / a["eta_d"]
            for t in range(i, j): soc[t] += ch * a["eta_c"]
            if src == 2: acc["dis_grid"] += d; acc["arb"] += d * p[j]
            else: acc["dis_pv"] += d; acc["shift"] += d * p[j]
    for i in range(n):
        sold = pvd[i] - cp[i]
        acc["pv"] += pv[i]; acc["sold"] += sold; acc["rev_pv"] += sold * p[i]; acc["arb"] -= cg[i] * p[i]
        acc["curt"] += free[i] - cf[i]; acc["grid_ch"] += cg[i]; acc["dis"] += dis[i]


def simulate(hs, a):
    acc = dict(pv=0, sold=0, rev_pv=0, shift=0, arb=0, curt=0, grid_ch=0, dis=0, dis_pv=0, dis_grid=0)
    k = 0
    while k < len(hs):
        e = k
        while e < len(hs) and hs[e][0] == hs[k][0]: e += 1
        sim_day(hs[k:e], a, acc); k = e
    return acc


def npv(r, f): return sum(c / (1 + r) ** t for t, c in enumerate(f))


def irr(f):
    lo, hi = -0.99, 2.0
    if npv(lo, f) * npv(hi, f) > 0: return None
    for _ in range(200):
        m = (lo + hi) / 2
        if npv(m, f) * npv(lo, f) > 0: lo = m
        else: hi = m
    return (lo + hi) / 2


def invest(acc, a):
    capex = (a["pv"] * 1000 * a["capex_pv"] + a["bess_mwh"] * 1000 * a["capex_bess"] + a["capex_grid"]) * a["cf"]
    opex = (a["pv"] * 1000 * a["opex_pv"] + a["bess_mw"] * 1000 * a["opex_bess"]) * a["of"]
    flows = [-capex] + [acc["rev_pv"] * (1 - a["pv_deg"]) ** (y - 1) + (acc["shift"] + acc["arb"]) * (1 - a["bess_deg"]) ** (y - 1) - opex
                        for y in range(1, a["life"] + 1)]
    return capex, opex, npv(a["disc"], flows), irr(flows)


if __name__ == "__main__":
    prices = json.load(open(sys.argv[1])); pvp = json.load(open(sys.argv[2]))
    name = sys.argv[3] if len(sys.argv) > 3 else "Плевен"
    site = next(s for s in pvp["sites"] if s["name"] == name)
    hs = hours(prices, site)
    acc = simulate(hs, BASE)
    capex, opex, n, r = invest(acc, BASE)
    print(f"site={name} hours={len(hs)} PV={acc['pv']:.0f} MWh sold={acc['sold']:.0f} curtailed={acc['curt']:.0f} "
          f"grid_charge={acc['grid_ch']:.0f} discharge={acc['dis']:.0f}")
    print(f"revenue: pv_direct={acc['rev_pv']:.0f} shifted={acc['shift']:.0f} arbitrage={acc['arb']:.0f} "
          f"total={acc['rev_pv'] + acc['shift'] + acc['arb']:.0f}")
    print(f"capex={capex:.0f} opex={opex:.0f} NPV={n:.0f} IRR={'%.2f%%' % (r * 100) if r is not None else '-'}")
