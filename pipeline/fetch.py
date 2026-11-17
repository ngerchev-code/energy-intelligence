#!/usr/bin/env python3
"""
Energy Intelligence Bulgaria - data pipeline.

Runs every 15 minutes on GitHub Actions. Downloads public energy data, records where every
number came from, and writes feed/feed.json, feed/news.json and feed/status.json.
The phone app reads those files; no credential ever reaches the app.

Sources (in order of preference):
  - ENTSO-E Transparency Platform (needs ENTSOE_TOKEN secret)
  - Energy-Charts / Fraunhofer ISE (no key; fallback)
  - Open-Meteo (weather forecast + ensemble spread; no key)
  - Public RSS feeds for news
  - Optional: Anthropic API (ANTHROPIC_API_KEY) for Bulgarian news summaries and a daily brief

Standard library only, so the job needs no installs.
"""
from __future__ import annotations

import datetime as dt
import email.utils
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from statistics import mean
from zoneinfo import ZoneInfo

UTC = dt.timezone.utc
SOFIA = ZoneInfo("Europe/Sofia")
NOW = dt.datetime.now(UTC).replace(second=0, microsecond=0)
OUT = os.environ.get("OUT_DIR", "out/feed")
PREV_BASE = os.environ.get("PREV_BASE", "")  # raw URL of the previous feed folder
TOKEN = os.environ.get("ENTSOE_TOKEN", "").strip()
AI_KEY = os.environ.get("ANTHROPIC_API_KEY", "").strip()
UA = "EnergyIntelligenceBulgaria/1.0 (+https://github.com/ngerchev-code/energy-intelligence)"

ENTSOE_URL = "https://web-api.tp.entsoe.eu/api"
EC_URL = "https://api.energy-charts.info"

# ENTSO-E EIC codes of bidding zones / control areas.
ZONES = {
    "BG": ("България", "10YCA-BULGARIA-R", "BG"),
    "RO": ("Румъния", "10YRO-TEL------P", "RO"),
    "GR": ("Гърция", "10YGR-HTSO-----Y", "GR"),
    "RS": ("Сърбия", "10YCS-SERBIATSOV", "RS"),
    "MK": ("Сев. Македония", "10YMK-MEPSO----8", None),
    "ME": ("Черна гора", "10YCS-CG-TSO---S", "ME"),
    "HR": ("Хърватия", "10YHR-HEP------M", "HR"),
    "SI": ("Словения", "10YSI-ELES-----O", "SI"),
    "HU": ("Унгария", "10YHU-MAVIR----U", "HU"),
    "PL": ("Полша", "10YPL-AREA-----S", "PL"),
    "CZ": ("Чехия", "10YCZ-CEPS-----N", "CZ"),
    "SK": ("Словакия", "10YSK-SEPS-----K", "SK"),
    "AT": ("Австрия", "10YAT-APG------L", "AT"),
    "DE": ("Германия", "10Y1001A1001A82H", "DE-LU"),
    "FR": ("Франция", "10YFR-RTE------C", "FR"),
    "IT": ("Италия (Север)", "10Y1001A1001A73I", "IT-North"),
    "ES": ("Испания", "10YES-REE------0", "ES"),
}
# Neighbours with a physical interconnection to Bulgaria.
NEIGHBOURS = {
    "RO": "10YRO-TEL------P",
    "GR": "10YGR-HTSO-----Y",
    "RS": "10YCS-SERBIATSOV",
    "MK": "10YMK-MEPSO----8",
    "TR": "10YTR-TEIAS----W",
}
PSR = {
    "B01": "Biomass", "B02": "Fossil Brown coal/Lignite", "B03": "Fossil Coal-derived gas",
    "B04": "Fossil Gas", "B05": "Fossil Hard coal", "B06": "Fossil Oil", "B07": "Fossil Oil shale",
    "B08": "Fossil Peat", "B09": "Geothermal", "B10": "Hydro Pumped Storage",
    "B11": "Hydro Run-of-river and poundage", "B12": "Hydro Water Reservoir", "B13": "Marine",
    "B14": "Nuclear", "B15": "Other renewable", "B16": "Solar", "B17": "Waste",
    "B18": "Wind Offshore", "B19": "Wind Onshore", "B20": "Other", "B25": "Energy storage",
}

STATUS: dict[str, dict] = {}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def mark(source_id: str, name: str, url: str, ok: bool, msg: str = "", configured: bool = True):
    s = STATUS.setdefault(source_id, {"id": source_id, "name": name, "url": url, "calls": 0, "errors": 0})
    s["calls"] += 1
    if not configured:
        s["status"] = "not_configured"
        s["message"] = msg
        return
    if ok:
        s["last_success"] = iso(NOW)
        s.setdefault("status", "ok")
    else:
        s["errors"] += 1
        s["message"] = msg[:300]
    s["status"] = "ok" if s["errors"] == 0 else ("degraded" if s["errors"] < s["calls"] else "error")


