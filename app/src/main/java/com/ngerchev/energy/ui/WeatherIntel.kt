package com.ngerchev.energy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.Feed
import com.ngerchev.energy.data.TimePoint
import com.ngerchev.energy.data.WxPoint
import java.time.ZonedDateTime
import kotlin.math.max

data class WxImpact(val text: String, val members: Int)

object Weather {
    private fun next24(p: WxPoint): List<Int> {
        val now = ZonedDateTime.now(EnergyParser.SOFIA).minusHours(1)
        return p.times.indices.filter { p.times[it].isAfter(now) }.take(24)
    }

    /** Facts from the forecast, then a clearly marked estimate of the effect. */
    fun impact(points: List<WxPoint>): WxImpact {
        val solarPts = points.filter { it.name != "Каварна" }
        var peak = 0.0; var peakHour = -1; var lo: Double? = null; var hi: Double? = null
        solarPts.firstOrNull()?.let { ref ->
            next24(ref).forEach { i ->
                val v = solarPts.mapNotNull { it.rad.getOrNull(i) }.average()
                if (!v.isNaN() && v > peak) {
                    peak = v; peakHour = ref.times[i].withZoneSameInstant(EnergyParser.SOFIA).hour
                    lo = solarPts.mapNotNull { it.radP10.getOrNull(i) }.average().takeIf { !it.isNaN() }
                    hi = solarPts.mapNotNull { it.radP90.getOrNull(i) }.average().takeIf { !it.isNaN() }
                }
            }
        }
        val windP = points.firstOrNull { it.name == "Каварна" }
        val wind = windP?.let { p -> next24(p).mapNotNull { p.wind.getOrNull(it) }.average() }?.takeIf { !it.isNaN() }
        val sb = StringBuilder()
        if (peakHour >= 0) {
            sb.append("Слънце: пик около ${"%02d".format(peakHour)}:00 – средно ${fmt0(peak)} W/m² за 5 града")
            if (lo != null && hi != null) sb.append(" (ансамбъл 10–90 %: ${fmt0(lo!!)}–${fmt0(hi!!)})")
            sb.append(". ")
        }
        if (wind != null) sb.append("Вятър при Каварна (100 м): средно ${fmt0(wind)} km/h. ")
        val effect = when {
            peak >= 450 && (wind ?: 0.0) >= 25 -> "силно ВЕИ производство – вероятни по-ниски цени по обед и при вятър"
            peak >= 450 -> "силно слънце – вероятно поевтиняване в обедните часове"
            peak in 1.0..199.9 -> "слабо слънце – по-малко ФЕЦ и по-малък обеден спад на цените"
            (wind ?: 0.0) >= 30 -> "силен вятър – повече ВЕП производство"
            else -> null
        }
        if (effect != null) sb.append("Оценка (не прогноза на цената): $effect.")
        return WxImpact(sb.toString().ifBlank { "Няма прогноза за следващите 24 ч." },
            points.maxOfOrNull { it.members } ?: 0)
    }
}

/** Load: actual and forecast on one chart. */
@Composable
fun LoadTile(feed: Feed) {
    if (feed.loadActual.isEmpty() && feed.loadForecast.isEmpty()) {
        Notice("Потребление и прогноза", "Нужни са данни от ENTSO-E (ENTSOE_TOKEN). Резервният източник не ги дава за България.")
        return
    }
    val today = ZonedDateTime.now(EnergyParser.SOFIA).toLocalDate()
    val act = feed.loadActual.filter { it.time.toLocalDate() == today }
    val fc = feed.loadForecast.filter { it.time.toLocalDate() == today || it.time.toLocalDate() == today.plusDays(1) }
    Tile {
        SectionTitle("Потребление", "MW")
        act.lastOrNull()?.let { Num(fmt0(it.value) + " MW", 26.sp) }
        Spacer(Modifier.height(8.dp))
        LineChart(listOf(act to EColors.Lime, fc to EColors.Sand))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(EColors.Lime, 8.dp); Spacer(Modifier.width(4.dp)); Label("измерено")
            Spacer(Modifier.width(12.dp))
            Dot(EColors.Sand, 8.dp); Spacer(Modifier.width(4.dp)); Label("прогноза ЕСО/ENTSO-E (днес–утре)")
        }
        Fresh(measuredKind(act.lastOrNull()?.time), feed.loadMeta, act.lastOrNull()?.time)
    }
}

