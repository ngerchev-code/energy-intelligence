"""
„Самоучител“ – weekly lesson, published every Sunday (Europe/Sofia).

Built only from the real data in the feed (ENTSO-E prices, Europe comparison, news). Every number in a lesson is
computed here; explanations are fixed teaching texts. Nothing is invented and nothing is forecast.
"""
from __future__ import annotations

import datetime as dt
from statistics import mean
from zoneinfo import ZoneInfo

SOFIA = ZoneInfo("Europe/Sofia")

# One concept per week, in order. Each: title, explanation, where to see it in the app.
CONCEPTS = [
    ("Пазарът „ден напред“",
     "Всеки ден до обяд производители и купувачи подават оферти за всеки 15-минутен интервал на следващия ден. "
     "Борсите в Европа изчисляват цените заедно (SDAC), като отчитат капацитета на връзките между държавите. "
     "Резултатът излиза около 13–14 ч. българско време.",
     "„Енергиен пазар“ → „Утре“ се появява след публикуването."),
    ("Маргинална цена",
     "Цената за даден интервал е цената на последната, най-скъпа оферта, нужна за покриване на търсенето. "
     "Затова при малко слънце и вятър по-скъпите централи (газ, въглища) определят цената за всички.",
     "Сравнете „Производство“ (дял ВЕИ) с цената в същия час."),
    ("Ценовият спред",
     "Спредът е разликата между най-скъпите и най-евтините часове на деня. Той е „суровината“ на батериите: "
     "колкото е по-голям, толкова повече печели арбитражът. Загубите (~10–12 % за кръг) трябва да се покрият първо.",
     "„Енергиен пазар“ → „Спред 2 ч.“."),
    ("Отрицателни цени",
     "Когато предлагането надвишава търсенето, а част от централите не могат или нямат стимул да спрат, цената пада "
     "под нула. Тогава производителят плаща, за да подава енергия, а батерията получава пари, за да се зарежда.",
     "„Енергиен пазар“ → „Отриц. часове“ (в синьо)."),
    ("„Патешката крива“",
     "При много фотоволтаици обедните цени падат, а вечер, когато слънцето залезе и потреблението остане високо, "
     "цената рязко се качва. Профилът на цената през деня заприличва на гръб на патица.",
     "Почасовата графика в „Енергиен пазар“: дъно по обед, пик вечер."),
    ("Пазарно обединение и съседите",
     "Когато връзките между две зони не са претоварени, пазарът изравнява цените им. Голяма разлика означава "
     "изчерпан капацитет на връзката.",
     "„Европа и Балкани“ → спред към България."),
    ("Улавяне на цената от PV (capture rate)",
     "Слънчевата централа не продава по средната цена, а по цената в часовете, когато произвежда. Отношението "
     "между двете е „уловената цена“. Колкото повече PV в системата, толкова по-ниско става то.",
     "„PV + BESS инвестиция“ → „Уловена цена на PV“."),
    ("Заряд (SoC) и цикли на батерията",
     "SoC е колко е заредена батерията. Работи се между минимум и максимум (напр. 10–90 %), за да се пази "
     "клетката. Един цикъл е едно пълно зареждане и разреждане на използваемия капацитет.",
     "„PV + BESS инвестиция“ → „Батерия“ и „Еквивалентни цикли“."),
    ("NPV – нетна настояща стойност",
     "NPV събира всички бъдещи парични потоци, като всеки е намален с дисконтовия процент за годините до него, и "
     "изважда инвестицията. Положително NPV значи, че проектът носи повече от изискваната доходност.",
     "„PV + BESS инвестиция“ → резултатът горе."),
    ("IRR – вътрешна норма на възвръщаемост",
     "IRR е дисконтовият процент, при който NPV става нула. Сравнява се с цената на капитала: ако IRR е по-висок, "
     "проектът създава стойност.",
     "„PV + BESS инвестиция“ → IRR на проекта и на собствения капитал."),
    ("DSCR – покритие на дълга",
     "DSCR е паричният поток за годината, разделен на вноските по кредита. Банките обикновено искат поне 1,2–1,3, "
     "за да има запас при лоша година.",
     "„PV + BESS инвестиция“ → „Анализ на резултата“."),
    ("Деградация",
     "Панелите губят малко мощност всяка година (обикновено под 1 %), а батериите – капацитет (около 1–3 % годишно "
     "според използването). Това намалява приходите в следващите години.",
     "„PV + BESS инвестиция“ → „Деградация BESS/PV“."),
    ("Чувствителност и сценарии",
     "Анализът на чувствителността показва кой параметър мени резултата най-много. Сценариите съчетават няколко "
     "промени наведнъж (песимистичен, базов, оптимистичен).",
     "„PV + BESS инвестиция“ → „Чувствителност на NPV“ и „Сценарии“."),
    ("Физически и търговски потоци",
     "Търговските графици са договорената размяна между държавите; физическите потоци са реалният ток по линиите. "
     "Те се различават, защото токът тече по пътя на най-малкото съпротивление.",
     "„Пренос и търговия“."),
]