def iso(t: dt.datetime) -> str:
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%MZ")


def http_get(url: str, timeout=40, retries=3, accept="*/*") -> bytes:
    last = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            body = e.read()[:2000]
            last = e
            # ENTSO-E answers 400 with an XML reason when there is simply no data.
            if e.code in (400, 401, 403, 404):
                raise RuntimeError(f"HTTP {e.code}: {_reason(body)}") from e
            if e.code == 429:  # rate limited: wait longer before retrying
                time.sleep(8 * (attempt + 1))
                continue
        except Exception as e:  # network hiccup: retry with backoff
            last = e
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{type(last).__name__}: {last}")


def _reason(body: bytes) -> str:
    try:
        root = ET.fromstring(body)
        txt = [el.text for el in root.iter() if el.tag.split("}")[-1] == "text" and el.text]
        return "; ".join(txt)[:200] or body[:200].decode("utf-8", "replace")
    except Exception:
        return body[:200].decode("utf-8", "replace")


# ---------------------------------------------------------------- ENTSO-E

def _ts(t: dt.datetime) -> str:
    return t.astimezone(UTC).strftime("%Y%m%d%H%M")


def entsoe(params: dict, label: str) -> list[dict]:
    """Returns a list of time series: {psr, in, out, points: [(datetime, value)], resolution}."""
    if not TOKEN:
        mark("entsoe", "ENTSO-E Transparency Platform", "https://transparency.entsoe.eu", False,
             "Липсва ENTSOE_TOKEN в GitHub Secrets", configured=False)
        return []
    q = dict(params, securityToken=TOKEN)
    url = ENTSOE_URL + "?" + urllib.parse.urlencode(q)
    try:
        body = http_get(url, timeout=60)
        series = parse_entsoe(body)
        mark("entsoe", "ENTSO-E Transparency Platform", "https://transparency.entsoe.eu", True)
        log(f"  entsoe {label}: {len(series)} series")
        return series
    except RuntimeError as e:
        msg = str(e)
        if "No matching data" in msg:
            mark("entsoe", "ENTSO-E Transparency Platform", "https://transparency.entsoe.eu", True)
            log(f"  entsoe {label}: no data")
            return []
        mark("entsoe", "ENTSO-E Transparency Platform", "https://transparency.entsoe.eu", False, f"{label}: {msg}")
        log(f"  entsoe {label}: ERROR {msg}")
        return []


_RES = {"PT15M": 15, "PT30M": 30, "PT60M": 60, "P1D": 1440}


def parse_entsoe(body: bytes) -> list[dict]:
    root = ET.fromstring(body)

    def local(el):
        return el.tag.split("}")[-1]

    def child(el, name):
        for c in el:
            if local(c) == name:
                return c
        return None

    if local(root) == "Acknowledgement_MarketDocument":
        raise RuntimeError(_reason(body))
    out = []
    for ts in [e for e in root if local(e) == "TimeSeries"]:
        info = {"psr": None, "in": None, "out": None, "curve": "A01"}
        for c in ts:
            n = local(c)
            if n == "MktPSRType":
                p = child(c, "psrType")
                info["psr"] = p.text if p is not None else None
            elif n in ("in_Domain.mRID", "inBiddingZone_Domain.mRID"):
                info["in"] = c.text
            elif n in ("out_Domain.mRID", "outBiddingZone_Domain.mRID"):
                info["out"] = c.text
            elif n == "curveType":
                info["curve"] = c.text
        points = []
        resolution = None
        for period in [e for e in ts if local(e) == "Period"]:
            ti = child(period, "timeInterval")
            start = dt.datetime.fromisoformat(child(ti, "start").text.replace("Z", "+00:00"))
            end = dt.datetime.fromisoformat(child(ti, "end").text.replace("Z", "+00:00"))
            resolution = child(period, "resolution").text
            step = dt.timedelta(minutes=_RES.get(resolution, 60))
            n_slots = int((end - start) / step)
            vals: dict[int, float] = {}
            for p in [e for e in period if local(e) == "Point"]:
                pos = int(child(p, "position").text)
                v = child(p, "price.amount")
                if v is None:
                    v = child(p, "quantity")
                vals[pos] = float(v.text)
            last = None
            for pos in range(1, n_slots + 1):
                if pos in vals:
                    last = vals[pos]
                elif info["curve"] != "A03":
                    continue  # A01: a missing point is missing data
                if last is not None:
                    points.append((start + step * (pos - 1), last))
        info["points"] = points
        info["resolution"] = resolution
        out.append(info)
    return out


# ---------------------------------------------------------------- Energy-Charts

def ec(path: str, params: dict, label: str) -> dict | None:
    url = f"{EC_URL}{path}?{urllib.parse.urlencode(params)}"
    time.sleep(1.5)  # be polite: Energy-Charts rate-limits bursts
    try:
        data = json.loads(http_get(url, accept="application/json"))
        mark("energycharts", "Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info", True)
        return data
    except Exception as e:
        mark("energycharts", "Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info", False, f"{label}: {e}")
        log(f"  energy-charts {label}: ERROR {e}")
        return None


