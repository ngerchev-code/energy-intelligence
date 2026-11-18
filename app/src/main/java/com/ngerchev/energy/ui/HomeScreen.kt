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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.Knowledge
import com.ngerchev.energy.logic.Prices
import java.time.ZonedDateTime
import kotlin.math.abs

/** Home: one glance at the Bulgarian system, Europe, weather, news and the daily fact. */
@Composable
fun HomeScreen(data: MarketData, kb: Knowledge?, padding: PaddingValues, go: (Screen) -> Unit) {
    val feed = data.feed
    val now = ZonedDateTime.now(EnergyParser.SOFIA)
    val hours = Prices.hourly(data.prices30d, now.toLocalDate())
    val stats = Prices.stats(hours)
    val cur = hours.firstOrNull { it.hour == now.hour }

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (feed != null && !feed.entsoeActive) item {
            Notice("ENTSO-E още не е свързан",
                "Данните са от резервния източник Energy-Charts. Добавете ENTSOE_TOKEN в GitHub Secrets, за да се " +
                    "включат товар, прогнози за ВЕИ и физически потоци. Състояние: Настройки.")
        }
        item {
            Tile(radius = 30.dp, modifier = Modifier.clickable(role = Role.Button) { go(Screen.MARKET) }) {
                Label("Цена в България · ${if (cur != null) hh(cur.hour) else "днес"}")
                if (stats != null) {
                    val shown = cur?.eurMwh ?: stats.avg
                    Box(Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.BottomCenter) {
                        Gauge(shown, stats.min.eurMwh, stats.max.eurMwh,
                            Modifier.fillMaxWidth(0.62f).height(110.dp).offset(y = (-4).dp), stroke = 14.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Num(fmt1(shown), 34.sp)
                            Label("€/MWh · ср ${fmt0(stats.avg)} · мин ${fmt0(stats.min.eurMwh)} · макс ${fmt0(stats.max.eurMwh)}")
                        }
                    }
                } else BodyText("Няма цени за днес.", EColors.Muted)
                Fresh(Kind.DAY_AHEAD, feed?.pricesMeta, data.fetchedAt)
            }
        }
        item {
            val load = feed?.loadActual?.lastOrNull()
            val gen = data.generation
            val gi = gen?.lastFilledIndex() ?: -1
            val genTotal = if (gen != null && gi >= 0) gen.series.sumOf { (it.values.getOrNull(gi) ?: 0.0).coerceAtLeast(0.0) } else null
            val fl = data.flows
            val fi = fl?.lastFilledIndex() ?: -1
            val net = if (fl != null && fi >= 0) fl.series.sumOf { it.values.getOrNull(fi) ?: 0.0 } else null
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatRow {
                    MiniTile("Потребление", load?.let { fmt0(it.value) } ?: "—", "MW",
                        load?.let { "${measuredKind(it.time).label} · ${fmtHm(it.time)}" } ?: "нужен е ENTSO-E") { go(Screen.PRODUCTION) }
                    MiniTile("Производство", genTotal?.let { fmt0(it) } ?: "—", "MW",
                        gen?.times?.getOrNull(gi)?.let { "${measuredKind(it).label} · ${fmtHm(it)}" } ?: "") { go(Screen.PRODUCTION) }
                }
                StatRow {
                    MiniTile(if ((net ?: 0.0) >= 0) "Нетен внос" else "Нетен износ", net?.let { fmt0(abs(it)) } ?: "—", "MW",
                        fl?.times?.getOrNull(fi)?.let { "${feed?.flowsMeta?.kind?.let(::flowKind) ?: ""} · ${fmtHm(it)}" } ?: "",
                        bg = EColors.Sand, fg = EColors.OnSand) { go(Screen.TRADE) }
                    val eu = feed?.europe?.filter { it.today != null }?.sortedBy { it.today!!.avg }.orEmpty()
                    val rank = eu.indexOfFirst { it.code == "BG" }
                    MiniTile("BG в Европа", if (rank >= 0) "${rank + 1}/${eu.size}" else "—", "",
                        eu.firstOrNull()?.let { "най-евтино: ${it.name}" } ?: "", bg = EColors.Lime, fg = EColors.OnLime) { go(Screen.EUROPE) }
                }
            }
        }
        item {
            Tile(color = EColors.Track, modifier = Modifier.clickable(role = Role.Button) { go(Screen.PVBESS) }) {
                Text("PV + BESS инвестиция →", color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("NPV, IRR, сценарии и чувствителност върху реалните цени за 12 месеца и реално слънце (PVGIS).",
                    color = EColors.Muted, fontFamily = Body, fontSize = 13.sp)
            }
        }
        val wx = feed?.weather.orEmpty()
        if (wx.isNotEmpty()) item {
            val impact = Weather.impact(wx)
            Tile(modifier = Modifier.clickable(role = Role.Button) { go(Screen.PRODUCTION) }) {
                SectionTitle("Времето и енергията", "следващите 24 ч.")
                BodyText(impact.text)
                Fresh(Kind.FORECAST, feed?.weatherMeta, feed?.weatherMeta?.retrievedAt,
                    impact.members.takeIf { it > 0 }?.let { "неопределеност от $it ансамблови члена" })
            }
        }
        val news = data.news
        if (news.isNotEmpty()) item {
            Tile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Последни новини", color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("Всички →", color = EColors.Lime, fontFamily = Body, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(role = Role.Button) { go(Screen.NEWS) }.padding(8.dp))
                }
                news.take(3).forEach { n -> NewsLine(n.title, "${n.source} · ${fmtTime(n.published)}") }
            }
        }
        val vision = news.filter { isVision(it) }
        if (vision.isNotEmpty()) item {
            Tile(color = EColors.Track) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ENERGY VISION", color = EColors.Lime, fontFamily = Display, fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("Още →", color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(role = Role.Button) { go(Screen.VISION) }.padding(8.dp))
                }
                vision.take(2).forEach { n -> NewsLine(n.title, "${n.maturity} · ${n.source}") }
            }
        }
        val fact = kb?.daily()
        if (fact != null) item {
            Tile(color = EColors.Lime, modifier = Modifier.clickable(role = Role.Button) { go(Screen.KNOWLEDGE) }) {
                Text("ЗНАЕТЕ ЛИ, ЧЕ...?", color = EColors.OnLime, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(fact.title, color = EColors.OnLime, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(fact.summary, color = EColors.OnLime, fontFamily = Body, fontSize = 13.sp)
            }
        }
        item { SourceNote(data) }
    }
}

