package com.ngerchev.energy.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.Analyst
import kotlin.math.max

/** AI analyst: answers built from the downloaded data, facts and interpretation kept apart. */
@Composable
fun AnalystScreen(data: MarketData, padding: PaddingValues) {
    var q by rememberSaveable { mutableIntStateOf(-1) }
    val answer = remember(q, data) { if (q >= 0) Analyst.answer(q, data, data.news) else null }
    val brief = data.feed?.aiBrief

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(radius = 30.dp) {
                Text("AI анализатор", color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                BodyText("Отговорите се смятат от изтеглените данни – всяко число е от източник с час. Тълкуванията " +
                    "са отделени и формулирани като възможност.", EColors.Muted, 13)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Analyst.QUESTIONS.forEachIndexed { i, text ->
                    Text(text, color = if (q == i) EColors.OnLime else EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(if (q == i) EColors.Lime else EColors.Tile)
                            .clickable(role = Role.Button) { q = if (q == i) -1 else i }.padding(14.dp))
                }
            }
        }
        if (answer != null) item {
            Tile {
                SectionTitle(answer.question)
                Badge("ФАКТИ ОТ ДАННИТЕ", EColors.Lime)
                answer.facts.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it") }
                if (answer.inference.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp)); Badge("ТЪЛКУВАНЕ (НЕ Е ФАКТ)", EColors.Coral)
                    answer.inference.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it", EColors.Muted) }
                }
                if (answer.uncertainty.isNotBlank()) { Spacer(Modifier.height(10.dp)); Label("Несигурност: ${answer.uncertainty}") }
                Spacer(Modifier.height(4.dp))
                Label("Данни към ${fmtTime(answer.asOf)} · ${answer.sources.joinToString()}")
            }
        }
        item {
            val sc = remember(data) { Analyst.scenario(data) }
            Tile {
                SectionTitle("Сценарий 24–72 ч.", "€/MWh")
                val maxV = max(sc.maxOfOrNull { it.high } ?: 1.0, 1.0)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val w = size.width / sc.size
                    sc.forEachIndexed { i, h ->
                        val top = (size.height * (1 - h.high / maxV)).toFloat()
                        val bottom = (size.height * (1 - h.low.coerceAtLeast(0.0) / maxV)).toFloat()
                        if (h.known) drawRect(EColors.Lime, Offset(i * w, (size.height * (1 - h.mid / maxV)).toFloat() - 2f), Size(w, 4f))
                        else {
                            drawRect(EColors.Track, Offset(i * w, top), Size(w * 0.9f, (bottom - top).coerceAtLeast(2f)))
                            drawRect(EColors.Sand, Offset(i * w, (size.height * (1 - h.mid / maxV)).toFloat() - 1.5f), Size(w, 3f))
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(EColors.Lime, 8.dp); Spacer(Modifier.width(4.dp)); Label("известни цени (ден напред)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(EColors.Sand, 8.dp); Spacer(Modifier.width(4.dp)); Label("сценарий: медиана и диапазон за същия час, последни 7 дни")
                }
                Fresh(Kind.SCENARIO, data.feed?.pricesMeta, data.fetchedAt, "не е прогноза на борсата")
            }
        }
        if (brief != null) item {
            Tile(color = EColors.Track) {
                SectionTitle("Дневен AI анализ", brief.model)
                Badge("ФАКТИ", EColors.Lime)
                brief.facts.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it") }
                Spacer(Modifier.height(10.dp)); Badge("ТЪЛКУВАНЕ", EColors.Coral)
                brief.inference.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it", EColors.Muted) }
                brief.uncertainty?.let { Spacer(Modifier.height(8.dp)); Label("Несигурност: $it") }
                Label("Генериран ${fmtTime(brief.generatedAt)} от данните в този час")
            }
        } else item {
            Notice("Свободни въпроси към AI – изключено",
                "За дневен AI анализ и превод на новините добавете ANTHROPIC_API_KEY в GitHub Secrets. Ключът остава на " +
                    "сървъра; разходите са по вашата API сметка. Готовите въпроси горе работят и без него.")
        }
    }
}