def ec_series(o: dict) -> tuple[list[dt.datetime], dict[str, list]]:
    """Both v2 ('series'+'data') and v1 ('unix_seconds'+arrays) formats."""
    if isinstance(o.get("data"), list):
        names = {s.get("id"): s.get("name", s.get("id")) for s in o.get("series", [])}
        times, cols = [], {}
        for row in o["data"]:
            t = row["timestamp"]
            times.append(dt.datetime.fromtimestamp(t, UTC) if isinstance(t, (int, float))
                         else dt.datetime.fromisoformat(str(t).replace("Z", "+00:00")))
            for k, v in (row.get("values") or {}).items():
                cols.setdefault(names.get(k, k), [None] * (len(times) - 1)).append(v)
            for col in cols.values():
                if len(col) < len(times):
                    col.append(None)
        unit = o.get("unit", "")
        factor = 1000.0 if "GW" in unit else 1.0
        return times, {k: [None if v is None else v * factor for v in c] for k, c in cols.items()}
    times = [dt.datetime.fromtimestamp(s, UTC) for s in o.get("unix_seconds", [])]
    cols = {}
    if "price" in o:
        cols["price"] = o["price"]
    for key, factor in (("production_types", 1.0), ("countries", 1000.0)):
        for s in o.get(key, []):
            cols[s["name"]] = [None if v is None else v * factor for v in s.get("data", [])]
    return times, cols


# ---------------------------------------------------------------- helpers

def pts(points) -> list:
    return [[iso(t), round(v, 3)] for t, v in sorted(points)]


def day_bounds(days_back: int, days_fwd: int) -> tuple[dt.datetime, dt.datetime]:
    today = NOW.astimezone(SOFIA).replace(hour=0, minute=0)
    return (today - dt.timedelta(days=days_back)).astimezone(UTC), (today + dt.timedelta(days=days_fwd)).astimezone(UTC)


def block(source: str, url: str, kind: str, unit: str, resolution: str | None, area: str, **extra) -> dict:
    """Metadata that travels with every data set."""
    return dict(source=source, source_url=url, kind=kind, unit=unit, resolution=resolution,
                area=area, retrieved_at=iso(NOW), **extra)


# ---------------------------------------------------------------- data sets

def prices(zone: str, days_back: int, days_fwd: int) -> dict | None:
    name, eic, bzn = ZONES[zone]
    start, end = day_bounds(days_back, days_fwd)
    s = entsoe({"documentType": "A44", "in_Domain": eic, "out_Domain": eic,
                "periodStart": _ts(start), "periodEnd": _ts(end)}, f"prices {zone}")
    if s:
        # When both 15-min and 60-min series exist, prefer the finest.
        best: dict[dt.datetime, float] = {}
        res = sorted({x["resolution"] for x in s}, key=lambda r: _RES.get(r, 60))
        for r in reversed(res):
            for x in s:
                if x["resolution"] == r:
                    best.update(dict(x["points"]))
        return block("ENTSO-E", "https://transparency.entsoe.eu", "day-ahead", "EUR/MWh", res[0], zone,
                     points=pts(best.items()), license="ENTSO-E Transparency Platform, reuse with attribution")
    if not bzn:
        return None
    o = ec("/price", {"bzn": bzn, "start": start.strftime("%Y-%m-%d"),
                      "end": (end - dt.timedelta(minutes=1)).strftime("%Y-%m-%d")}, f"price {zone}")
    if not o:
        return None
    times, cols = ec_series(o)
    col = next((c for k, c in cols.items() if "price" in k.lower()), next(iter(cols.values()), []))
    p = [(t, v) for t, v in zip(times, col) if v is not None]
    if not p:
        return None
    step = (p[1][0] - p[0][0]).seconds // 60 if len(p) > 1 else 60
    return block("Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info", "day-ahead", "EUR/MWh",
                 f"PT{step}M", zone, points=pts(p), license=o.get("license") or o.get("license_info"))


def generation_bg() -> dict | None:
    start, end = day_bounds(0, 1)
    s = entsoe({"documentType": "A75", "processType": "A16", "in_Domain": ZONES["BG"][1],
                "periodStart": _ts(start), "periodEnd": _ts(min(end, NOW))}, "generation BG")
    s = [x for x in s if x["in"] and x["points"]]
    if s:
        series = {}
        for x in s:
            nm = PSR.get(x["psr"], x["psr"] or "Other")
            series.setdefault(nm, {})
            series[nm].update(dict(x["points"]))
        return block("ENTSO-E", "https://transparency.entsoe.eu", "actual", "MW", s[0]["resolution"], "BG",
                     series={k: pts(v.items()) for k, v in series.items()})
    o = ec("/public_power", {"country": "bg", "start": start.strftime("%Y-%m-%d")}, "public_power BG")
    if not o:
        return None
    times, cols = ec_series(o)
    skip = ("load", "share", "cross", "import", "export", "residual", "consumption")
    series = {k: pts([(t, v) for t, v in zip(times, c) if v is not None])
              for k, c in cols.items() if not any(w in k.lower() for w in skip)}
    load = next((c for k, c in cols.items() if k.lower().strip() == "load"), None)
    out = block("Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info", "actual", "MW", None, "BG",
                series={k: v for k, v in series.items() if v})
    if load:
        out["load_actual"] = pts([(t, v) for t, v in zip(times, load) if v is not None])
    return out


