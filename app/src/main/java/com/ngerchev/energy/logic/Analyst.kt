package com.ngerchev.energy.logic

import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.data.NewsItem
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

data class Answer(
    val question: String,
    val facts: List<String>,
    val inference: List<String>,
    val uncertainty: String,
    val sources: List<String>,
    val asOf: ZonedDateTime?,
)

data class ScenarioHour(val time: ZonedDateTime, val low: Double, val mid: Double, val high: Double, val known: Boolean)

/**
 * Rule-based analyst: every number in an answer is read from the downloaded data;
 * interpretations are listed separately and worded as possibilities.
 */
object Analyst {

    val QUESTIONS = listOf(
        "Защо цената днес е различна от вчера?",
        "Коя държава има най-ниска цена днес?",
        "Каква е нетната позиция на България – внос или износ?",
        "Кои технологии се развиват най-бързо?",
        "Какво се промени на европейския пазар тази седмица?",
        "Каква е доходността на батерия 1 MW / 2 MWh?",
    )

    private fun f(v: Double) = String.format(Locale("bg"), "%.1f", v)
    private fun f0(v: Double) = String.format(Locale("bg"), "%.0f", v)

    fun answer(i: Int, d: MarketData, news: List<NewsItem>): Answer = when (i) {
        0 -> priceChange(d)
        1 -> cheapest(d)
        2 -> netPosition(d)
        3 -> technologies(news)
        4 -> europeWeek(d)
        else -> bess(d)
    }

    private fun src(d: MarketData) = listOfNotNull(d.feed?.pricesMeta?.source ?: "Energy-Charts (Fraunhofer ISE)")

    fun priceChange(d: MarketData): Answer {
        val today = LocalDate.now(EnergyParser.SOFIA)
        val t = Prices.stats(Prices.hourly(d.prices30d, today))
        val y = Prices.stats(Prices.hourly(d.prices30d, today.minusDays(1)))
        val q = QUESTIONS[0]
        if (t == null || y == null) return Answer(q, listOf("Няма пълни цени за днес и вчера."), emptyList(), "", src(d), d.fetchedAt)
        val diff = t.avg - y.avg
        val facts = mutableListOf(
            "Средна цена днес: ${f(t.avg)} €/MWh, вчера: ${f(y.avg)} €/MWh (${if (diff >= 0) "+" else "−"}${f(abs(diff))}).",
            "Днес най-евтино в ${t.min.hour}:00 (${f(t.min.eurMwh)}), най-скъпо в ${t.max.hour}:00 (${f(t.max.eurMwh)}).",
        )
        val inf = mutableListOf<String>()
        val eu = d.feed?.europe.orEmpty()
        val nb = eu.filter { it.code in setOf("RO", "GR", "HU") && it.daily.size >= 2 }
        if (nb.isNotEmpty()) {
            val moves = nb.map { z -> z.name to (z.daily.last().second - z.daily[z.daily.size - 2].second) }
            facts += "Съседни зони (последни два дни): " + moves.joinToString { "${it.first} ${if (it.second >= 0) "+" else "−"}${f(abs(it.second))}" } + " €/MWh."
            if (moves.all { it.second * diff > 0 }) inf += "Вероятно промяната е регионална – съседните пазари се движат в същата посока, обичайно заради времето, търсенето или цените на газа."
            else inf += "Възможно е причината да е местна (производство или потребление в България), защото съседите не се движат еднакво."
        }
        val gen = d.generation
        val gi = gen?.lastFilledIndex() ?: -1
        if (gen != null && gi >= 0) {
            val total = gen.series.sumOf { (it.values.getOrNull(gi) ?: 0.0).coerceAtLeast(0.0) }
            val res = gen.series.filter { s -> listOf("solar", "wind", "hydro", "biomass").any { it in (s.id + s.name).lowercase() } && "pumped" !in (s.id + s.name).lowercase() }
                .sumOf { (it.values.getOrNull(gi) ?: 0.0).coerceAtLeast(0.0) }
            if (total > 0) {
                facts += "Дял на ВЕИ в последното измерване (${gen.times[gi].hour}:${"%02d".format(gen.times[gi].minute)}): ${f0(res / total * 100)} %."
                inf += if (res / total > 0.4) "Високият дял на ВЕИ обикновено натиска цените надолу в съответните часове."
                else "При нисък дял на ВЕИ цената по-често се определя от по-скъпите ТЕЦ."
            }
        }
        return Answer(q, facts, inf,
            "Тълкуванията са хипотези по наличните данни; не са взети предвид цени на газа и CO₂, ремонти и прекъсвания.",
            src(d), d.fetchedAt)
    }

