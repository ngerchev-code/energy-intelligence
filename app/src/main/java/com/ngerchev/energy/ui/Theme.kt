package com.ngerchev.energy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object EColors {
    val Bg = Color(0xFF0B1220)
    val Card = Color(0xFF131C2E)
    val CardHigh = Color(0xFF1B2740)
    val Teal = Color(0xFF2DD4BF)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Green = Color(0xFF4ADE80)
    val Blue = Color(0xFF60A5FA)
    val Text = Color(0xFFE5E9F0)
    val Muted = Color(0xFF8B97AD)
    val Grid = Color(0xFF26324A)
}

@Composable
fun EnergyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EColors.Teal,
            onPrimary = Color(0xFF00201C),
            secondary = EColors.Amber,
            background = EColors.Bg,
            surface = EColors.Bg,
            surfaceVariant = EColors.Card,
            surfaceContainer = EColors.Card,
            surfaceContainerHigh = EColors.CardHigh,
            onBackground = EColors.Text,
            onSurface = EColors.Text,
            onSurfaceVariant = EColors.Muted,
            outline = EColors.Grid,
            error = EColors.Red,
        ),
        content = content,
    )
}

/** Colour for a generation source, chosen by keywords in its id or name. */
fun sourceColor(key: String): Color {
    val k = key.lowercase()
    return when {
        "nuclear" in k -> Color(0xFFA78BFA)
        "lignite" in k || "brown" in k -> Color(0xFF8D6E63)
        "hard coal" in k || "hard_coal" in k || "coal" in k -> Color(0xFF6B7280)
        "gas" in k -> Color(0xFFFB923C)
        "solar" in k -> EColors.Amber
        "wind" in k -> EColors.Blue
        "hydro" in k || "water" in k -> Color(0xFF38BDF8)
        "biomass" in k || "bio" in k -> EColors.Green
        "oil" in k -> Color(0xFF78716C)
        else -> Color(0xFF94A3B8)
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
        "solar" in k -> "Слънце (ФЕЦ)"
        "wind" in k -> "Вятър"
        "pumped" in k -> "ПАВЕЦ"
        "reservoir" in k -> "ВЕЦ (язовирни)"
        "run" in k && "river" in k -> "ВЕЦ (течащи)"
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
    "mk" to "Северна Македония", "north macedonia" to "Северна Македония",
)

fun countryLabel(id: String, name: String): String =
    countryNames[id.lowercase()] ?: countryNames[name.lowercase()] ?: name