def load_bg() -> dict | None:
    start, end = day_bounds(1, 2)
    eic = ZONES["BG"][1]
    actual = entsoe({"documentType": "A65", "processType": "A16", "outBiddingZone_Domain": eic,
                     "periodStart": _ts(start), "periodEnd": _ts(min(end, NOW))}, "load actual BG")
    fc = entsoe({"documentType": "A65", "processType": "A01", "outBiddingZone_Domain": eic,
                 "periodStart": _ts(start), "periodEnd": _ts(end)}, "load forecast BG")
    if not actual and not fc:
        return None
    merge = lambda s: pts({t: v for x in s for t, v in x["points"]}.items())
    return block("ENTSO-E", "https://transparency.entsoe.eu", "actual+forecast", "MW",
                 (actual or fc)[0]["resolution"], "BG", actual=merge(actual), forecast=merge(fc))


def res_forecast_bg() -> dict | None:
    start, end = day_bounds(0, 2)
    s = entsoe({"documentType": "A69", "processType": "A01", "in_Domain": ZONES["BG"][1],
                "periodStart": _ts(start), "periodEnd": _ts(end)}, "wind/solar forecast BG")
    if not s:
        return None
    series = {}
    for x in s:
        nm = PSR.get(x["psr"], x["psr"])
        series.setdefault(nm, {}).update(dict(x["points"]))
    return block("ENTSO-E", "https://transparency.entsoe.eu", "forecast", "MW", s[0]["resolution"], "BG",
                 series={k: pts(v.items()) for k, v in series.items()})


def flows_bg() -> dict | None:
    start, _ = day_bounds(0, 1)
    bg = ZONES["BG"][1]
    if TOKEN:
        out = {}
        for code, eic in NEIGHBOURS.items():
            imp = entsoe({"documentType": "A11", "in_Domain": bg, "out_Domain": eic,
                          "periodStart": _ts(start), "periodEnd": _ts(NOW)}, f"flow {code}->BG")
            exp = entsoe({"documentType": "A11", "in_Domain": eic, "out_Domain": bg,
                          "periodStart": _ts(start), "periodEnd": _ts(NOW)}, f"flow BG->{code}")
            net: dict[dt.datetime, float] = {}
            for x in imp:
                for t, v in x["points"]:
                    net[t] = net.get(t, 0) + v
            for x in exp:
                for t, v in x["points"]:
                    net[t] = net.get(t, 0) - v
            if net:
                out[code] = pts(net.items())
        if out:
            return block("ENTSO-E", "https://transparency.entsoe.eu", "physical", "MW", "PT60M", "BG",
                         sign="+ внос / − износ", neighbours=out)
    o = ec("/cbet", {"country": "bg", "start": start.strftime("%Y-%m-%d")}, "cbet BG")
    if not o:
        return None
    times, cols = ec_series(o)
    nb = {k: pts([(t, v) for t, v in zip(times, c) if v is not None]) for k, c in cols.items()
          if not any(w in k.lower() for w in ("sum", "total"))}
    return block("Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info", "commercial", "MW", None,
                 "BG", sign="+ внос / − износ", neighbours={k: v for k, v in nb.items() if v})


# ---------------------------------------------------------------- weather

WX_POINTS = [("София", 42.70, 23.32), ("Плевен", 43.42, 24.62), ("Пловдив", 42.15, 24.75),
             ("Стара Загора", 42.43, 25.64), ("Варна", 43.21, 27.92), ("Каварна", 43.43, 28.34)]


