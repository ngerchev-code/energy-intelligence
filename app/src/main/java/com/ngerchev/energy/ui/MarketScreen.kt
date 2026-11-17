package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.HourPrice
import com.ngerchev.energy.logic.Prices
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("bg"))
private val shortDay = DateTimeFormatter.ofPattern("d.MM")

fun screenPadding(padding: PaddingValues) = PaddingValues(
    start = 16.dp, end = 16.dp,
    top = padding.calculateTopPadding() + 4.dp,
    bottom = padding.calculateBottomPadding() + 96.dp, // room for the floating dock
)

@Composable
fun MarketScreen(data: MarketData, padding: PaddingValues) {
    val now = ZonedDateTime.now(EnergyParser.SOFIA)
    val today = now.toLocalDate()
    val hasTomorrow = data.tomorrow.isNotEmpty()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val isTomorrow = tab == 1 && hasTomorrow
    val day: LocalDate = if (isTomorrow) today.plusDays(1) else today
    val hours = Prices.hourly(if (isTomorrow) data.tomorrow else data.prices30d, day)
    val stats = Prices.stats(hours)
    val daily = Prices.dailyAverages(data.prices30d)
    val monthAvg = daily.map { it.second }.average().takeIf { !it.isNaN() }

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(EColors.Tile).padding(5.dp)) {
                Pill("Днес", tab == 0) { tab = 0 }
                Pill(if (hasTomorrow) "Утре" else "Утре · след 14 ч.", tab == 1, enabled = hasTomorrow) { tab = 1 }
            }
        }

        if (stats == null) {
            item { Tile { Text("Няма цени за избрания ден.", color = EColors.Muted, fontFamily = Body) } }
        } else {
            val current = if (day == today) hours.firstOrNull { it.hour == now.hour } else null
            val shown = current?.eurMwh ?: stats.avg
            item {
                Tile(radius = 30.dp) {
                    Label(
                        if (current != null) "Цена сега · ${hh(current.hour)}"
                        else day.format(dayFmt).replaceFirstChar { it.uppercase() } + " · средна"
                    )
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.BottomCenter) {
                        Gauge(shown, stats.min.eurMwh, stats.max.eurMwh,
                            Modifier.fillMaxWidth(0.78f).height(140.dp).offset(y = (-6).dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val parts = fmt1(shown).split(",")
                            Row(verticalAlignment = Alignment.Bottom) {
                                Num(parts[0], 44.sp)
                                Num("," + parts.getOrElse(1) { "0" }, 22.sp)
                            }
                            val pos = if (stats.max.eurMwh - stats.min.eurMwh > 1e-6)
                                ((shown - stats.min.eurMwh) / (stats.max.eurMwh - stats.min.eurMwh) * 100).roundToInt() else 50
                            Label("€/MWh · $pos % от дневния диапазон")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label("мин ${fmt1(stats.min.eurMwh)}")
                        Label("ср ${fmt1(stats.avg)}")
                        Label("макс ${fmt1(stats.max.eurMwh)}")
                    }
                    Fresh(Kind.DAY_AHEAD, data.feed?.pricesMeta, data.fetchedAt)
                    if (current != null) {
                        Spacer(Modifier.height(6.dp))
                        Label("= ${fmt2(current.eurMwh / 10)} ц/kWh на борсата, без такси и ДДС")
                    }
                }
            }
            item {
                val win = windows(hours)
                StatRow {
                    ActionTile("Зареждай", win?.first, EColors.Lime, EColors.OnLime)
                    ActionTile("Продавай", win?.second, EColors.Sand, EColors.OnSand)
                }
            }
            item {
                Tile {
                    SectionTitle("Ден напред · почасово", "€/MWh")
                    BarChart(
                        values = hours.map { it.eurMwh },
                        labels = hours.map { it.hour.toString() },
                        highlight = current?.hour?.let { h -> hours.indexOfFirst { it.hour == h } }?.takeIf { it >= 0 },
                        colorFor = { barColor(it, stats.min.eurMwh, stats.max.eurMwh) },
                        average = stats.avg,
                    )
                }
            }
            item {
                StatRow {
                    val peak = hours.filter { it.hour in 8..19 }.map { it.eurMwh }.average()
                    Stat("Пик 8–20", if (peak.isNaN()) "—" else fmt0(peak), "€")
                    Stat("Спред 2 ч.", fmt0(stats.spread2h), "€")
                    Stat("Отриц. ч.", stats.negativeHours.toString(), "",
                        if (stats.negativeHours > 0) EColors.Sky else EColors.Text)
                }
            }
        }

        if (daily.size > 2) {
            item {
                Tile {
                    SectionTitle("Последни ${daily.size} дни", "ср ${monthAvg?.let { fmt0(it) } ?: "—"} €/MWh")
                    val lo = daily.minOf { it.second }
                    val hi = daily.maxOf { it.second }
                    BarChart(
                        values = daily.map { it.second },
                        labels = daily.map { it.first.format(shortDay) },
                        labelEvery = 7,
                        height = 96.dp,
                        highlight = daily.lastIndex,
                        colorFor = { barColor(it, lo, hi) },
                        average = monthAvg,
                    )
                }
            }
        }
        val year = data.feed?.yearDaily.orEmpty()
        if (year.size > 30) item {
            Tile {
                val avg = year.map { it.second }.average()
                SectionTitle("Последните 12 месеца", "ср ${fmt0(avg)} €/MWh")
                val lo = year.minOf { it.second }
                val hi = year.maxOf { it.second }
                BarChart(values = year.map { it.second }, labels = year.map { it.first.substring(5) },
                    labelEvery = 60, height = 90.dp, colorFor = { barColor(it, lo, hi) }, average = avg)
                Fresh(Kind.HISTORY, data.feed?.yearMeta, data.feed?.yearMeta?.retrievedAt, "дневни средни")
            }
        }
        item { SourceNote(data) }
    }
}

