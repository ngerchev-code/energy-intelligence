package com.ngerchev.energy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.data.Meta
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("d.MM HH:mm")
private val hmOnly = DateTimeFormatter.ofPattern("HH:mm")

fun fmtTime(t: ZonedDateTime?): String = t?.withZoneSameInstant(EnergyParser.SOFIA)?.format(hm) ?: "—"
fun fmtHm(t: ZonedDateTime?): String = t?.withZoneSameInstant(EnergyParser.SOFIA)?.format(hmOnly) ?: "—"

/** What kind of data a block holds, in words a user can trust. */
enum class Kind(val label: String) { LIVE("НА ЖИВО"), RECENT("ПОСЛЕДНО ИЗМЕРЕНО"), HISTORY("ИСТОРИЯ"),
    DAY_AHEAD("ДЕН НАПРЕД"), FORECAST("ПРОГНОЗА"), SCENARIO("СЦЕНАРИЙ"), EDU("СПРАВКА") }

/** Measured data older than 2 hours is never called live. */
fun measuredKind(last: ZonedDateTime?): Kind {
    if (last == null) return Kind.HISTORY
    val age = Duration.between(last, ZonedDateTime.now(EnergyParser.SOFIA)).toMinutes()
    return when {
        age <= 120 -> Kind.LIVE
        age <= 24 * 60 -> Kind.RECENT
        else -> Kind.HISTORY
    }
}

fun kindColor(k: Kind): Color = when (k) {
    Kind.LIVE -> EColors.Lime
    Kind.RECENT -> EColors.Sand
    Kind.DAY_AHEAD -> EColors.Sky
    Kind.FORECAST, Kind.SCENARIO -> EColors.Coral
    else -> EColors.Muted
}

/** Small badge + "source · time" line, attached to every data block. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Fresh(kind: Kind, meta: Meta?, at: ZonedDateTime? = null, extra: String? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp)) {
        Badge(kind.label, kindColor(kind))
        val parts = listOfNotNull(
            meta?.source,
            at?.let { "към ${fmtTime(it)}" },
            meta?.resolution?.let { resLabel(it) },
            extra,
        )
        Text(parts.joinToString(" · "), color = EColors.Muted, fontSize = 11.sp, fontFamily = Body,
            modifier = Modifier.align(Alignment.CenterVertically))
    }
}

fun resLabel(r: String) = when (r) {
    "PT15M" -> "15 мин."
    "PT30M" -> "30 мин."
    "PT60M" -> "1 час"
    "P1D" -> "ден"
    else -> r
}

@Composable
fun Badge(text: String, color: Color) {
    Text(text, color = if (color.luminance() > 0.45f) EColors.OnLime else Color(0xFFF4F6F2), fontSize = 10.sp,
        fontWeight = FontWeight.Bold, fontFamily = Body,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) EColors.OnLime else EColors.Text, fontSize = 13.sp, fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) EColors.Lime else EColors.Tile)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(options: List<String>, selected: String?, onPick: (String?) -> Unit, allLabel: String? = "Всички") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allLabel != null) Chip(allLabel, selected == null) { onPick(null) }
        options.forEach { o -> Chip(o, selected == o) { onPick(if (selected == o) null else o) } }
    }
}

/** A plain notice tile: missing configuration, missing data, etc. */
@Composable
fun Notice(title: String, text: String, color: Color = EColors.Tile) {
    Tile(color = color) {
        Text(title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.padding(top = 4.dp))
        Text(text, color = EColors.Muted, fontFamily = Body, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
fun BodyText(text: String, color: Color = EColors.Text, size: Int = 14) {
    Text(text, color = color, fontFamily = Body, fontSize = size.sp, lineHeight = (size * 1.45).sp)
}

@Composable
fun LinkText(text: String, url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { openUrl(ctx, url) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("↗", color = EColors.Lime, fontFamily = Body, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = EColors.Text, fontFamily = Body, fontSize = 14.sp)
    }
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun shareText(ctx: Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = EColors.Muted, fontSize = 14.sp, fontFamily = Body, modifier = Modifier.weight(1f))
        Text(value, color = EColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Body)
    }
}

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