def weather() -> dict | None:
    lat = ",".join(str(p[1]) for p in WX_POINTS)
    lon = ",".join(str(p[2]) for p in WX_POINTS)
    hourly = "temperature_2m,shortwave_radiation,cloud_cover,wind_speed_100m,wind_direction_100m,precipitation"
    url = (f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&hourly={hourly}"
           f"&timezone=UTC&forecast_days=3&past_days=0")
    try:
        data = json.loads(http_get(url))
        mark("openmeteo", "Open-Meteo", "https://open-meteo.com", True)
    except Exception as e:
        mark("openmeteo", "Open-Meteo", "https://open-meteo.com", False, str(e))
        return None
    if isinstance(data, dict):
        data = [data]
    points = []
    for (name, la, lo), d in zip(WX_POINTS, data):
        h = d["hourly"]
        points.append({"name": name, "lat": la, "lon": lo, "time": [t + "Z" for t in h["time"]],
                       "temp": h["temperature_2m"], "rad": h["shortwave_radiation"], "cloud": h["cloud_cover"],
                       "wind": h["wind_speed_100m"], "wdir": h["wind_direction_100m"], "precip": h["precipitation"]})
    # Uncertainty: spread of the ICON ensemble members (10th-90th percentile).
    try:
        eurl = (f"https://ensemble-api.open-meteo.com/v1/ensemble?latitude={lat}&longitude={lon}"
                f"&hourly=shortwave_radiation,wind_speed_10m&models=icon_seamless&timezone=UTC&forecast_days=3")
        edata = json.loads(http_get(eurl))
        if isinstance(edata, dict):
            edata = [edata]
        for p, e in zip(points, edata):
            h = e["hourly"]
            for var, key in (("shortwave_radiation", "rad"), ("wind_speed_10m", "wind10")):
                members = [v for k, v in h.items() if k.startswith(var)]
                if not members:
                    continue
                lo_, hi_ = [], []
                for i in range(len(h["time"])):
                    vals = sorted(m[i] for m in members if m[i] is not None)
                    if not vals:
                        lo_.append(None); hi_.append(None); continue
                    lo_.append(round(vals[int(0.1 * (len(vals) - 1))], 1))
                    hi_.append(round(vals[int(0.9 * (len(vals) - 1))], 1))
                p[key + "_p10"], p[key + "_p90"] = lo_, hi_
            p["ens_time"] = [t + "Z" for t in h["time"]]
            p["ens_members"] = len([k for k in h if k.startswith("shortwave_radiation")])
        mark("openmeteo_ens", "Open-Meteo Ensemble (ICON)", "https://open-meteo.com/en/docs/ensemble-api", True)
    except Exception as e:
        mark("openmeteo_ens", "Open-Meteo Ensemble (ICON)", "https://open-meteo.com/en/docs/ensemble-api", False, str(e))
    return block("Open-Meteo", "https://open-meteo.com", "forecast", "°C, W/m², %, km/h, mm", "PT60M", "BG",
                 points=points, license="CC BY 4.0, open-meteo.com")


# ---------------------------------------------------------------- news

FEEDS = [
    ("ec_energy", "European Commission – Energy", "https://energy.ec.europa.eu/node/2/rss_en", "official"),
    ("doe", "US Department of Energy", "https://www.energy.gov/rss/newsroom.xml", "official"),
    ("sciencedaily", "ScienceDaily – Energy", "https://www.sciencedaily.com/rss/matter_energy.xml", "research"),
    ("phys_energy", "Phys.org – Energy & Green Tech", "https://phys.org/rss-feed/technology-news/energy-green-tech/", "research"),
    ("ess_news", "Energy-Storage.news", "https://www.energy-storage.news/feed/", "industry"),
    ("pvmag", "pv magazine", "https://www.pv-magazine.com/feed/", "industry"),
]

