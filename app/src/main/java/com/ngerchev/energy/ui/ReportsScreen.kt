package com.ngerchev.energy.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.Report
import com.ngerchev.energy.logic.ReportKind
import com.ngerchev.energy.logic.Reports
import java.io.File

/** Daily / weekly / monthly / annual reports with CSV and PDF export. */
@Composable
fun ReportsScreen(data: MarketData, padding: PaddingValues) {
    val ctx = LocalContext.current
    var kindName by rememberSaveable { mutableStateOf(ReportKind.DAILY.name) }
    val kind = ReportKind.valueOf(kindName)
    val r = remember(kind, data) { Reports.build(kind, data) }

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ChipRow(ReportKind.entries.map { it.label }, kind.label,
                { l -> kindName = (ReportKind.entries.firstOrNull { it.label == l } ?: ReportKind.DAILY).name }, allLabel = null)
        }
        item {
            Tile(radius = 30.dp) {
                SectionTitle(r.title)
                r.lines.forEach { KeyValue(it.first, it.second) }
                Label("Данни към ${fmtTime(r.dataAsOf)} · изготвен ${fmtTime(r.generatedAt)}")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Експорт CSV (Excel)", false) { share(ctx, r, csv = true) }
                    Chip("Експорт PDF", false) { share(ctx, r, csv = false) }
                }
            }
        }
        r.sections.forEach { (title, items) ->
            item { Tile { SectionTitle(title); items.forEach { BodyText("• $it", size = 13) } } }
        }
        if (kind == ReportKind.ANNUAL && data.feed?.yearDaily.isNullOrEmpty())
            item { Notice("Няма годишна история", "Годишните данни идват от сървърната задача веднъж дневно.") }
        item { BodyText("Източници: ${r.sources.joinToString()}", EColors.Muted, 12) }
    }
}

private fun share(ctx: Context, r: Report, csv: Boolean) {
    runCatching {
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val name = "energo-${r.kind.name.lowercase()}-${r.generatedAt.toLocalDate()}"
        val file = if (csv) File(dir, "$name.csv").apply { writeText(Reports.csv(r)) } else File(dir, "$name.pdf").also { pdf(r, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND).setType(if (csv) "text/csv" else "application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, r.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, r.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** A4 PDF: title, key figures, sections, the data table and sources, on as many pages as needed. */
private fun pdf(r: Report, out: File) {
    val doc = PdfDocument()
    val w = 595; val h = 842; val m = 40f
    val title = Paint().apply { textSize = 15f; isFakeBoldText = true; isAntiAlias = true }
    val body = Paint().apply { textSize = 10f; isAntiAlias = true }
    val muted = Paint().apply { textSize = 9f; color = 0xFF555555.toInt(); isAntiAlias = true }
    var pageNo = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
    var y = m
    fun line(text: String, p: Paint, gap: Float = 15f) {
        if (y + gap > h - m) {
            doc.finishPage(page); pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create()); y = m
        }
        page.canvas.drawText(text.take(100), m, y + p.textSize, p); y += gap
    }
    line(r.title, title, 24f)
    line("Данни към ${r.dataAsOf} · изготвен ${r.generatedAt}", muted, 20f)
    r.lines.forEach { line("${it.first}: ${it.second}", body) }
    r.sections.forEach { (t, items) -> y += 8f; line(t, title.apply { textSize = 12f }, 18f); items.forEach { line("• $it", body) } }
    y += 8f
    r.table.forEachIndexed { i, row -> line(row.joinToString("     "), if (i == 0) title.apply { textSize = 11f } else body, 13f) }
    y += 10f
    line("Източници: ${r.sources.joinToString()}", muted)
    line("Енерго BG · Energy Intelligence Bulgaria", muted)
    doc.finishPage(page)
    out.outputStream().use { doc.writeTo(it) }
    doc.close()
}
