package com.ngerchev.energy.logic

import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.MarketData
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class ReportKind(val label: String, val days: Int) { DAILY("Дневен", 1), WEEKLY("Седмичен", 7), MONTHLY("Месечен", 30), ANNUAL("Годишен", 365) }

data class Report(
    val kind: ReportKind,
    val title: String,
    val lines: List<Pair<String, String>>,   // label -> value
    val sections: List<Pair<String, List<String>>>,
    val table: List<List<String>>,           // first row = header
    val sources: List<String>,
    val generatedAt: ZonedDateTime,
    val dataAsOf: ZonedDateTime,
)

object Reports {
    private val dfmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private fun f(v: Double) = String.format(Locale("bg"), "%.2f", v)

    fun build(kind: ReportKind, d: MarketData): Report {
        val today = LocalDate.now(EnergyParser.SOFIA)
        val feed = d.feed
        val lines = mutableListOf<Pair<String, String>>()
        val sections = mutableListOf<Pair<String, List<String>>>()
        val table = mutableListOf<List<String>>()
        val sources = linkedSetOf<String>()
        feed?.pricesMeta?.source?.let { sources += it } ?: sources.add("Energy-Charts (Fraunhofer ISE)")

        if (kind == ReportKind.DAILY) {
            val h = Prices.hourly(d.prices30d, today)
            Prices.stats(h)?.let { s ->
                lines += "Средна цена (base)" to "${f(s.avg)} €/MWh"
                lines += "Мин. / макс." to "${f(s.min.eurMwh)} (${s.min.hour} ч.) / ${f(s.max.eurMwh)} (${s.max.hour} ч.)"
                lines += "Спред 2 ч." to "${f(s.spread2h)} €/MWh"
                lines += "Отрицателни часове" to "${s.negativeHours}"
            }
            table += listOf("Час", "Цена EUR/MWh")
            h.forEach { table += listOf("%02d:00".format(it.hour), f(it.eurMwh)) }
            d.generation?.let { g ->
                val i = g.lastFilledIndex()
                if (i >= 0) {
                    sections += "Производство (последно измерване ${g.times[i].toLocalTime()})" to
                        g.series.mapNotNull { s -> s.values[i]?.takeIf { it > 0.5 }?.let { "${s.name}: ${"%.0f".format(it)} MW" } }
                    feed?.genMeta?.source?.let { sources += it }
                }
            }
            feed?.loadActual?.lastOrNull()?.let { lines += "Потребление (последно)" to "${"%.0f".format(it.value)} MW · ${it.time.toLocalTime()}" }
            d.flows?.let { fl ->
                val i = fl.lastFilledIndex()
                if (i >= 0) {
                    val net = fl.series.sumOf { it.values[i] ?: 0.0 }
                    lines += (if (net >= 0) "Нетен внос" else "Нетен износ") to "${"%.0f".format(abs(net))} MW"
                    feed?.flowsMeta?.source?.let { sources += it }
                }
            }
        } else {
            val daily = if (kind == ReportKind.ANNUAL) feed?.yearDaily.orEmpty()
            else Prices.dailyAverages(d.prices30d).map { it.first.toString() to it.second }
            val sel = daily.takeLast(kind.days)
            if (sel.isNotEmpty()) {
                val vals = sel.map { it.second }
                val avg = vals.average()
                val sd = sqrt(vals.map { (it - avg) * (it - avg) }.average())
                lines += "Период" to "${sel.first().first} – ${sel.last().first} (${sel.size} дни)"
                lines += "Средна цена" to "${f(avg)} €/MWh"
                lines += "Мин. / макс. дневна" to "${f(vals.min())} / ${f(vals.max())}"
                lines += "Волатилност (станд. откл. на дневните)" to "${f(sd)} €/MWh"
                if (kind == ReportKind.ANNUAL) feed?.yearMeta?.source?.let { sources += it }
            } else lines += "Данни" to "няма достатъчно история"
            table += listOf("Дата", "Средна цена EUR/MWh")
            sel.forEach { table += listOf(it.first, f(it.second)) }
            val eu = feed?.europe.orEmpty().filter { it.daily.isNotEmpty() }
            if (eu.isNotEmpty() && kind != ReportKind.ANNUAL) {
                sections += "Регионално сравнение (средно за периода)" to eu.map { z ->
                    z.name to z.daily.takeLast(kind.days).map { it.second }.average()
                }.sortedBy { it.second }.map { "${it.first}: ${f(it.second)} €/MWh" }
            }
        }
        val news = d.news.filter { it.published.isAfter(ZonedDateTime.now(EnergyParser.SOFIA).minusDays(kind.days.coerceAtMost(30).toLong())) }
        if (news.isNotEmpty()) {
            sections += "Новини и технологии" to news.take(if (kind == ReportKind.DAILY) 5 else 10).map { "${it.title} – ${it.source}" }
            sources += news.take(10).map { it.source }
        }
        return Report(kind, "${kind.label} отчет – енергиен пазар България · ${today.format(dfmt)}", lines, sections, table,
            sources.toList(), ZonedDateTime.now(EnergyParser.SOFIA), d.fetchedAt)
    }

    /** CSV with ; as separator (opens correctly in Excel with Bulgarian settings) and a UTF-8 BOM. */
    fun csv(r: Report): String = buildString {
        append('﻿')
        append(r.title).append('\n')
        append("Данни към;").append(r.dataAsOf).append('\n')
        r.lines.forEach { append(it.first).append(';').append(it.second.replace(';', ',')).append('\n') }
        append('\n')
        r.table.forEach { row -> append(row.joinToString(";")).append('\n') }
        append('\n').append("Източници;").append(r.sources.joinToString(", ")).append('\n')
    }
}