CATS = [  # (key, Bulgarian label, keywords)
    ("fusion", "Ядрен синтез", ["fusion", "tokamak", "stellarator", "iter", "plasma"]),
    ("nuclear", "Ядрена енергетика", ["nuclear", "reactor", "smr", "uranium", "fission"]),
    ("hydrogen", "Водород", ["hydrogen", "electrolys", "fuel cell", "ammonia"]),
    ("batteries", "Батерии", ["battery", "batteries", "storage", "bess", "lithium", "sodium-ion", "solid-state"]),
    ("solar", "Слънчеви технологии", ["solar", "photovoltaic", "perovskite", " pv "]),
    ("wind", "Вятърни технологии", ["wind", "turbine", "offshore"]),
    ("geothermal", "Геотермална енергия", ["geothermal"]),
    ("gas", "Газ и LNG", ["lng", "natural gas", "gas pipeline", "methane"]),
    ("water", "Водни технологии", ["hydropower", "pumped hydro", "water", "desalination"]),
    ("ai", "AI и автоматизация", [" ai ", "artificial intelligence", "machine learning", "data center", "automation"]),
    ("grid", "Интелигентни мрежи", ["grid", "transmission", "interconnector", "smart meter", "flexibility"]),
    ("decarb", "Декарбонизация", ["carbon", "emission", "decarboni", "net zero", "climate"]),
    ("invest", "Инвестиции в енергийни технологии", ["invest", "funding", "billion", "million", "financ"]),
    ("electricity", "Електроенергия", ["electricity", "power market", "power price"]),
]
TYPES = [
    ("Патент", ["patent"]),
    ("Научен пробив", ["breakthrough", "record", "first ever", "world's first"]),
    ("Пилотен проект", ["pilot", "demonstration", "demonstrator", "prototype"]),
    ("Инвестиция", ["invest", "funding", "raises", "billion", "financ"]),
    ("Нов производствен капацитет", ["factory", "gigafactory", "plant opens", "manufacturing", "commissioned"]),
    ("Регулаторна промяна", ["regulation", "directive", "law", "policy", "commission adopts", "auction rules"]),
    ("Изследване", ["study", "research", "researchers", "scientists", "university"]),
    ("Технологична прогноза", ["outlook", "forecast", "projection", "scenario"]),
    ("Индустриално приложение", ["deploy", "installed", "contract", "order", "supply agreement"]),
]
MATURITY = [  # strongest signal first
    ("Изследване", ["researchers", "study", "theoretical", "simulation", "scientists"]),
    ("Лабораторен прототип", ["laboratory", "lab-scale", "prototype", "proof of concept", "cell efficiency"]),
    ("Пилотна демонстрация", ["pilot", "demonstration", "demonstrator", "first-of-a-kind", "trial"]),
    ("Търговско внедряване", ["commercial", "commissioned", "deploy", "operational", "gigafactory", "order"]),
]
COUNTRIES = {"Bulgaria": "България", "Romania": "Румъния", "Greece": "Гърция", "Turkey": "Турция",
             "Serbia": "Сърбия", "Germany": "Германия", "France": "Франция", "Italy": "Италия", "Spain": "Испания",
             "Poland": "Полша", "Austria": "Австрия", "Hungary": "Унгария", "United States": "САЩ",
             " US ": "САЩ", "China": "Китай", "Japan": "Япония", "Korea": "Южна Корея", "India": "Индия",
             "United Kingdom": "Великобритания", " UK ": "Великобритания", "Australia": "Австралия",
             "Netherlands": "Нидерландия", "Denmark": "Дания", "Norway": "Норвегия", "Sweden": "Швеция",
             "EU ": "ЕС", "European": "ЕС"}


def strip_html(s: str) -> str:
    s = re.sub(r"<[^>]+>", " ", s or "")
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


def parse_feed(body: bytes) -> list[dict]:
    root = ET.fromstring(body)
    items = []
    loc = lambda e: e.tag.split("}")[-1]
    for it in root.iter():
        if loc(it) not in ("item", "entry"):
            continue
        d = {}
        for c in it:
            n = loc(c)
            if n == "title":
                d["title"] = strip_html(c.text or "")
            elif n == "link":
                d["link"] = (c.get("href") or c.text or "").strip() or d.get("link")
            elif n in ("pubDate", "published", "updated", "date") and "date" not in d:
                d["date"] = (c.text or "").strip()
            elif n in ("description", "summary") and "summary" not in d:
                d["summary"] = strip_html(c.text or "")
        if d.get("title") and d.get("link"):
            items.append(d)
    return items


def parse_date(s: str) -> dt.datetime | None:
    if not s:
        return None
    try:
        t = email.utils.parsedate_to_datetime(s)
    except Exception:
        try:
            t = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
        except Exception:
            return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=UTC)
    return t


def first_match(text: str, rules) -> str | None:
    for label, words in rules:
        if any(w in text for w in words):
            return label
    return None


def classify(title: str, summary: str) -> dict:
    t = f" {title} {summary} ".lower()
    cats = [label for key, label, words in CATS if any(w in t for w in words)]
    raw = f" {title} {summary} "
    countries = sorted({bg for en, bg in COUNTRIES.items() if en in raw})
    if any(w in t for w in ("bulgaria", "balkan", "south-east europe", "southeast europe", "black sea")):
        rel = "висока"
    elif any(c in countries for c in ("Румъния", "Гърция", "Турция", "Сърбия", "ЕС")):
        rel = "средна"
    else:
        rel = "косвена"
    return {
        "categories": cats[:3] or ["Електроенергия"],
        "type": first_match(t, TYPES) or "Нова технология",
        "maturity": first_match(t, MATURITY) or "Не е определена",
        "countries": countries,
        "bg_relevance": rel,
        "classified_by": "автоматично (ключови думи)",
    }