/** Best 3-hour charge window and the best later 3-hour sell window. */
private fun windows(hours: List<HourPrice>): Pair<Pair<String, Double>, Pair<String, Double>>? {
    if (hours.size < 8) return null
    val w = 3
    var best: Pair<Int, Int>? = null
    var bestGain = Double.NEGATIVE_INFINITY
    for (i in 0..hours.size - w) for (j in i + w..hours.size - w) {
        val buy = hours.subList(i, i + w).map { it.eurMwh }.average()
        val sell = hours.subList(j, j + w).map { it.eurMwh }.average()
        if (sell - buy > bestGain) { bestGain = sell - buy; best = i to j }
    }
    val (i, j) = best ?: return null
    fun range(k: Int) = "${hours[k].hour}–${hours[k + w - 1].hour + 1} ч."
    return (range(i) to hours.subList(i, i + w).map { it.eurMwh }.average()) to
        (range(j) to hours.subList(j, j + w).map { it.eurMwh }.average())
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionTile(
    title: String, win: Pair<String, Double>?, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color,
) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(bg).padding(16.dp)) {
        Text(title, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Body)
        Spacer(Modifier.height(6.dp))
        Num(win?.first ?: "—", 20.sp, fg)
        Spacer(Modifier.height(2.dp))
        Text(win?.let { "≈ ${fmt1(it.second)} €/MWh" } ?: "", color = fg, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = Body)
    }
}

fun hh(h: Int) = "%02d:00".format(h % 24)

@Composable
fun SourceNote(data: MarketData) {
    val t = data.fetchedAt.format(DateTimeFormatter.ofPattern("d.MM HH:mm"))
    Text(
        (if (data.fromCache) "Без връзка — последни запазени данни. " else "Обновено $t · ") +
            (data.feed?.pricesMeta?.source ?: "energy-charts.info (Fraunhofer ISE)"),
        color = EColors.Muted, fontSize = 11.sp, fontFamily = Body,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