def _iso(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%MZ")


def _local(t: str) -> dt.datetime:
    return dt.datetime.fromisoformat(t.replace("Z", "+00:00")).astimezone(SOFIA)


def week_of(now: dt.datetime) -> tuple[dt.date, dt.date, str]:
    """The Monday–Sunday week that ends on this Sunday (or the last one)."""
    d = now.astimezone(SOFIA).date()
    sunday = d - dt.timedelta(days=(d.weekday() + 1) % 7)
    monday = sunday - dt.timedelta(days=6)
    y, w, _ = monday.isocalendar()
    return monday, sunday, f"{y}-W{w:02d}"


def _bess_day(prices: list[float], mwh: float = 2.0, mw: float = 1.0, eff: float = 0.88) -> float:
    """Worked example: one cycle, buy the cheapest hours, sell the dearest later hours (as in the app)."""
    h = int(round(mwh / mw))
    best = 0.0
    for split in range(h, len(prices) - h + 1):
        buy = sorted(prices[:split])[:h]
        sell = sorted(prices[split:], reverse=True)[:h]
        best = max(best, sum(sell) * mw * eff - sum(buy) * mw)
    return best


def build_lesson(feed: dict, news: list[dict], now: dt.datetime, number: int, ref: dt.datetime | None = None) -> dict | None:
    monday, sunday, key = week_of(ref or now)
    pts = (feed.get("bg") or {}).get("prices", {}).get("points") or []
    src = (feed.get("bg") or {}).get("prices", {}).get("source", "")
    days: dict[dt.date, dict[int, list[float]]] = {}
    for t, v in pts:
        lt = _local(t)
        days.setdefault(lt.date(), {}).setdefault(lt.hour, []).append(v)

    def hourly(d):
        return [mean(days[d][h]) for h in sorted(days[d])] if d in days else []

    week = [monday + dt.timedelta(days=i) for i in range(7)]
    prev = [monday - dt.timedelta(days=7 - i) for i in range(7)]
    wk = {d: hourly(d) for d in week if len(hourly(d)) >= 23}
    pw = {d: hourly(d) for d in prev if len(hourly(d)) >= 23}
    if len(wk) < 5:
        return None
    avg = mean(v for h in wk.values() for v in h)
    pavg = mean(v for h in pw.values() for v in h) if pw else None
    day_avg = {d: mean(h) for d, h in wk.items()}
    dmin, dmax = min(day_avg, key=day_avg.get), max(day_avg, key=day_avg.get)
    profile = [mean(h[i] for h in wk.values() if len(h) > i) for i in range(23)]  # common hours
    trough, peak = profile.index(min(profile)), profile.index(max(profile))
    neg = sum(1 for h in wk.values() for v in h if v < 0)
    spreads = {d: (mean(sorted(h)[-2:]) - mean(sorted(h)[:2])) for d, h in wk.items()}
    best_day = max(spreads, key=spreads.get)
    bess = _bess_day(wk[best_day])
    names = ["понеделник", "вторник", "сряда", "четвъртък", "петък", "събота", "неделя"]
    dn = lambda d: f"{names[d.weekday()]} {d.strftime('%d.%m')}"

    facts = [
        f"Средна цена ден напред в България: {avg:.1f} €/MWh" +
        (f" ({'+' if avg >= pavg else '−'}{abs(avg - pavg):.1f} €/MWh спрямо предходната седмица)." if pavg else "."),
        f"Най-евтин ден: {dn(dmin)} ({day_avg[dmin]:.1f} €/MWh); най-скъп: {dn(dmax)} ({day_avg[dmax]:.1f} €/MWh).",
        f"Средно най-евтино около {trough:02d}:00 ({profile[trough]:.1f} €/MWh), най-скъпо около {peak:02d}:00 "
        f"({profile[peak]:.1f} €/MWh).",
        f"Часове с отрицателна цена: {neg}." if neg else "Нямаше часове с отрицателна цена.",
    ]
    eu = (feed.get("europe") or {}).get("zones") or {}
    ranks = []
    for z, d in eu.items():
        vals = [v for day, v in d.get("daily", []) if monday.isoformat() <= day <= sunday.isoformat()]
        if len(vals) >= 5:
            ranks.append((z, d.get("name", z), mean(vals)))
    ranks.sort(key=lambda x: x[2])
    if ranks:
        bg = next((i for i, r in enumerate(ranks) if r[0] == "BG"), None)
        facts.append(f"В Европа най-евтино беше в {ranks[0][1]} ({ranks[0][2]:.1f}), най-скъпо в {ranks[-1][1]} "
                     f"({ranks[-1][2]:.1f})" + (f"; България е {bg + 1}-а от {len(ranks)} зони." if bg is not None else "."))

    why = []
    if peak >= 17 and trough in range(10, 16):
        why.append("Профилът дъно-по-обед и пик-вечер е типичен за „патешката крива“: слънчевите централи свалят "
                   "цената през деня, а след залез по-скъпите централи определят цената.")
    elif peak >= 16 and trough <= 5:
        why.append("Нощното дъно и следобедно-вечерният пик са типични за зимата: потреблението расте със здрачаването "
                   "(осветление, отопление), а слънчевите централи вече не произвеждат.")
    elif peak in range(7, 11):
        why.append("Сутрешният пик обикновено идва от покачване на потреблението преди слънцето да даде пълна мощност.")
    if neg:
        why.append("Отрицателните часове показват излишък на производство – най-изгодното време за зареждане на батерии.")
    if pavg and abs(avg - pavg) / pavg > 0.15:
        why.append("Промяна над 15 % за седмица често идва от времето (вятър, слънце, температура) или от цените на газа; "
                   "приложението не може да посочи точната причина.")

    title, text, where = CONCEPTS[(number - 1) % len(CONCEPTS)]
    exercise_q = (f"Батерия 1 MW / 2 MWh с 88 % ефективност прави един цикъл {'във' if best_day.weekday() == 1 else 'в'} {dn(best_day)} – денят с най-голям "
                  f"спред ({spreads[best_day]:.1f} €/MWh между двата най-скъпи и двата най-евтини часа). Колко печели?")
    exercise_a = (f"≈ {bess:.0f} €: купува в двата най-евтини часа и продава в двата най-скъпи по-късно, като губи 12 % "
                  f"при кръга. Сметката е същата като в „BESS анализатор“.")

    in_week = [n for n in news if monday.isoformat() <= n.get("published", "")[:10] <= sunday.isoformat()]
    rank = {"пряка": 0, "висока": 0, "средна": 1, "косвена": 2}
    in_week.sort(key=lambda n: rank.get(n.get("bg_relevance") or "", 3))
    top_news = [{"title": n["title"], "link": n["link"], "source": n["source"]}
                for n in in_week[:3]]

    return {
        "id": key, "number": number,
        "title": f"Урок {number} · {monday.strftime('%d.%m')}–{sunday.strftime('%d.%m.%Y')}",
        "published": _iso(now),
        "week": {"from": monday.isoformat(), "to": sunday.isoformat()},
        "facts": facts,
        "why": why,
        "concept": {"title": title, "text": text, "where": where},
        "exercise": {"question": exercise_q, "answer": exercise_a},
        "news": top_news,
        "sources": [src or "ENTSO-E", "ENTSO-E / Energy-Charts (Европа)"],
        "note": "Числата са изчислени автоматично от реалните данни; обясненията са учебни текстове, не анализ на причините.",
    }


def update_lessons(prev: dict | None, feed: dict, news: list[dict], now: dt.datetime) -> dict:
    """Publish on Sunday (Sofia) from 08:00, once per week; keep every earlier lesson."""
    lessons = list((prev or {}).get("lessons", []))
    local = now.astimezone(SOFIA)
    ref = now - dt.timedelta(days=1) if local.weekday() == 6 and local.hour < 8 else now
    _, _, key = week_of(ref)
    # Sunday from 08:00; the very first run publishes the last completed week so the section is never empty.
    due = (local.weekday() == 6 and local.hour >= 8) or not lessons
    if due and not any(l["id"] == key for l in lessons):
        lesson = build_lesson(feed, news, now, len(lessons) + 1, ref)
        if lesson:
            lessons.insert(0, lesson)
    return {"generated_at": _iso(now), "schedule": "всяка неделя от 08:00", "lessons": lessons}