def news(prev: list[dict]) -> list[dict]:
    seen = {}
    for it in prev:  # keep earlier items and their AI summaries
        seen[it["id"]] = it
    cutoff = NOW - dt.timedelta(days=30)
    for fid, fname, url, tier in FEEDS:
        try:
            items = parse_feed(http_get(url, timeout=30, retries=1, accept="application/rss+xml, application/atom+xml, */*"))
            mark("rss_" + fid, fname, url, True)
            log(f"  rss {fid}: {len(items)} items")
        except Exception as e:
            mark("rss_" + fid, fname, url, False, str(e))
            log(f"  rss {fid}: ERROR {e}")
            continue
        for d in items[:40]:
            pub = parse_date(d.get("date", ""))
            if pub is None or pub < cutoff or pub > NOW + dt.timedelta(days=1):
                continue  # publication date must be valid
            norm = re.sub(r"[^a-z0-9]", "", d["title"].lower())[:80]
            key = hashlib.sha1(norm.encode()).hexdigest()[:12]
            if key in seen:
                continue  # duplicate (same story from another feed)
            summary = d.get("summary", "")
            if len(summary) > 280:
                summary = summary[:277].rsplit(" ", 1)[0] + "…"
            seen[key] = {
                "id": key, "title": d["title"], "summary_original": summary, "link": d["link"],
                "source": fname, "source_tier": tier, "published": iso(pub), "retrieved": iso(NOW),
                "language": "en", **classify(d["title"], summary),
                "summary_bg": None, "impact_bg": None, "verification": "оригинален източник, непроверено от редактор",
            }
    items = [v for v in seen.values() if parse_date(v["published"]) and parse_date(v["published"]) >= cutoff]
    items.sort(key=lambda x: x["published"], reverse=True)
    items = items[:180]
    if AI_KEY:
        ai_summaries(items)
    return items


# ---------------------------------------------------------------- optional AI

