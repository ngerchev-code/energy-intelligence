package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.data.Series
import com.ngerchev.energy.data.SeriesSet
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Generation mix and cross-border exchange for today. */
@Composable
fun GridScreen(data: MarketData, padding: PaddingValues) {
    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val gen = data.generation
        val idx = gen?.lastFilledIndex() ?: -1
        val flows = data.flows
        val fi = flows?.lastFilledIndex() ?: -1
        val net = if (flows != null && fi >= 0) flows.series.sumOf { it.values.getOrNull(fi) ?: 0.0 } else null

        if (gen == null || idx < 0 || gen.series.isEmpty()) {
            item { Tile { Text("Няма данни за производството днес.", color = EColors.Muted, fontFamily = Body) } }
        } else {
            val now: List<Pair<Series, Double>> = gen.series.mapNotNull { s -> s.values.getOrNull(idx)?.let { s to it } }
                .filter { it.second > 0.5 }
                .sortedByDescending { it.second }
            val total = now.sumOf { it.second }
            val renewable = now.filter { isRenewable(it.first.id + " " + it.first.name) }.sumOf { it.second }
            val share = if (total > 0) renewable / total else 0.0

            item {
                Tile(radius = 30.dp) {
                    Label("Производство сега · ${gen.times[idx].format(timeFmt)} ч.")
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Num(fmt0(total), 40.sp)
                        Text(" MW", color = EColors.Muted, fontSize = 14.sp, fontFamily = Body,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    // One proportional strip of all sources.
                    Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                        now.forEach { (s, v) ->
                            Spacer(Modifier.weight((v / total).toFloat().coerceAtLeast(0.001f)).height(16.dp)
                                .background(sourceColor(s.id + " " + s.name)))
                        }
                    }
                }
            }
            item {
                StatRow {
                    Stat("ВЕИ дял", fmt0(share * 100), "%", EColors.OnLime, EColors.Lime)
                    val nuclear = now.firstOrNull { "nuclear" in (it.first.id + it.first.name).lowercase() }
                    Stat("АЕЦ", nuclear?.let { fmt0(it.second) } ?: "—", "MW")
                    if (net != null) Stat(if (net >= 0) "Внос" else "Износ", fmt0(abs(net)), "MW",
                        EColors.OnSand, EColors.Sand)
                }
            }
            item {
                // Source tiles, two per row.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    now.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (s, v) -> SourceTile(s, v, total, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Tile {
                    SectionTitle("Микс през деня", "MW")
                    val hourly = hourlyColumns(gen)
                    StackedChart(columns = hourly.second, labels = hourly.first)
                }
            }
        }

        if (flows != null && fi >= 0 && flows.series.isNotEmpty()) {
            val cur = flows.series.mapNotNull { s -> s.values.getOrNull(fi)?.let { s to it } }
            val maxAbs = cur.maxOfOrNull { abs(it.second) } ?: 0.0
            item {
                Tile {
                    SectionTitle("Обмен със съседите", "към ${flows.times[fi].format(timeFmt)} ч.")
                    cur.sortedByDescending { abs(it.second) }.forEach { (s, v) ->
                        ShareBar(
                            countryLabel(s.id, s.name), v, maxAbs,
                            if (v >= 0) EColors.Sand else EColors.Lime,
                            (if (v >= 0) "внос " else "износ ") + fmt0(abs(v)) + " MW",
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Label("Търговски графици · пясъчно = внос, лайм = износ")
                }
            }
        }
        item { SourceNote(data) }
    }
}

@Composable
private fun SourceTile(s: Series, v: Double, total: Double, modifier: Modifier) {
    val c = sourceColor(s.id + " " + s.name)
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(EColors.Tile).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c, 10.dp)
            Spacer(Modifier.width(8.dp))
            Label(sourceLabel(s.id, s.name))
        }
        Spacer(Modifier.height(8.dp))
        Num(fmt0(v), 18.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Label("MW · ${if (total > 0) fmt0(v / total * 100) else "0"} %")
    }
}

private fun isRenewable(key: String): Boolean {
    val k = key.lowercase()
    return listOf("solar", "wind", "hydro", "biomass", "geothermal", "renewable").any { it in k }
}

/** Groups 15-minute steps into hours: labels and per-hour stacked columns. */
private fun hourlyColumns(set: SeriesSet): Pair<List<String>, List<List<Pair<Color, Double>>>> {
    val byHour = set.times.indices.groupBy { set.times[it].hour }.toSortedMap()
    val order = set.series.sortedByDescending { s -> s.values.filterNotNull().average().takeIf { !it.isNaN() } ?: 0.0 }
    val labels = byHour.keys.map { it.toString() }
    val cols = byHour.values.map { idxs ->
        order.map { s ->
            val vs = idxs.mapNotNull { s.values.getOrNull(it) }
            sourceColor(s.id + " " + s.name) to (if (vs.isEmpty()) 0.0 else vs.average())
        }
    }
    val lastFull = cols.indexOfLast { c -> c.any { it.second > 0 } }
    return labels.take(lastFull + 1) to cols.take(lastFull + 1)
}
