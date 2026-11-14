package com.ngerchev.energy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ngerchev.energy.R

/** "Dispatch board" identity: deep green-graphite, lime signal, warm sand, Unbounded + Manrope. */
object EColors {
    val Bg = Color(0xFF0E1915)
    val Tile = Color(0xFF17261F)
    val Track = Color(0xFF24382E)
    val BarIdle = Color(0xFF2E463A)
    val Lime = Color(0xFFC6F432)
    val Sand = Color(0xFFF2E9DC)
    val Mint = Color(0xFF7CC8A8)
    val Coral = Color(0xFFFF8A6B)
    val Sky = Color(0xFF8EC5FF)
    val Text = Color(0xFFEAF2EC)
    val Muted = Color(0xFF9FB3A8)
    val OnLime = Color(0xFF0E1915)
    val OnSand = Color(0xFF2A1A0A)

    // Aliases kept for older call sites.
    val Teal = Lime
    val Amber = Sand
    val Green = Mint
    val Red = Coral
    val Blue = Sky
    val Card = Tile
    val CardHigh = Track
    val Grid = Track
}

val Display = FontFamily(
    Font(R.font.unbounded_regular, FontWeight.Normal),
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_extrabold, FontWeight.ExtraBold),
)

val Body = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private val base = Typography()
private fun TextStyle.body() = copy(fontFamily = Body)

@Composable
fun EnergyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EColors.Lime,
            onPrimary = EColors.OnLime,
            secondary = EColors.Sand,
            background = EColors.Bg,
            surface = EColors.Bg,
            surfaceVariant = EColors.Tile,
            surfaceContainer = EColors.Tile,
            surfaceContainerHigh = EColors.Track,
            onBackground = EColors.Text,
            onSurface = EColors.Text,
            onSurfaceVariant = EColors.Muted,
            outline = EColors.Track,
            error = EColors.Coral,
        ),
        typography = Typography(
            displayLarge = base.displayLarge.copy(fontFamily = Display),
            headlineMedium = base.headlineMedium.copy(fontFamily = Display),
            titleLarge = base.titleLarge.body(),
            titleMedium = base.titleMedium.body(),
            bodyLarge = base.bodyLarge.body(),
            bodyMedium = base.bodyMedium.body(),
            bodySmall = base.bodySmall.body(),
            labelLarge = base.labelLarge.body(),
            labelMedium = base.labelMedium.body(),
            labelSmall = base.labelSmall.body(),
        ),
        content = content,
    )
}

/** Colour for a generation source, chosen by keywords in its id or name. */
fun sourceColor(key: String): Color {
    val k = key.lowercase()
    return when {
        "nuclear" in k -> EColors.Lime
        "lignite" in k || "brown" in k -> Color(0xFFB08968)
        "coal" in k -> Color(0xFF7D8A84)
        "gas" in k -> EColors.Coral
        "solar" in k -> Color(0xFFFFD166)
        "wind" in k -> EColors.Sky
        "hydro" in k || "water" in k -> EColors.Mint
        "biomass" in k || "bio" in k -> Color(0xFF9BD17A)
        "oil" in k -> Color(0xFF8A7F72)
        else -> EColors.Sand
    }
}

/** Bulgarian label for a generation source. */
fun sourceLabel(id: String, name: String): String {
    val k = "$id $name".lowercase()
    return when {
        "nuclear" in k -> "АЕЦ"
        "lignite" in k || "brown" in k -> "Лигнитни ТЕЦ"
        "hard coal" in k || "hard_coal" in k -> "Каменни въглища"
        "gas" in k -> "Природен газ"
        "solar" in k -> "Слънце"
        "wind" in k -> "Вятър"
        "pumped" in k -> "ПАВЕЦ"
        "reservoir" in k -> "ВЕЦ язовирни"
        "run" in k && "river" in k -> "ВЕЦ течащи"
        "hydro" in k -> "ВЕЦ"
        "biomass" in k -> "Биомаса"
        "oil" in k -> "Нефт"
        "waste" in k -> "Отпадъци"
        "geothermal" in k -> "Геотермална"
        "other" in k -> "Други"
        else -> name
    }
}

private val countryNames = mapOf(
    "ro" to "Румъния", "romania" to "Румъния",
    "gr" to "Гърция", "greece" to "Гърция",
    "tr" to "Турция", "turkey" to "Турция", "türkiye" to "Турция",
    "rs" to "Сърбия", "serbia" to "Сърбия",
    "mk" to "Сев. Македония", "north macedonia" to "Сев. Македония",
)

fun countryLabel(id: String, name: String): String =
    countryNames[id.lowercase()] ?: countryNames[name.lowercase()] ?: name
