package com.ngerchev.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.data.SeriesSet
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Generation mix and cross-border exchange for today. */
@Composable
fun GridScreen(data: MarketData, padding: PaddingValues) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val gen = data.generation
        val idx = gen?.lastFilledIndex() ?: -1
        if (gen == null || idx < 0 || gen.series.isEmpty()) {
            item { Card { Text("Няма данни за производството днес.", color = EColors.Muted) } }
        } else {
            val now = gen.series.mapNotNull { s -> s.values.getOrNull(idx)?.let { s to it } }
                .filter { it.second > 0.5 }
                .sortedByDescending { it.second }
            val total = now.sumOf { it.second }
            val renewable = now.filter { isRenewable(it.first.id + " " + it.first.name) }.sumOf { it.second }
            item {
                Card {
                    SectionTitle("Производство сега", "Към ${gen.times[idx].format(timeFmt)} ч., нетно, MW")
                    StatRow {
                        Stat("Общо", fmt0(total), "MW")
                        Stat("ВЕИ дял", if (total > 0) fmt0(renewable / total * 100) else "—", "%", EColors.Green)
                        val nuclear = now.firstOrNull { "nuclear" in it.first.id.lowercase() || "nuclear" in it.first.name.lowercase() }
                        Stat("АЕЦ", nuclear?.let { fmt0(it.second) } ?: "—", "MW")
                    }
                    Spacer(Modifier.height(12.dp))
                    now.forEach { (s, v) ->
                        ShareBar(
                            sourceLabel(s.id, s.name), v, total, sourceColor(s.id + " " + s.name),
                            "${fmt0(v)} MW · ${if (total > 0) fmt0(v / total * 100) else "0"}%",
                        )
                    }
                }
            }
            item {
                Card {
                    SectionTitle("Микс през деня", "Почасово, MW")
                    val hourly = hourlyColumns(gen)
                    StackedChart(columns = hourly.second, labels = hourly.first)
                    Spacer(Modifier.height(10.dp))
                    // Legend
                    gen.series.filter { s -> s.values.any { (it ?: 0.0) > 0.5 } }.chunked(2).forEach { pair ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            pair.forEach { s ->
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Dot(sourceColor(s.id + " " + s.name), 8.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(sourceLabel(s.id, s.name), color = EColors.Muted, fontSize = 12.sp)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val flows = data.flows
        val fi = flows?.lastFilledIndex() ?: -1
        if (flows != null && fi >= 0 && flows.series.isNotEmpty()) {
            val cur = flows.series.mapNotNull { s -> s.values.getOrNull(fi)?.let { s to it } }
            val net = cur.sumOf { it.second }
            val maxAbs = cur.maxOfOrNull { kotlin.math.abs(it.second) } ?: 0.0
            item {
                Card {
                    SectionTitle("Трансграничен обмен", "Търговски графици към ${flows.times[fi].format(timeFmt)} ч. · + внос / − износ")
                    StatRow {
                        Stat(if (net >= 0) "Нетен внос" else "Нетен износ", fmt0(kotlin.math.abs(net)), "MW",
                            if (net >= 0) EColors.Amber else EColors.Teal)
                    }
                    Spacer(Modifier.height(10.dp))
                    cur.sortedByDescending { kotlin.math.abs(it.second) }.forEach { (s, v) ->
                        ShareBar(
                            countryLabel(s.id, s.name), v, maxAbs,
                            if (v >= 0) EColors.Amber else EColors.Teal,
                            (if (v >= 0) "внос " else "износ ") + fmt0(kotlin.math.abs(v)) + " MW",
                        )
                    }
                }
            }
        }
        item { SourceNote(data) }
    }
}

private fun isRenewable(key: String): Boolean {
    val k = key.lowercase()
    return listOf("solar", "wind", "hydro", "biomass", "geothermal", "renewable").any { it in k } &&
        "pumped storage consumption" !in k
}

/** Groups 15-minute steps into hours: labels and per-hour stacked columns. */
private fun hourlyColumns(set: SeriesSet): Pair<List<String>, List<List<Pair<androidx.compose.ui.graphics.Color, Double>>>> {
    val byHour = set.times.indices.groupBy { set.times[it].hour }.toSortedMap()
    val order = set.series.sortedByDescending { s -> s.values.filterNotNull().average().takeIf { !it.isNaN() } ?: 0.0 }
    val labels = byHour.keys.map { it.toString() }
    val cols = byHour.values.map { idxs ->
        order.map { s ->
            val vs = idxs.mapNotNull { s.values.getOrNull(it) }
            sourceColor(s.id + " " + s.name) to (if (vs.isEmpty()) 0.0 else vs.average())
        }
    }
    // Drop trailing hours with no data yet.
    val lastFull = cols.indexOfLast { c -> c.any { it.second > 0 } }
    return labels.take(lastFull + 1) to cols.take(lastFull + 1)
}