    fun cheapest(d: MarketData): Answer {
        val q = QUESTIONS[1]
        val z = d.feed?.europe?.filter { it.today != null }?.sortedBy { it.today!!.avg }.orEmpty()
        if (z.isEmpty()) return Answer(q, listOf("Няма данни за европейските зони."), emptyList(), "", emptyList(), d.fetchedAt)
        val bgRank = z.indexOfFirst { it.code == "BG" }
        val facts = listOf(
            "Най-ниска средна цена днес: ${z.first().name} – ${f(z.first().today!!.avg)} €/MWh.",
            "Най-висока: ${z.last().name} – ${f(z.last().today!!.avg)} €/MWh.",
            if (bgRank >= 0) "България е ${bgRank + 1}-а от ${z.size} зони с ${f(z[bgRank].today!!.avg)} €/MWh." else "България липсва в днешните данни.",
        )
        return Answer(q, facts, emptyList(), "Сравняват се само зоните с публикувани цени.",
            z.map { it.source }.distinct(), d.feed?.generatedAt)
    }

    fun netPosition(d: MarketData): Answer {
        val q = QUESTIONS[2]
        val set = d.flows
        val fi = set?.lastFilledIndex() ?: -1
        if (set == null || fi < 0) return Answer(q, listOf("Няма данни за обмена."), emptyList(), "", emptyList(), d.fetchedAt)
        val cur = set.series.mapNotNull { s -> s.values.getOrNull(fi)?.let { s.name to it } }
        val net = cur.sumOf { it.second }
        val kind = if (d.feed?.flowsMeta?.kind == "physical") "физическите потоци" else "търговските графици"
        return Answer(q, listOf(
            "Към ${set.times[fi].hour}:${"%02d".format(set.times[fi].minute)} България има нетен ${if (net >= 0) "внос" else "износ"} ${f0(abs(net))} MW по $kind.",
            "По граници: " + cur.joinToString { "${it.first} ${if (it.second >= 0) "+" else "−"}${f0(abs(it.second))}" } + " MW (+ внос).",
        ), emptyList(), "Търговските графици и физическите потоци се различават – физическите показват реалния ток по линиите.",
            listOfNotNull(d.feed?.flowsMeta?.source), set.times[fi])
    }

    fun technologies(news: List<NewsItem>): Answer {
        val q = QUESTIONS[3]
        val now = ZonedDateTime.now(EnergyParser.SOFIA)
        val last = news.filter { it.published.isAfter(now.minusDays(7)) }.flatMap { it.categories }.groupingBy { it }.eachCount()
        val prev = news.filter { it.published.isAfter(now.minusDays(14)) && !it.published.isAfter(now.minusDays(7)) }
            .flatMap { it.categories }.groupingBy { it }.eachCount()
        if (last.isEmpty()) return Answer(q, listOf("Няма достатъчно новини за последните 7 дни."), emptyList(), "", emptyList(), now)
        val top = last.entries.sortedByDescending { it.value }.take(5)
        return Answer(q,
            listOf("Най-често споменавани теми в новините за 7 дни: " + top.joinToString { "${it.key} (${it.value}${prev[it.key]?.let { p -> ", преди: $p" } ?: ""})" } + "."),
            listOf("Възможно е честотата в новините да отразява инвестиционния интерес, но тя не измерва пазарен растеж."),
            "Мярката е брой новини от избраните RSS канали, класифицирани автоматично.",
            news.map { it.source }.distinct().take(6), now)
    }

