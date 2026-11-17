package com.ngerchev.energy.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Where a data set came from and what kind of data it is. Shown next to every number. */
data class Meta(
    val source: String,
    val sourceUrl: String,
    val kind: String,          // day-ahead | actual | forecast | physical | commercial | historical
    val unit: String,
    val resolution: String?,
    val retrievedAt: ZonedDateTime?,
    val license: String?,
)

data class TimePoint(val time: ZonedDateTime, val value: Double)

data class SourceStatus(
    val id: String, val name: String, val url: String, val status: String,
    val lastSuccess: ZonedDateTime?, val message: String?,
)

data class DayStat(val avg: Double, val min: Double, val max: Double)

data class ZoneStat(
    val code: String, val name: String, val source: String, val resolution: String?,
    val today: DayStat?, val tomorrow: DayStat?, val daily: List<Pair<String, Double>>,
)

data class WxPoint(
    val name: String,
    val times: List<ZonedDateTime>,
    val temp: List<Double?>, val rad: List<Double?>, val cloud: List<Double?>,
    val wind: List<Double?>, val wdir: List<Double?>, val precip: List<Double?>,
    val radP10: List<Double?>, val radP90: List<Double?>,
    val members: Int,
)

data class AiBrief(val facts: List<String>, val inference: List<String>, val uncertainty: String?,
                   val generatedAt: ZonedDateTime?, val model: String?)

data class Feed(
    val generatedAt: ZonedDateTime,
    val sources: List<SourceStatus>,
    val pricesMeta: Meta?,
    val prices: List<PricePoint>,
    val yearMeta: Meta?,
    val yearDaily: List<Pair<String, Double>>,
    val genMeta: Meta?,
    val generation: SeriesSet?,
    val loadMeta: Meta?,
    val loadActual: List<TimePoint>,
    val loadForecast: List<TimePoint>,
    val resMeta: Meta?,
    val resForecast: SeriesSet?,
    val flowsMeta: Meta?,
    val flows: SeriesSet?,
    val europe: List<ZoneStat>,
    val weatherMeta: Meta?,
    val weather: List<WxPoint>,
    val aiBrief: AiBrief?,
) {
    fun source(id: String) = sources.firstOrNull { it.id == id }
    val entsoeActive get() = source("entsoe")?.status in setOf("ok", "degraded")
    val entsoeConfigured get() = source("entsoe")?.status != "not_configured"
}

data class NewsItem(
    val id: String, val title: String, val summaryOriginal: String, val link: String,
    val source: String, val tier: String, val published: ZonedDateTime,
    val categories: List<String>, val type: String, val maturity: String,
    val countries: List<String>, val bgRelevance: String,
    val summaryBg: String?, val impactBg: String?, val summaryBy: String?,
)

object FeedParser {

    private fun time(s: String?): ZonedDateTime? =
        s?.takeIf { it.isNotBlank() }?.let {
            runCatching { OffsetDateTime.parse(it.replace(Regex("Z$"), "+00:00").let(::fixSeconds)).atZoneSameInstant(EnergyParser.SOFIA) }.getOrNull()
        }

    /** "2026-11-17T12:00+00:00" lacks seconds; OffsetDateTime accepts that, but keep it explicit. */
    private fun fixSeconds(s: String) = s

    private fun meta(o: JSONObject?): Meta? = o?.let {
        Meta(
            source = it.optString("source"),
            sourceUrl = it.optString("source_url"),
            kind = it.optString("kind"),
            unit = it.optString("unit"),
            resolution = it.optString("resolution").takeIf { r -> r.isNotBlank() && r != "null" },
            retrievedAt = time(it.optString("retrieved_at")),
            license = it.optString("license").takeIf { l -> l.isNotBlank() && l != "null" },
        )
    }

