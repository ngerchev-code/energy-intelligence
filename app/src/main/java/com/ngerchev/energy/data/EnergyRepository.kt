package com.ngerchev.energy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Downloads public data from Energy-Charts (Fraunhofer ISE) for Bulgaria and keeps the last
 * successful answers on the phone, so the app still shows something without internet.
 */
class EnergyRepository(context: Context) {

    private val prefs = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    /** Server feed first (ENTSO-E, weather, news); the direct Energy-Charts download stays as fallback. */
    suspend fun load(): MarketData = withContext(Dispatchers.IO) {
        val feedBody = fetchAny("feed", listOf("$FEED/feed.json"))
        val feed = feedBody?.let { runCatching { FeedParser.parse(it.body) }.getOrNull() }
        val news = fetchAny("news", listOf("$FEED/news.json"))
            ?.let { runCatching { FeedParser.parseNews(it.body) }.getOrNull() }.orEmpty()
        val year = fetchFile("prices_365.json")?.let { runCatching { FeedParser.parsePriceYear(it) }.getOrNull() }
        val pv = fetchFile("pv_profiles.json")?.let { runCatching { FeedParser.parsePv(it) }.getOrNull() }
        val pvYears = fetchFile("pv_years.json")?.let { runCatching { FeedParser.parsePv(it) }.getOrNull() }
        val lessons = fetchFile("lessons.json")?.let { runCatching { FeedParser.parseLessons(it) }.getOrNull() }.orEmpty()
        if (feed != null && feed.prices.isNotEmpty()) {
            val tomorrow = LocalDate.now(EnergyParser.SOFIA).plusDays(1)
            return@withContext MarketData(
                prices30d = feed.prices,
                tomorrow = feed.prices.filter { it.time.toLocalDate() == tomorrow },
                generation = feed.generation,
                flows = feed.flows,
                license = feed.pricesMeta?.license,
                fetchedAt = feed.generatedAt,
                fromCache = feedBody.cached,
                feed = feed,
                news = news,
                year = year,
                pv = pv,
                pvYears = pvYears,
                lessons = lessons,
            )
        }
        loadDirect().copy(feed = feed, news = news, year = year, pv = pv, pvYears = pvYears, lessons = lessons)
    }

    private suspend fun loadDirect(): MarketData = withContext(Dispatchers.IO) {
        val today = LocalDate.now(EnergyParser.SOFIA)
        val from = today.minusDays(30)
        val tomorrow = today.plusDays(1)
        coroutineScope {
            val prices = async { fetchAny("prices", listOf(
                "$BASE/v2/price?bzn=BG&start=$from&end=$today",
                "$BASE/price?bzn=BG&start=$from&end=$today",
            )) }
            val next = async { fetchAny("tomorrow", listOf(
                "$BASE/v2/price?bzn=BG&start=$tomorrow&end=$tomorrow",
                "$BASE/price?bzn=BG&start=$tomorrow&end=$tomorrow",
            )) }
            val gen = async { fetchAny("generation", listOf(
                "$BASE/v2/public_power?country=bg&start=$today&end=$today",
                "$BASE/public_power?country=bg&start=$today&end=$today",
            )) }
            val flows = async { fetchAny("flows", listOf(
                "$BASE/v2/cbet?country=bg&start=$today&end=$today",
                "$BASE/cbet?country=bg&start=$today&end=$today",
            )) }

            val p = prices.await() ?: throw java.io.IOException("Няма връзка с източника на данни")
            val nextJson = next.await()
            val genJson = gen.await()
            val flowsJson = flows.await()
            MarketData(
                prices30d = EnergyParser.parsePrices(p.body),
                tomorrow = nextJson?.takeUnless { it.cached }
                    ?.let { runCatching { EnergyParser.parsePrices(it.body) }.getOrNull() }
                    ?.filter { it.time.toLocalDate() == tomorrow }
                    .orEmpty(),
                generation = genJson?.let { runCatching { EnergyParser.parseGeneration(it.body) }.getOrNull() },
                flows = flowsJson?.let { runCatching { EnergyParser.parseFlows(it.body) }.getOrNull() },
                license = EnergyParser.license(p.body),
                fetchedAt = ZonedDateTime.now(EnergyParser.SOFIA),
                fromCache = p.cached,
            )
        }
    }

    private val dir = java.io.File(context.filesDir, "feed").apply { mkdirs() }

    /** Large files are cached on disk, not in preferences. Falls back to the last saved copy. */
    private fun fetchFile(name: String): String? {
        val f = java.io.File(dir, name)
        val fresh = runCatching { get("$FEED/$name") }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }
        if (fresh != null) { runCatching { f.writeText(fresh) }; return fresh }
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    private data class Body(val body: String, val cached: Boolean)

    /** Tries each URL in turn; falls back to the cached answer when all fail. */
    private fun fetchAny(key: String, urls: List<String>): Body? {
        for (u in urls) {
            val body = runCatching { get(u) }.getOrNull() ?: continue
            if (!looksValid(body)) continue
            prefs.edit().putString(key, body).apply()
            return Body(body, false)
        }
        return prefs.getString(key, null)?.let { Body(it, true) }
    }

    private fun looksValid(body: String) =
        body.trimStart().startsWith("{") &&
            (body.contains("\"data\"") || body.contains("unix_seconds") || body.contains("\"schema\"") || body.contains("\"items\""))

    /** GET with manual redirects (Android does not follow https -> http on its own). */
    private fun get(url: String, hops: Int = 0): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "EnergyIntelligenceBulgaria/1.0 (Android)")
        try {
            val code = c.responseCode
            if (code in 300..399 && hops < 3) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code")
                return get(URL(URL(url), loc).toString(), hops + 1)
            }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://api.energy-charts.info"
        /** Published every 15 min by the data-sync workflow; contains no credentials. */
        const val FEED = "https://raw.githubusercontent.com/ngerchev-code/energy-intelligence/data/feed"
    }
}
