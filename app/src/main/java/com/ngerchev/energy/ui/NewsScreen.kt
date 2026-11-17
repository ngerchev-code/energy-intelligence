package com.ngerchev.energy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.NewsItem
import java.time.ZonedDateTime

private val PERIODS = listOf("Днес", "7 дни", "30 дни")
private val MATURITIES = listOf("Изследване", "Лабораторен прототип", "Пилотна демонстрация", "Търговско внедряване")

/** News centre. [vision] = Energy Vision: technologies and innovations only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsScreen(all: List<NewsItem>, vision: Boolean, padding: PaddingValues) {
    val ctx = LocalContext.current
    var period by rememberSaveable { mutableStateOf("7 дни") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var maturity by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    val now = ZonedDateTime.now(EnergyParser.SOFIA)
    val base = if (vision) all.filter { isVision(it) } else all
    val since = when (period) {
        "Днес" -> now.toLocalDate().atStartOfDay(EnergyParser.SOFIA)
        "7 дни" -> now.minusDays(7)
        else -> now.minusDays(30)
    }
    val list = base.filter {
        it.published.isAfter(since) && (cat == null || cat in it.categories) &&
            (maturity == null || it.maturity == maturity) && (country == null || country in it.countries)
    }
    val cats = base.flatMap { it.categories }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(10)
    val countries = base.flatMap { it.countries }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(8)

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vision) item {
            Tile(color = EColors.Lime, radius = 30.dp) {
                Text("ENERGY VISION", color = EColors.OnLime, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Иновации, ноу-хау и технологиите на бъдещето", color = EColors.OnLime, fontFamily = Body, fontSize = 13.sp)
            }
        }
        item { ChipRow(PERIODS, period, { period = it ?: "7 дни" }, allLabel = null) }
        if (cats.isNotEmpty()) item { ChipRow(cats, cat, { cat = it }) }
        if (vision) item { ChipRow(MATURITIES, maturity, { maturity = it }, allLabel = "Всяка зрялост") }
        if (countries.isNotEmpty()) item { ChipRow(countries, country, { country = it }, allLabel = "Всички държави") }
        if (all.isEmpty()) item {
            Notice("Още няма новини", "Новините се събират от сървърната задача от официални RSS канали. Обновете след малко.")
        } else if (list.isEmpty()) item {
            Notice("Няма новини по тези филтри", "Разширете периода или махнете филтър.")
        }
        items(list, key = { it.id }) { n ->
            Tile(modifier = Modifier.clickable(role = Role.Button) { openUrl(ctx, n.link) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    n.categories.forEach { Badge(it, EColors.Sand) }
                    if (vision || n.maturity != "Не е определена") Badge(n.maturity, maturityColor(n.maturity))
                }
                Spacer(Modifier.height(8.dp))
                Text(n.title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                if (n.summaryBg != null) {
                    BodyText(n.summaryBg, size = 13)
                    n.impactBg?.let { Spacer(Modifier.height(4.dp)); BodyText("Значение за България (AI тълкуване): $it", EColors.Muted, 12) }
                    Label("Резюме: ${n.summaryBy ?: "AI"} · оригиналът е на английски")
                } else if (n.summaryOriginal.isNotBlank()) {
                    BodyText(n.summaryOriginal, EColors.Muted, 13)
                    Label("Оригинален текст (EN) · български превод няма – нужен е AI ключ")
                }
                Spacer(Modifier.height(6.dp))
                Label("${n.source} · ${fmtTime(n.published)} · ${n.type} · уместност за БГ: ${n.bgRelevance}" +
                    if (n.countries.isNotEmpty()) " · ${n.countries.joinToString()}" else "")
                Label("Категория, тип и зрялост – автоматична класификация · ↗ отваря източника", EColors.Muted)
            }
        }
    }
}

fun maturityColor(m: String) = when (m) {
    "Изследване" -> EColors.Coral
    "Лабораторен прототип" -> EColors.Sand
    "Пилотна демонстрация" -> EColors.Sky
    "Търговско внедряване" -> EColors.Lime
    else -> EColors.Muted
}
