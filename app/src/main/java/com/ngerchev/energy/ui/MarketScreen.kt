package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.ngerchev.energy.logic.Prices
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("bg"))
private val shortDay = DateTimeFormatter.ofPattern("d.MM")

@Composable
fun MarketScreen(data: MarketData, padding: PaddingValues) {
    val now = ZonedDateTime.now(EnergyParser.SOFIA)
    val today = now.toLocalDate()
    val hasTomorrow = data.tomorrow.isNotEmpty()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val day: LocalDate = if (tab == 1 && hasTomorrow) today.plusDays(1) else today
    val source = if (tab == 1 && hasTomorrow) data.tomorrow else data.prices30d
    val hours = Prices.hourly(source, day)
    val stats = Prices.stats(hours)
    val daily = Prices.dailyAverages(data.prices30d)
    val monthAvg = daily.map { it.second }.average().takeIf { !it.isNaN() }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(EColors.Card).padding(4.dp),
            ) {
                Chip("Днес", tab == 0) { tab = 0 }
                Chip(if (hasTomorrow) "Утре" else "Утре (след ~14:00)", tab == 1, enabled = hasTomorrow) { tab = 1 }
            }
        }

        if (stats == null) {
            item { Card { Text("Няма цени за избрания ден.", color = EColors.Muted) } }
        } else {
            val current = if (day == today) hours.firstOrNull { it.hour == now.hour } else null
            item {
                Card {
                    Text(day.format(dayFmt).replaceFirstChar { it.uppercase() }, color = EColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (current != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fmt1(current.eurMwh), color = priceColor(current.eurMwh, stats.min.eurMwh, stats.max.eurMwh),
                                fontSize = 42.sp, fontWeight = FontWeight.Bold)
                            Text("  €/MWh сега", color = EColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Text("= ${fmt2(current.eurMwh / 1000 * 100)} евроцента/kWh (без мрежови такси и ДДС)",
                            color = EColors.Muted, fontSize = 12.sp)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fmt1(stats.avg), color = EColors.Text, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                            Text("  €/MWh средно", color = EColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    StatRow {
                        Stat("Средна (base)", fmt1(stats.avg), "€")
                        Stat("Мин. ${hh(stats.min.hour)}", fmt1(stats.min.eurMwh), "€", EColors.Green)
                        Stat("Макс. ${hh(stats.max.hour)}", fmt1(stats.max.eurMwh), "€", EColors.Red)
                    }
                    Spacer(Modifier.height(8.dp))
                    StatRow {
                        val peak = hours.filter { it.hour in 8..19 }.map { it.eurMwh }.average()
                        Stat("Пик 8–20 ч.", if (peak.isNaN()) "—" else fmt1(peak), "€")
                        Stat("Спред 2 ч.", fmt1(stats.spread2h), "€", EColors.Amber)
                        Stat("Отриц. часове", stats.negativeHours.toString(), "", if (stats.negativeHours > 0) EColors.Blue else EColors.Text)
                    }
                }
            }
            item {
                Card {
                    SectionTitle("Почасови цени", "Ден напред, БНЕБ (IBEX) зона BG · пунктир = средна")
                    BarChart(
                        values = hours.map { it.eurMwh },
                        labels = hours.map { it.hour.toString() },
                        highlight = current?.hour?.let { h -> hours.indexOfFirst { it.hour == h } },
                        colorFor = { priceColor(it, stats.min.eurMwh, stats.max.eurMwh) },
                        average = stats.avg,
                    )
                }
            }
            item {
                Card {
                    SectionTitle("Най-евтини и най-скъпи часове", "Кога да се зарежда и кога да се продава")
                    val cheap = hours.sortedBy { it.eurMwh }.take(4).sortedBy { it.hour }
                    val dear = hours.sortedByDescending { it.eurMwh }.take(4).sortedBy { it.hour }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Евтини", color = EColors.Green, fontWeight = FontWeight.SemiBold)
                            cheap.forEach { HourLine(it.hour, it.eurMwh) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Скъпи", color = EColors.Red, fontWeight = FontWeight.SemiBold)
                            dear.forEach { HourLine(it.hour, it.eurMwh) }
                        }
                    }
                }
            }
        }

        if (daily.size > 2) {
            item {
                Card {
                    SectionTitle("Последните ${daily.size} дни", "Средна дневна цена · средно ${monthAvg?.let { fmt1(it) } ?: "—"} €/MWh")
                    val lo = daily.minOf { it.second }
                    val hi = daily.maxOf { it.second }
                    BarChart(
                        values = daily.map { it.second },
                        labels = daily.map { it.first.format(shortDay) },
                        labelEvery = 7,
                        height = 140.dp,
                        colorFor = { priceColor(it, lo, hi) },
                        average = monthAvg,
                    )
                }
            }
        }
        item { SourceNote(data) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Chip(
    text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    Text(
        text,
        color = when {
            selected -> EColors.Bg
            enabled -> EColors.Text
            else -> EColors.Muted
        },
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) EColors.Teal else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun HourLine(hour: Int, price: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${hh(hour)}–${hh(hour + 1)}", color = EColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(fmt1(price), color = EColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun hh(h: Int) = "%02d:00".format(h % 24)

@Composable
fun SourceNote(data: MarketData) {
    val t = data.fetchedAt.format(DateTimeFormatter.ofPattern("d.MM HH:mm"))
    Text(
        (if (data.fromCache) "Без връзка — показани са последните запазени данни. " else "Обновено $t. ") +
            "Данни: energy-charts.info (Fraunhofer ISE), ENTSO-E.",
        color = EColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp),
    )
}
