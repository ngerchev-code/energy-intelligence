package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.data.TimePoint
import com.ngerchev.energy.data.ZoneStat
import java.time.LocalDate
import kotlin.math.abs

private enum class Period(val label: String) { TODAY("Днес"), TOMORROW("Утре"), WEEK("7 дни"), MONTH("30 дни") }

/** Day-ahead price comparison across European bidding zones. */
@Composable
fun EuropeScreen(data: MarketData, padding: PaddingValues) {
    val feed = data.feed
    if (feed == null || feed.europe.isEmpty()) {
        Box(Modifier.padding(16.dp)) {
            Notice("Няма данни за Европа", "Сравнението идва от сървърната задача. Опитайте да обновите след малко.")
        }
        return
    }
    var periodName by rememberSaveable { mutableStateOf(Period.TODAY.name) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val period = Period.valueOf(periodName)

    fun value(z: ZoneStat): Double? = when (period) {
        Period.TODAY -> z.today?.avg
        Period.TOMORROW -> z.tomorrow?.avg
        Period.WEEK -> z.daily.takeLast(7).map { it.second }.takeIf { it.isNotEmpty() }?.average()
        Period.MONTH -> z.daily.takeLast(30).map { it.second }.takeIf { it.isNotEmpty() }?.average()
    }

    val rows = feed.europe.mapNotNull { z -> value(z)?.let { z to it } }.sortedBy { it.second }
    val bg = rows.firstOrNull { it.first.code == "BG" }?.second
    val maxV = rows.maxOfOrNull { abs(it.second) } ?: 1.0

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ChipRow(Period.entries.map { it.label }, period.label,
                { l -> periodName = (Period.entries.firstOrNull { it.label == l } ?: Period.TODAY).name }, allLabel = null)
        }
        if (rows.isEmpty()) item { Notice("Няма цени за избрания период", "Цените за утре излизат около 13–14 ч. българско време.") }
        else item {
            Tile {
                SectionTitle("Средна цена ден напред", "€/MWh · спред към BG")
                rows.forEachIndexed { i, (z, v) ->
                    val isBg = z.code == "BG"
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected == z.code) EColors.Track else EColors.Tile)
                        .clickable(role = Role.Button) { selected = if (selected == z.code) null else z.code }
                        .padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = EColors.Muted, fontSize = 11.sp, fontFamily = Body, modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(z.name, color = EColors.Text, fontFamily = Body,
                                    fontWeight = if (isBg) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 14.sp,
                                    modifier = Modifier.weight(1f))
                                Text(fmt1(v), color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(if (bg != null && !isBg) "  ${if (v - bg >= 0) "+" else "−"}${fmt0(abs(v - bg))}" else "",
                                    color = EColors.Muted, fontFamily = Body, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(EColors.Track)) {
                                Box(Modifier.fillMaxWidth((abs(v) / maxV).toFloat().coerceIn(0.02f, 1f)).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(if (isBg) EColors.Lime else barColor(v, rows.first().second, rows.last().second)))
                            }
                        }
                    }
                }
                Fresh(if (period == Period.TOMORROW || period == Period.TODAY) Kind.DAY_AHEAD else Kind.HISTORY, null,
                    feed.generatedAt, "източник по зона в детайлите")
            }
        }
        val sel = feed.europe.firstOrNull { it.code == selected }
        val bgZone = feed.europe.firstOrNull { it.code == "BG" }
        if (sel != null && bgZone != null) item {
            Tile {
                SectionTitle("${sel.name} и България", "дневни средни, €/MWh")
                fun line(z: ZoneStat) = z.daily.mapNotNull { (d, v) ->
                    runCatching { TimePoint(LocalDate.parse(d).atStartOfDay(com.ngerchev.energy.data.EnergyParser.SOFIA), v) }.getOrNull()
                }
                LineChart(listOf(line(bgZone) to EColors.Lime, line(sel) to EColors.Sand))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(EColors.Lime, 8.dp); Spacer(Modifier.width(4.dp)); Label("България")
                    Spacer(Modifier.width(12.dp)); Dot(EColors.Sand, 8.dp); Spacer(Modifier.width(4.dp)); Label(sel.name)
                }
                Spacer(Modifier.height(8.dp))
                sel.today?.let { KeyValue("Днес мин / макс", "${fmt1(it.min)} / ${fmt1(it.max)}") }
                KeyValue("Източник", sel.source)
                KeyValue("Резолюция", sel.resolution?.let(::resLabel) ?: "—")
            }
        }
        item {
            BodyText("Сравняват се зоните, за които има публикувани цени. Турция (EPİAŞ) не е в ENTSO-E, а Северна " +
                "Македония и Албания се показват, когато източникът ги дава. Сравнение по 15 минути и по часове " +
                "има в екрана „Енергиен пазар“ за България; тук са дневни средни.", EColors.Muted, 12)
        }
    }
}