    fun europeWeek(d: MarketData): Answer {
        val q = QUESTIONS[4]
        val z = d.feed?.europe.orEmpty().filter { it.daily.size >= 14 }
        if (z.isEmpty()) return Answer(q, listOf("Няма 14 дни история по зони."), emptyList(), "", emptyList(), d.fetchedAt)
        val ch = z.map { s ->
            val a = s.daily.takeLast(7).map { it.second }.average()
            val b = s.daily.dropLast(7).takeLast(7).map { it.second }.average()
            Triple(s.name, a, a - b)
        }.sortedByDescending { abs(it.third) }
        val avgMove = ch.map { it.third }.average()
        return Answer(q, listOf(
            "Средно за всички зони цените се промениха с ${if (avgMove >= 0) "+" else "−"}${f(abs(avgMove))} €/MWh спрямо предходната седмица.",
            "Най-голяма промяна: " + ch.take(3).joinToString { "${it.first} ${if (it.third >= 0) "+" else "−"}${f(abs(it.third))}" } + " €/MWh.",
            ch.firstOrNull { it.first == "България" }?.let { "България: средно ${f(it.second)} €/MWh (${if (it.third >= 0) "+" else "−"}${f(abs(it.third))})." } ?: "",
        ).filter { it.isNotEmpty() },
            listOf(if (ch.count { it.third * avgMove > 0 } > ch.size * 0.7) "Вероятно става дума за общ европейски фактор (време, газ, търсене)."
            else "Промените са разнопосочни – вероятно по-силни местни фактори."),
            "Седмиците се сравняват по дневни средни цени ден напред.", z.map { it.source }.distinct(), d.feed?.generatedAt)
    }

    fun bess(d: MarketData): Answer {
        val q = QUESTIONS[5]
        val days = Prices.fullDays(d.prices30d)
        val r = Bess.evaluate(days, BessInput()) ?: return Answer(q, listOf("Няма достатъчно цени."), emptyList(), "", emptyList(), d.fetchedAt)
        return Answer(q, listOf(
            "По реалните цени за последните ${r.daysAnalysed} дни арбитраж с 1 MW / 2 MWh, 1 цикъл дневно, носи средно ${f0(r.avgDailyProfit)} € на ден.",
            "Това е ≈ ${f0(r.annualRevenue)} € годишно приход и ${f0(r.annualNet)} € след поддръжка.",
        ), listOf("Ако цените се държат като през последните 30 дни, откупуването е около ${r.paybackYears?.let { f(it) } ?: "—"} години – резултатът е симулация."),
            "Предположения: ефективност 88 %, DoD 90 %, 250 €/kWh, без балансиращи услуги, такси и деградация. Подробно – BESS анализатор.",
            src(d), d.fetchedAt)
    }

    /**
     * 24–72 h price outlook: hours already set by the day-ahead market are facts; later hours are a
     * scenario band (min/median/max of the same hour over the last 7 days), never a forecast.
     */
    fun scenario(d: MarketData): List<ScenarioHour> {
        val now = ZonedDateTime.now(EnergyParser.SOFIA).withMinute(0).withSecond(0).withNano(0)
        val known = d.prices30d.groupBy { it.time.withMinute(0).withSecond(0).withNano(0) }
            .mapValues { (_, ps) -> ps.map { it.eurMwh }.average() }
        val today = now.toLocalDate()
        val history = (1..7).map { today.minusDays(it.toLong()) }.associateWith { Prices.hourly(d.prices30d, it) }
        return (0 until 72).map { h ->
            val t = now.plusHours(h.toLong())
            val k = known[t]
            if (k != null) ScenarioHour(t, k, k, k, true)
            else {
                val same = history.values.mapNotNull { day -> day.firstOrNull { it.hour == t.hour }?.eurMwh }.sorted()
                if (same.isEmpty()) ScenarioHour(t, 0.0, 0.0, 0.0, false)
                else ScenarioHour(t, same.first(), same[same.size / 2], same.last(), false)
            }
        }
    }
}