    private fun points(a: JSONArray?): List<TimePoint> {
        if (a == null) return emptyList()
        val out = ArrayList<TimePoint>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            if (p.isNull(1)) continue
            val t = time(p.optString(0)) ?: continue
            out.add(TimePoint(t, p.optDouble(1)))
        }
        return out
    }

    /** {"name": [[t, v], ...], ...} -> a SeriesSet on the union of timestamps. */
    private fun seriesSet(o: JSONObject?): SeriesSet? {
        if (o == null || o.length() == 0) return null
        val byName = LinkedHashMap<String, Map<ZonedDateTime, Double>>()
        o.keys().forEach { k -> byName[k] = points(o.optJSONArray(k)).associate { it.time to it.value } }
        val times = byName.values.flatMap { it.keys }.distinct().sorted()
        if (times.isEmpty()) return null
        return SeriesSet(times, byName.map { (k, m) -> Series(k.lowercase(), k, times.map { m[it] }) })
    }

    private fun nums(a: JSONArray?): List<Double?> =
        if (a == null) emptyList() else (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it) }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun dayStat(o: JSONObject?) = o?.let { DayStat(it.optDouble("avg"), it.optDouble("min"), it.optDouble("max")) }

    private fun pairs(a: JSONArray?): List<Pair<String, Double>> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            a.optJSONArray(i)?.let { it.optString(0) to it.optDouble(1) }
        }

    fun parse(json: String): Feed {
        val o = JSONObject(json)
        val bg = o.optJSONObject("bg") ?: JSONObject()
        val prices = bg.optJSONObject("prices")
        val year = bg.optJSONObject("daily_365")
        val gen = bg.optJSONObject("generation")
        val load = bg.optJSONObject("load")
        val res = bg.optJSONObject("res_forecast")
        val flows = bg.optJSONObject("flows")
        val eu = o.optJSONObject("europe")?.optJSONObject("zones")
        val wx = o.optJSONObject("weather")
        val ai = o.optJSONObject("ai_brief")

        val sources = o.optJSONArray("sources")?.let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                SourceStatus(s.optString("id"), s.optString("name"), s.optString("url"), s.optString("status"),
                    time(s.optString("last_success")), s.optString("message").takeIf { it.isNotBlank() })
            }
        }.orEmpty()

        return Feed(
            generatedAt = time(o.optString("generated_at")) ?: ZonedDateTime.now(EnergyParser.SOFIA),
            sources = sources,
            pricesMeta = meta(prices),
            prices = points(prices?.optJSONArray("points")).map { PricePoint(it.time, it.value) },
            yearMeta = meta(year),
            yearDaily = pairs(year?.optJSONArray("days")),
            genMeta = meta(gen),
            generation = seriesSet(gen?.optJSONObject("series")),
            loadMeta = meta(load) ?: gen?.takeIf { it.has("load_actual") }?.let { meta(it) },
            loadActual = points(load?.optJSONArray("actual")).ifEmpty { points(gen?.optJSONArray("load_actual")) },
            loadForecast = points(load?.optJSONArray("forecast")),
            resMeta = meta(res),
            resForecast = seriesSet(res?.optJSONObject("series")),
            flowsMeta = meta(flows),
            flows = seriesSet(flows?.optJSONObject("neighbours")),
            europe = eu?.keys()?.asSequence()?.map { k ->
                val z = eu.getJSONObject(k)
                ZoneStat(k, z.optString("name"), z.optString("source"), z.optString("resolution").takeIf { it != "null" },
                    dayStat(z.optJSONObject("today")), dayStat(z.optJSONObject("tomorrow")), pairs(z.optJSONArray("daily")))
            }?.toList().orEmpty(),
            weatherMeta = meta(wx),
            weather = wx?.optJSONArray("points")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    WxPoint(
                        name = p.optString("name"),
                        times = strings(p.optJSONArray("time")).mapNotNull { time(it) },
                        temp = nums(p.optJSONArray("temp")), rad = nums(p.optJSONArray("rad")),
                        cloud = nums(p.optJSONArray("cloud")), wind = nums(p.optJSONArray("wind")),
                        wdir = nums(p.optJSONArray("wdir")), precip = nums(p.optJSONArray("precip")),
                        radP10 = nums(p.optJSONArray("rad_p10")), radP90 = nums(p.optJSONArray("rad_p90")),
                        members = p.optInt("ens_members", 0),
                    )
                }
            }.orEmpty(),
            aiBrief = ai?.let {
                AiBrief(strings(it.optJSONArray("facts")), strings(it.optJSONArray("inference")),
                    it.optString("uncertainty").takeIf { u -> u.isNotBlank() }, time(it.optString("generated_at")),
                    it.optString("model").takeIf { m -> m.isNotBlank() })
            },
        )
    }

    fun parseNews(json: String): List<NewsItem> {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val n = a.getJSONObject(i)
            val pub = time(n.optString("published")) ?: return@mapNotNull null
            NewsItem(
                id = n.optString("id"), title = n.optString("title"), summaryOriginal = n.optString("summary_original"),
                link = n.optString("link"), source = n.optString("source"), tier = n.optString("source_tier"),
                published = pub, categories = strings(n.optJSONArray("categories")), type = n.optString("type"),
                maturity = n.optString("maturity"), countries = strings(n.optJSONArray("countries")),
                bgRelevance = n.optString("bg_relevance"),
                summaryBg = n.optString("summary_bg").takeIf { it.isNotBlank() && it != "null" },
                impactBg = n.optString("impact_bg").takeIf { it.isNotBlank() && it != "null" },
                summaryBy = n.optString("summary_by").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}
