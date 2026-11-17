package com.ngerchev.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.data.TimePoint
import kotlin.math.abs

private val NB_COLORS = listOf(Color(0xFFC6F432), Color(0xFFF2E9DC), Color(0xFF8EC5FF), Color(0xFFFF8A6B), Color(0xFF7CC8A8))

/** Cross-border exchange: current position per neighbour and the day's net balance. */
@Composable
fun TradeScreen(data: MarketData, padding: PaddingValues) {
    val set = data.flows
    val meta = data.feed?.flowsMeta
    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (set == null || set.lastFilledIndex() < 0) {
            item { Notice("Няма данни за обмена", "Опитайте да обновите. Физическите потоци идват от ENTSO-E (нужен е ENTSOE_TOKEN).") }
        } else {
            val fi = set.lastFilledIndex()
            val cur = set.series.mapNotNull { s -> s.values.getOrNull(fi)?.let { s to it } }
            val net = cur.sumOf { it.second }
            item {
                Tile(radius = 30.dp, color = if (net >= 0) EColors.Sand else EColors.Lime) {
                    Label(if (net >= 0) "Нетен внос сега" else "Нетен износ сега", EColors.OnLime)
                    Num("${fmt0(abs(net))} MW", 36.sp, EColors.OnLime)
                    Label("${flowKind(meta?.kind ?: "")} потоци · към ${fmtTime(set.times[fi])}", EColors.OnLime)
                }
            }
            item {
                Tile {
                    SectionTitle("По съседи", "+ внос / − износ")
                    val maxAbs = cur.maxOfOrNull { abs(it.second) } ?: 1.0
                    cur.sortedByDescending { abs(it.second) }.forEach { (s, v) ->
                        ShareBar(countryLabel(s.id, s.name), v, maxAbs, if (v >= 0) EColors.Sand else EColors.Lime,
                            (if (v >= 0) "внос " else "износ ") + fmt0(abs(v)) + " MW")
                    }
                    Fresh(measuredKind(set.times[fi]), meta, set.times[fi])
                }
            }
            item {
                Tile {
                    SectionTitle("През деня", "MW по граница")
                    val lines = set.series.mapIndexed { i, s ->
                        set.times.indices.mapNotNull { k -> s.values[k]?.let { TimePoint(set.times[k], it) } } to NB_COLORS[i % NB_COLORS.size]
                    }
                    LineChart(lines)
                    set.series.forEachIndexed { i, s ->
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(NB_COLORS[i % NB_COLORS.size], 8.dp); Spacer(Modifier.width(6.dp)); Label(countryLabel(s.id, s.name))
                        }
                    }
                }
            }
        }
        item {
            Notice("Капацитет и претоварване",
                "Свободният преносен капацитет и претоварването на връзките все още не се изтеглят. Косвен признак: " +
                    "голяма ценова разлика между съседни зони в „Европа и Балкани“ означава изчерпан капацитет.")
        }
        item { Spacer(Modifier.height(4.dp)); SourceNote(data) }
    }
}