def claude(prompt: str, max_tokens=900) -> str | None:
    body = json.dumps({"model": os.environ.get("AI_MODEL", "claude-haiku-5-5"), "max_tokens": max_tokens,
                       "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=body, headers={
        "x-api-key": AI_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json", "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            out = json.loads(r.read())
        mark("anthropic", "Anthropic API (AI обобщения)", "https://docs.claude.com", True)
        return "".join(b.get("text", "") for b in out.get("content", []))
    except Exception as e:
        mark("anthropic", "Anthropic API (AI обобщения)", "https://docs.claude.com", False, str(e))
        return None


def ai_summaries(items: list[dict]):
    todo = [x for x in items if not x.get("summary_bg")][:15]
    if not todo:
        return
    payload = [{"id": x["id"], "title": x["title"], "summary": x["summary_original"]} for x in todo]
    prompt = (
        "За всяка новина по-долу напиши на български: 'summary' – 1–2 изречения само по фактите в заглавието и "
        "резюмето (не добавяй числа, имена или твърдения, които ги няма), и 'impact' – едно изречение за възможното "
        "значение за българския енергиен пазар, започващо с 'Възможно е'. Ако няма достатъчно информация, impact = "
        "null. Върни само JSON масив [{\"id\",\"summary\",\"impact\"}].\n\n" + json.dumps(payload, ensure_ascii=False))
    txt = claude(prompt, 2500)
    if not txt:
        return
    try:
        arr = json.loads(txt[txt.index("["): txt.rindex("]") + 1])
    except Exception:
        return
    by_id = {x["id"]: x for x in todo}
    for a in arr:
        x = by_id.get(a.get("id"))
        if x and a.get("summary"):
            x["summary_bg"] = a["summary"]
            x["impact_bg"] = a.get("impact")
            x["summary_by"] = "AI (Claude) по заглавието и резюмето на източника"


def ai_brief(feed: dict) -> dict | None:
    if not AI_KEY:
        return None
    facts = feed.get("facts")
    if not facts:
        return None
    prompt = (
        "Ти си енергиен анализатор. По-долу са ИЗМЕРЕНИ данни за пазара в България (с източник и час). Напиши кратък "
        "анализ на български в JSON {\"facts\": [..], \"inference\": [..], \"uncertainty\": \"..\"}. 'facts' – 3–5 "
        "изречения, които само преразказват числата от данните. 'inference' – 2–3 изречения с възможни причини, "
        "всяко започващо с 'Вероятно' или 'Възможно'. Не измисляй числа, които ги няма в данните.\n\n"
        + json.dumps(facts, ensure_ascii=False))
    txt = claude(prompt, 900)
    if not txt:
        return None
    try:
        o = json.loads(txt[txt.index("{"): txt.rindex("}") + 1])
        o["generated_at"] = iso(NOW)
        o["model"] = os.environ.get("AI_MODEL", "claude-haiku-5-5")
        o["based_on"] = "facts в feed.json към " + iso(NOW)
        return o
    except Exception:
        return None


# ---------------------------------------------------------------- summaries

def sofia_day(t: str) -> str:
    return dt.datetime.fromisoformat(t.replace("Z", "+00:00")).astimezone(SOFIA).strftime("%Y-%m-%d")


def daily_avgs(points: list) -> list:
    by_day: dict[str, list] = {}
    for t, v in points:
        d = sofia_day(t)
        by_day.setdefault(d, []).append(v)
    return [[d, round(mean(vs), 2)] for d, vs in sorted(by_day.items()) if len(vs) >= 20]


def day_stats(points: list, day: str) -> dict | None:
    vals = [v for t, v in points
            if sofia_day(t) == day]
    if not vals:
        return None
    return {"avg": round(mean(vals), 2), "min": min(vals), "max": max(vals), "n": len(vals)}


def last_value(points: list):
    return points[-1] if points else None


def load_prev(name: str):
    if not PREV_BASE:
        return None
    try:
        return json.loads(http_get(f"{PREV_BASE}/{name}", timeout=20, retries=1))
    except Exception:
        return None


def main():
    os.makedirs(OUT, exist_ok=True)
    log(f"run {iso(NOW)} token={'yes' if TOKEN else 'no'} ai={'yes' if AI_KEY else 'no'}")
    prev = load_prev("feed.json") or {}

    feed = {"schema": 1, "generated_at": iso(NOW), "timezone": "Europe/Sofia", "currency": "EUR"}
    bg = {}
    bg["prices"] = prices("BG", 31, 2)
    # Year of daily averages: refreshed once a day, otherwise carried over.
    prev_year = (prev.get("bg") or {}).get("daily_365")
    if prev_year and prev_year.get("retrieved_at", "")[:10] == iso(NOW)[:10]:
        bg["daily_365"] = prev_year
    else:
        y = prices("BG", 365, 1)
        if y:
            bg["daily_365"] = {k: v for k, v in y.items() if k != "points"} | {"days": daily_avgs(y["points"]),
                                                                              "kind": "historical"}
    bg["generation"] = generation_bg()
    bg["load"] = load_bg()
    bg["res_forecast"] = res_forecast_bg()
    bg["flows"] = flows_bg()
    feed["bg"] = bg

    # European comparison: today + tomorrow at native resolution, plus 30-day daily averages.
    eu = {}
    for z in ZONES:
        p = bg["prices"] if z == "BG" else prices(z, 30, 2)
        if not p:
            continue
        sofia_today = NOW.astimezone(SOFIA).strftime("%Y-%m-%d")
        tomorrow = (NOW.astimezone(SOFIA) + dt.timedelta(days=1)).strftime("%Y-%m-%d")
        eu[z] = {"name": ZONES[z][0], "source": p["source"], "resolution": p["resolution"],
                 "today": day_stats(p["points"], sofia_today), "tomorrow": day_stats(p["points"], tomorrow),
                 "daily": daily_avgs(p["points"]), "license": p.get("license")}
    feed["europe"] = {"retrieved_at": iso(NOW), "zones": eu,
                      "note": "Средни цени ден напред по зони; Турция не е в ENTSO-E (EPİAŞ), Албания – при наличие."}
    feed["weather"] = weather()

    # Compact facts for reports and the AI analyst (all numbers come from the data above).
    facts = {"generated_at": iso(NOW)}
    sofia_today = NOW.astimezone(SOFIA).strftime("%Y-%m-%d")
    yesterday = (NOW.astimezone(SOFIA) - dt.timedelta(days=1)).strftime("%Y-%m-%d")
    if bg.get("prices"):
        facts["bg_price_today"] = day_stats(bg["prices"]["points"], sofia_today)
        facts["bg_price_yesterday"] = day_stats(bg["prices"]["points"], yesterday)
        facts["bg_price_source"] = bg["prices"]["source"]
    if bg.get("load") and bg["load"].get("actual"):
        facts["bg_load_last"] = last_value(bg["load"]["actual"])
    if bg.get("generation"):
        gen_last = {k: last_value(v) for k, v in bg["generation"]["series"].items() if v}
        facts["bg_generation_last"] = gen_last
    if bg.get("flows"):
        facts["bg_net_flow_last"] = {k: last_value(v) for k, v in bg["flows"]["neighbours"].items()}
        facts["bg_flow_kind"] = bg["flows"]["kind"]
    if eu:
        ranked = sorted(((z, v["today"]["avg"]) for z, v in eu.items() if v.get("today")), key=lambda x: x[1])
        facts["europe_today_avg_sorted"] = ranked
    feed["facts"] = facts
    feed["ai_brief"] = ai_brief(feed) or (prev.get("ai_brief") if prev.get("ai_brief", {}).get(
        "generated_at", "")[:10] == iso(NOW)[:10] else None)

    prev_news = load_prev("news.json") or {}
    items = news(prev_news.get("items", []))

    feed["sources"] = list(STATUS.values())
    status = {"generated_at": iso(NOW), "entsoe_configured": bool(TOKEN), "ai_configured": bool(AI_KEY),
              "sources": list(STATUS.values())}

    with open(f"{OUT}/feed.json", "w", encoding="utf-8") as f:
        json.dump(feed, f, ensure_ascii=False, separators=(",", ":"))
    with open(f"{OUT}/news.json", "w", encoding="utf-8") as f:
        json.dump({"generated_at": iso(NOW), "items": items}, f, ensure_ascii=False, separators=(",", ":"))
    with open(f"{OUT}/status.json", "w", encoding="utf-8") as f:
        json.dump(status, f, ensure_ascii=False, indent=1)
    log(json.dumps(status, ensure_ascii=False, indent=1))
    if not feed["bg"].get("prices"):
        log("WARNING: no Bulgarian prices from any source")


if __name__ == "__main__":
    main()