fun flowKind(k: String) = when (k) { "physical" -> "физически"; "commercial" -> "търговски"; else -> k }

fun isVision(n: com.ngerchev.energy.data.NewsItem) =
    n.type != "Регулаторна промяна" && n.categories.any { it in VISION_CATS }

val VISION_CATS = setOf("Ядрен синтез", "Водород", "Батерии", "Слънчеви технологии", "Вятърни технологии",
    "Геотермална енергия", "AI и автоматизация", "Интелигентни мрежи", "Ядрена енергетика", "Водни технологии")

@Composable
private fun androidx.compose.foundation.layout.RowScope.MiniTile(
    label: String, value: String, unit: String, note: String,
    bg: Color = EColors.Tile, fg: Color = EColors.Text, onClick: () -> Unit,
) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(bg)
        .clickable(role = Role.Button, onClick = onClick).padding(14.dp)) {
        Label(label, if (bg == EColors.Tile) EColors.Muted else fg)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Num(value, 20.sp, fg)
            if (unit.isNotEmpty()) Text(" $unit", color = fg, fontSize = 11.sp, fontFamily = Body,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        if (note.isNotEmpty()) Text(note, color = if (bg == EColors.Tile) EColors.Muted else fg, fontSize = 10.sp,
            fontFamily = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NewsLine(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, color = EColors.Muted, fontFamily = Body, fontSize = 11.sp)
    }
}
