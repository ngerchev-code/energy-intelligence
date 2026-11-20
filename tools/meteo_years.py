#!/usr/bin/env python3
"""Same prices (last 365 days, ENTSO-E) combined with five real PVGIS meteorological years for Pleven.
Usage: python tools/meteo_years.py <prices_365.json> <pv_years.json>"""
import json, statistics, sys
import pvbess_reference as R

prices = json.load(open(sys.argv[1])); years = json.load(open(sys.argv[2]))
rows = []
for site in years["sites"]:
    hs = R.hours(prices, site); acc = R.simulate(hs, R.BASE); capex, opex, npv, irr = R.invest(acc, R.BASE)
    rows.append((site["year"], site["kwh_per_kwp_year"], acc["pv"], acc["rev_pv"] + acc["shift"] + acc["arb"], npv, irr))
    print(f"{site['year']}: {site['kwh_per_kwp_year']:.0f} kWh/kWp | PV {acc['pv']:.0f} MWh | revenue {rows[-1][3]:.0f} € | NPV {npv:.0f} € | IRR {irr * 100:.2f} %")
for i, lbl in ((1, "yield kWh/kWp"), (3, "revenue €"), (4, "NPV €")):
    v = [r[i] for r in rows]
    print(f"{lbl}: min {min(v):.0f}, max {max(v):.0f}, mean {statistics.mean(v):.0f}, spread {100 * (max(v) - min(v)) / statistics.mean(v):.1f} %")
