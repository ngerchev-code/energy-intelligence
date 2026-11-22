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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyRepository
import com.ngerchev.energy.data.MarketData

/** All twelve sections. */
enum class Screen(val title: String, val short: String) {
    HOME("Табло", "Табло"),
    MARKET("Енергиен пазар", "Пазар"),
    EUROPE("Европа и Балкани", "Европа"),
    PRODUCTION("Производство", "Производство"),
    TRADE("Пренос и търговия", "Пренос"),
    BESS("BESS анализатор", "BESS"),
    PVBESS("PV + BESS инвестиция", "PV+BESS"),
    NEWS("Новини", "Новини"),
    VISION("Energy Vision", "Vision"),
    KNOWLEDGE("Знаете ли, че...?", "Знаете ли"),
    TUTORIAL("Самоучител", "Самоучител"),
    ANALYST("AI анализатор", "AI"),
    REPORTS("Отчети", "Отчети"),
    UPDATE("Обновяване", "Обновяване"),
    SETTINGS("Настройки", "Настройки"),
    MORE("Всички раздели", "Още"),
}

/** Grid of every section, opened from the dock's "Още". */
@Composable
fun MoreScreen(padding: PaddingValues, go: (Screen) -> Unit) {
    val list = Screen.entries.filter { it != Screen.MORE }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), contentPadding = screenPadding(padding),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list) { s ->
            val accent = s == Screen.VISION || s == Screen.KNOWLEDGE || s == Screen.TUTORIAL
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(if (accent) EColors.Lime else EColors.Tile)
                .clickable(role = Role.Button) { go(s) }.padding(16.dp)) {
                Text("%02d".format(s.ordinal + 1), color = if (accent) EColors.OnLime else EColors.Muted,
                    fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Text(s.title, color = if (accent) EColors.OnLime else EColors.Text, fontFamily = Body,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(data: MarketData?, theme: Int, onTheme: (Int) -> Unit, padding: PaddingValues) {
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    val feed = data?.feed
    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile {
                SectionTitle("Тема")
                ChipRow(listOf("Системна", "Тъмна", "Светла"), listOf("Системна", "Тъмна", "Светла")[theme],
                    { l -> onTheme(listOf("Системна", "Тъмна", "Светла").indexOf(l).coerceAtLeast(0)) }, allLabel = null)
            }
        }
        item {
            Tile {
                SectionTitle("Източници на данни", feed?.let { "сървър: ${fmtTime(it.generatedAt)}" })
                if (feed == null) {
                    BodyText("Сървърните данни не са заредени – приложението ползва директно Energy-Charts.", EColors.Muted, 13)
                } else feed.sources.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(when (s.status) { "ok" -> EColors.Lime; "degraded" -> EColors.Sand; "not_configured" -> EColors.Muted; else -> EColors.Coral }, 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(when (s.status) {
                                "ok" -> "работи · ${fmtTime(s.lastSuccess)}"
                                "degraded" -> "частично · ${s.message ?: ""}"
                                "not_configured" -> "не е настроен · ${s.message ?: ""}"
                                else -> "грешка · ${s.message ?: ""}"
                            }, color = EColors.Muted, fontFamily = Body, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
        item {
            Notice("Ключове (само на сървъра)",
                "ENTSOE_TOKEN – цени, товар, прогнози за ВЕИ, физически потоци от ENTSO-E.\n" +
                    "ANTHROPIC_API_KEY (по желание) – български резюмета на новините и дневен AI анализ.\n" +
                    "Добавят се в GitHub → Settings → Secrets and variables → Actions. Приложението никога не съдържа ключове.")
        }
        item {
            Tile {
                SectionTitle("Energy Intelligence Bulgaria", "версия $version")
                BodyText("Цени ден напред, производство, обмен, сравнение с Европа, времето, BESS, новини, Energy Vision, " +
                    "справочник и отчети. Данните се обновяват на сървъра на всеки 15 минути.", size = 13)
                Spacer(Modifier.height(8.dp))
                LinkText("ENTSO-E Transparency Platform", "https://transparency.entsoe.eu")
                LinkText("Energy-Charts (Fraunhofer ISE)", "https://www.energy-charts.info")
                LinkText("Open-Meteo", "https://open-meteo.com")
                LinkText("Данни (JSON) и състояние", EnergyRepository.FEED + "/status.json")
                data?.license?.let { Label("Лиценз на цените: $it") }
            }
        }
    }
}
