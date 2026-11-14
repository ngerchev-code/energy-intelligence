package com.ngerchev.energy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val sym = DecimalFormatSymbols(Locale("bg", "BG")).apply { groupingSeparator = ' ' }
private val f0 = DecimalFormat("#,##0", sym)
private val f1 = DecimalFormat("#,##0.0", sym)
private val f2 = DecimalFormat("#,##0.00", sym)

fun fmt0(v: Double) = f0.format(v)
fun fmt1(v: Double) = f1.format(v)
fun fmt2(v: Double) = f2.format(v)
fun eur(v: Double) = "€ " + f0.format(v)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(EColors.Card)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(text, color = EColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = EColors.Muted, fontSize = 12.sp)
    }
}

@Composable
fun RowScope.Stat(label: String, value: String, unit: String = "", color: Color = EColors.Text) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(EColors.CardHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = EColors.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(" $unit", color = EColors.Muted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
fun StatRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Price colour: cheap = green, average = amber, expensive = red, negative = blue. */
fun priceColor(v: Double, lo: Double, hi: Double): Color {
    if (v < 0) return EColors.Blue
    val t = if (hi - lo < 1e-6) 0.5 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    return if (t < 0.5) lerp(EColors.Green, EColors.Amber, (t * 2).toFloat())
    else lerp(EColors.Amber, EColors.Red, ((t - 0.5) * 2).toFloat())
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f
)

/**
 * Bar chart with a zero line, an average line and axis labels every [labelEvery] bars.
 * [highlight] marks one bar (e.g. the current hour).
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    labelEvery: Int = 3,
    highlight: Int? = null,
    colorFor: (Double) -> Color,
    average: Double? = null,
) {
    if (values.isEmpty()) return
    val maxV = max(values.max(), 0.0)
    val minV = min(values.min(), 0.0)
    val range = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.height(height).width(36.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(fmt0(maxV), color = EColors.Muted, fontSize = 9.sp)
                if (minV < 0) Text("0", color = EColors.Muted, fontSize = 9.sp)
                Text(fmt0(minV), color = EColors.Muted, fontSize = 9.sp)
            }
            Canvas(Modifier.weight(1f).height(height)) {
                val n = values.size
                val slot = size.width / n
                val gap = (slot * 0.18f).coerceAtMost(4.dp.toPx())
                val zeroY = (maxV / range * size.height).toFloat()
                values.forEachIndexed { i, v ->
                    val top = ((maxV - max(v, 0.0)) / range * size.height).toFloat()
                    val bottom = ((maxV - min(v, 0.0)) / range * size.height).toFloat()
                    val h = (bottom - top).coerceAtLeast(1.5f)
                    var c = colorFor(v)
                    if (highlight != null && i != highlight) c = c.copy(alpha = 0.55f)
                    drawRoundRect(
                        color = c,
                        topLeft = Offset(i * slot + gap / 2, top),
                        size = Size(slot - gap, h),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
                drawLine(EColors.Grid, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
                if (average != null) {
                    val y = ((maxV - average) / range * size.height).toFloat()
                    drawLine(
                        Color.White.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    if (i % labelEvery == 0) l else "",
                    color = EColors.Muted, fontSize = 9.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Horizontal share bar: label, coloured bar proportional to value/total, value text. */
@Composable
fun ShareBar(label: String, value: Double, total: Double, color: Color, valueText: String) {
    val share = if (total > 0) (abs(value) / total).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(8.dp))
            Text(label, color = EColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = EColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(EColors.CardHigh)) {
            Box(Modifier.fillMaxWidth(share).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** Stacked bar per time step, one colour per series. */
@Composable
fun StackedChart(
    columns: List<List<Pair<Color, Double>>>,
    labels: List<String>,
    height: Dp = 170.dp,
    labelEvery: Int = 4,
) {
    if (columns.isEmpty()) return
    val maxSum = columns.maxOf { c -> c.sumOf { max(it.second, 0.0) } }.takeIf { it > 0 } ?: 1.0
    Column {
        Row {
            Column(Modifier.height(height).width(40.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(fmt0(maxSum), color = EColors.Muted, fontSize = 9.sp)
                Text("0", color = EColors.Muted, fontSize = 9.sp)
            }
            Canvas(Modifier.weight(1f).height(height)) {
                val slot = size.width / columns.size
                val gap = (slot * 0.15f).coerceAtMost(3.dp.toPx())
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
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", color = EColors.Muted, fontSize = 9.sp,
                    maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
    }
}
