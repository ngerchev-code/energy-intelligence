package com.ngerchev.energy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val sym = DecimalFormatSymbols(Locale("bg", "BG")).apply { groupingSeparator = ' '; decimalSeparator = ',' }
private val f0 = DecimalFormat("#,##0", sym)
private val f1 = DecimalFormat("#,##0.0", sym)
private val f2 = DecimalFormat("#,##0.00", sym)

fun fmt0(v: Double): String = f0.format(v)
fun fmt1(v: Double): String = f1.format(v)
fun fmt2(v: Double): String = f2.format(v)
fun eur(v: Double) = f0.format(v) + " €"

/** Big rounded tile — the basic building block of the dispatch-board look. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    color: Color = EColors.Tile,
    radius: Dp = 26.dp,
    padding: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(color).padding(padding)
    ) { content() }
}

/** Kept name for older call sites. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Tile(modifier, content = content)

@Composable
fun Label(text: String, color: Color = EColors.Muted, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Body, modifier = modifier)
}

@Composable
fun Num(text: String, size: TextUnit = 22.sp, color: Color = EColors.Text, weight: FontWeight = FontWeight.ExtraBold) {
    Text(text, color = color, fontSize = size, fontWeight = weight, fontFamily = Display, letterSpacing = (-0.5).sp, maxLines = 1)
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = EColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Body,
            modifier = Modifier.weight(1f))
        if (sub != null) Label(sub)
    }
}

/** Small tile with a label and a number, used in rows of 2–3. */
@Composable
fun RowScope.Stat(label: String, value: String, unit: String = "", color: Color = EColors.Text, bg: Color = EColors.Tile) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Label(label, if (bg == EColors.Tile) EColors.Muted else color.copy(alpha = 0.8f))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Num(value, 17.sp, color, FontWeight.SemiBold)
            if (unit.isNotEmpty()) Text(" $unit", color = if (bg == EColors.Tile) EColors.Muted else color,
                fontSize = 11.sp, fontFamily = Body, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
fun StatRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Pill-shaped segmented choice. */
@Composable
fun RowScope.Pill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = when {
            selected -> EColors.OnLime
            enabled -> EColors.Text
            else -> EColors.Muted
        },
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        fontFamily = Body,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(50))
            .background(if (selected) EColors.Lime else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Bar colour: cheap hours mint, expensive sand, the rest muted; negative prices sky. */
fun barColor(v: Double, lo: Double, hi: Double): Color {
    if (v < 0) return EColors.Sky
    val t = if (hi - lo < 1e-6) 0.5 else (v - lo) / (hi - lo)
    return when {
        t < 0.25 -> EColors.Mint
        t > 0.75 -> EColors.Sand
        else -> EColors.BarIdle
    }
}

/** Kept for older call sites: text colour for a price. */
fun priceColor(v: Double, lo: Double, hi: Double): Color {
    if (v < 0) return EColors.Sky
    val t = if (hi - lo < 1e-6) 0.5 else (v - lo) / (hi - lo)
    return when {
        t < 0.25 -> EColors.Mint
        t > 0.75 -> EColors.Sand
        else -> EColors.Text
    }
}

/** Half-circle gauge: where [value] sits between [min] and [max]. */
@Composable
fun Gauge(value: Double, min: Double, max: Double, modifier: Modifier = Modifier, stroke: Dp = 18.dp) {
    val frac = if (max - min < 1e-9) 0.5f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Canvas(modifier) {
        val sw = stroke.toPx()
        val d = min(size.width, size.height * 2) - sw
        val tl = Offset((size.width - d) / 2, sw / 2)
        val sz = Size(d, d)
        drawArc(EColors.Track, 180f, 180f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(EColors.Lime, 180f, 180f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/**
 * Rounded bar chart with an optional dashed average line and hour labels.
 * [highlight] marks one bar in lime (e.g. the current hour).
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    labelEvery: Int = 6,
    highlight: Int? = null,
    colorFor: (Double) -> Color,
    average: Double? = null,
) {
    if (values.isEmpty()) return
    val maxV = max(values.max(), 0.0)
    val minV = min(values.min(), 0.0)
    val range = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            val slot = size.width / n
            val gap = if (slot < 3.dp.toPx()) 0f else (slot * 0.22f).coerceIn(1.dp.toPx(), 4.dp.toPx())
            val zeroY = (maxV / range * size.height).toFloat()
            values.forEachIndexed { i, v ->
                val minH = 4.dp.toPx()
                val c = if (highlight == i) EColors.Lime else colorFor(v)
                val (y, h) = if (v >= 0) {
                    val top = ((maxV - v) / range * size.height).toFloat()
                    val hh = (zeroY - top).coerceAtLeast(minH)
                    (zeroY - hh) to hh
                } else {
                    val bottom = ((maxV - v) / range * size.height).toFloat()
                    zeroY to (bottom - zeroY).coerceAtLeast(minH)
                }
                drawRoundRect(c, Offset(i * slot + gap / 2, y), Size(slot - gap, h), CornerRadius(if (slot < 6.dp.toPx()) 0f else 4.dp.toPx()))
            }
            if (average != null) {
                val y = ((maxV - average) / range * size.height).toFloat()
                drawLine(EColors.Text.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", color = EColors.Muted, fontSize = 10.sp, fontFamily = Body,
                    maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Label, value and a thin rounded bar proportional to value/total. */
@Composable
fun ShareBar(label: String, value: Double, total: Double, color: Color, valueText: String) {
    val share = if (total > 0) (abs(value) / total).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = EColors.Text, fontSize = 14.sp, fontFamily = Body, modifier = Modifier.weight(1f))
            Text(valueText, color = EColors.Text, fontSize = 13.sp, fontFamily = Body, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(EColors.Track)) {
            Box(Modifier.fillMaxWidth(share).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** Stacked rounded columns, one colour per series. */
@Composable
fun StackedChart(
    columns: List<List<Pair<Color, Double>>>,
    labels: List<String>,
    height: Dp = 150.dp,
    labelEvery: Int = 6,
) {
    if (columns.isEmpty()) return
    val maxSum = columns.maxOf { c -> c.sumOf { max(it.second, 0.0) } }.takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / columns.size
            val gap = if (slot < 3.dp.toPx()) 0f else (slot * 0.2f).coerceIn(1.dp.toPx(), 4.dp.toPx())
            columns.forEachIndexed { i, col ->
                var y = size.height
                col.forEach { (c, v) ->
                    val h = (max(v, 0.0) / maxSum * size.height).toFloat()
                    if (h > 0f) {
                        drawRect(c, Offset(i * slot + gap / 2, y - h), Size(slot - gap, h))
                        y -= h
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", color = EColors.Muted, fontSize = 10.sp, fontFamily = Body,
                    maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
    }
}