/** Wind and solar forecast (ENTSO-E). */
@Composable
fun ResForecastTile(feed: Feed) {
    val set = feed.resForecast ?: return
    Tile {
        SectionTitle("Прогноза за слънце и вятър", "MW")
        val lines = set.series.map { s ->
            set.times.indices.mapNotNull { i -> s.values[i]?.let { TimePoint(set.times[i], it) } } to sourceColor(s.id + " " + s.name)
        }
        LineChart(lines)
        set.series.forEach { s ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(sourceColor(s.id + " " + s.name), 8.dp); Spacer(Modifier.width(6.dp)); Label(sourceLabel(s.id, s.name))
            }
        }
        Fresh(Kind.FORECAST, feed.resMeta, feed.resMeta?.retrievedAt)
    }
}

@Composable
fun WeatherTile(feed: Feed) {
    val pts = feed.weather
    if (pts.isEmpty()) return
    Tile {
        SectionTitle("Времето и ВЕИ", "72 ч.")
        BodyText(Weather.impact(pts).text)
        Spacer(Modifier.height(10.dp))
        val ref = pts.first()
        val rad = ref.times.indices.mapNotNull { i ->
            pts.filter { it.name != "Каварна" }.mapNotNull { it.rad.getOrNull(i) }.average().takeIf { !it.isNaN() }
                ?.let { TimePoint(ref.times[i], it) }
        }
        val p10 = ref.times.indices.mapNotNull { i -> ref.radP10.getOrNull(i)?.let { TimePoint(ref.times[i], it) } }
        val p90 = ref.times.indices.mapNotNull { i -> ref.radP90.getOrNull(i)?.let { TimePoint(ref.times[i], it) } }
        Label("Слънчева радиация, W/m² (лайм – средно; сиво – диапазон 10–90 % на ансамбъла за ${ref.name})")
        LineChart(listOf(rad to EColors.Lime, p10 to EColors.Muted, p90 to EColors.Muted), height = 110)
        Spacer(Modifier.height(10.dp))
        pts.forEach { p ->
            val i = p.times.indexOfFirst { it.isAfter(ZonedDateTime.now(EnergyParser.SOFIA).minusHours(1)) }.coerceAtLeast(0)
            KeyValue(p.name, "${p.temp.getOrNull(i)?.let { fmt0(it) } ?: "—"} °C · облаци ${p.cloud.getOrNull(i)?.let { fmt0(it) } ?: "—"} % · вятър ${p.wind.getOrNull(i)?.let { fmt0(it) } ?: "—"} km/h")
        }
        Fresh(Kind.FORECAST, feed.weatherMeta, feed.weatherMeta?.retrievedAt)
    }
}

/** Simple multi-line chart on a shared time axis. */
@Composable
fun LineChart(lines: List<Pair<List<TimePoint>, androidx.compose.ui.graphics.Color>>, height: Int = 130) {
    val all = lines.flatMap { it.first }
    if (all.size < 2) return
    val t0 = all.minOf { it.time.toEpochSecond() }
    val t1 = all.maxOf { it.time.toEpochSecond() }.coerceAtLeast(t0 + 1)
    val vMax = max(all.maxOf { it.value }, 0.0).takeIf { it > 0 } ?: 1.0
    val vMin = minOf(all.minOf { it.value }, 0.0)
    Column {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            lines.forEach { (pts, c) ->
                if (pts.size < 2) return@forEach
                val path = Path()
                pts.sortedBy { it.time }.forEachIndexed { i, p ->
                    val x = ((p.time.toEpochSecond() - t0).toFloat() / (t1 - t0)) * size.width
                    val y = (((vMax - p.value) / (vMax - vMin)) * size.height).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c, style = Stroke(width = 2.5.dp.toPx()))
            }
            drawLine(EColors.Track, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(fmtTime(all.minBy { it.time }.time), color = EColors.Muted, fontSize = 10.sp, fontFamily = Body, modifier = Modifier.weight(1f))
            Text("макс ${fmt0(vMax)}", color = EColors.Muted, fontSize = 10.sp, fontFamily = Body, modifier = Modifier.weight(1f))
            Text(fmtTime(all.maxBy { it.time }.time), color = EColors.Muted, fontSize = 10.sp, fontFamily = Body)
        }
    }
}
