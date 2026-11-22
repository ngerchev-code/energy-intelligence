package com.ngerchev.energy.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/** A published version on GitHub Releases (the app's only distribution channel). */
data class UpdateInfo(val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long,
                      val published: ZonedDateTime?)

/**
 * Checks the public GitHub release, downloads the signed APK and hands it to Android's installer.
 * Android always asks the user to confirm an install; an app cannot install itself silently.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/ngerchev-code/energy-intelligence/releases/latest"
    private const val APK = "EnergyIntelligence.apk"

    fun currentCode(ctx: Context): Int {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** "v1.0.12" → 12 (the version code is the build number). */
    fun codeOf(tag: String): Int? = tag.trim().removePrefix("v").split(".").lastOrNull()?.toIntOrNull()

    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val code = codeOf(o.optString("tag_name")) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK }
            ?: return null
        return UpdateInfo(
            code = code, name = o.optString("tag_name").removePrefix("v"),
            notes = o.optString("body").trim(), apkUrl = apk.optString("browser_download_url"), size = apk.optLong("size"),
            published = runCatching { ZonedDateTime.parse(o.optString("published_at")) }.getOrNull(),
        )
    }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "EnergyIntelligenceBulgaria")
        try {
            if (c.responseCode != 200) error("GitHub отговори с код ${c.responseCode}")
            parse(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    fun file(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, APK)

    /** Downloads the APK (GitHub redirects to its file host); progress 0..1. */
    suspend fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        var url = URL(info.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "EnergyIntelligenceBulgaria")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                require(next.protocol == "https") { "Отказан небезопасен адрес" }
                url = next
                continue
            }
            if (code != 200) error("Изтеглянето върна код $code")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        val out = file(ctx)
        val tmp = File(out.parentFile, "$APK.part")
        try {
            c.inputStream.use { inp ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally { c.disconnect() }
        if (info.size > 0 && tmp.length() != info.size) { tmp.delete(); error("Файлът е непълен – опитайте пак") }
        out.delete(); tmp.renameTo(out)
        out
    }

    /** Android 8+: the user must allow this app to install updates once. */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
