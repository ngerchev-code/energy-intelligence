package com.ngerchev.energy.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reads Energy-Charts responses. Both the v2 format ("series" + "data") and the older v1
 * format ("unix_seconds" + arrays) are understood, so a change on their side does not break the app.
 */
object EnergyParser {

    val SOFIA: ZoneId = ZoneId.of("Europe/Sofia")

    fun license(json: String): String? =
        runCatching { JSONObject(json).optString("license").ifBlank { null } }.getOrNull()

    fun parsePrices(json: String): List<PricePoint> {
        val o = JSONObject(json)
        if (o.has("data") && o.opt("data") is JSONArray) {
            val set = parseV2(o)
            val s = set.series.firstOrNull { it.id.contains("price", true) } ?: set.series.firstOrNull()
                ?: return emptyList()
            return set.times.indices.mapNotNull { i ->
                s.values.getOrNull(i)?.let { PricePoint(set.times[i], it) }
            }
        }
        // v1: {"unix_seconds":[...], "price":[...]}
        val ts = o.optJSONArray("unix_seconds") ?: return emptyList()
        val pr = o.optJSONArray("price") ?: return emptyList()
        return (0 until minOf(ts.length(), pr.length())).mapNotNull { i ->
            val v = num(pr, i) ?: return@mapNotNull null
            PricePoint(fromUnix(ts.getLong(i)), v)
        }
    }

    /** Generation by source. Load, shares and exchange series are left out. */
    fun parseGeneration(json: String): SeriesSet {
        val o = JSONObject(json)
        val set = if (o.opt("data") is JSONArray) parseV2(o) else parseV1(o, "production_types")
        val factor = if (o.optString("unit").contains("GW", true)) 1000.0 else 1.0
        val kept = set.series.filterNot { isNotGeneration(it.id) || isNotGeneration(it.name) }
            .map { s -> s.copy(values = s.values.map { v -> v?.times(factor) }) }
        return set.copy(series = kept)
    }

    /** Cross-border physical/commercial exchange per neighbour (MW). */
    fun parseFlows(json: String): SeriesSet {
        val o = JSONObject(json)
        val v2 = o.opt("data") is JSONArray
        val set = if (v2) parseV2(o) else parseV1(o, "countries")
        // The old format is in GW; the new one states its unit. Positive = import.
        val unit = o.optString("unit")
        val factor = if (unit.contains("GW", true) || (!v2 && unit.isBlank())) 1000.0 else 1.0
        val kept = set.series.filterNot {
            val k = "${it.id} ${it.name}".lowercase()
            k.contains("sum") || k.contains("total") || k.contains("сума")
        }.map { s -> s.copy(values = s.values.map { v -> v?.times(factor) }) }
        return set.copy(series = kept)
    }

    private fun isNotGeneration(key: String): Boolean {
        val k = key.lowercase()
        return listOf("load", "share", "cross", "import", "export", "consumption", "pumping", "price")
            .any { k.contains(it) }
    }

    private fun parseV2(o: JSONObject): SeriesSet {
        val seriesArr = o.optJSONArray("series") ?: JSONArray()
        val ids = (0 until seriesArr.length()).map { i ->
            val s = seriesArr.getJSONObject(i)
            s.optString("id") to s.optString("name", s.optString("id"))
        }
        val data = o.getJSONArray("data")
        val times = ArrayList<ZonedDateTime>(data.length())
        val cols = ids.map { ArrayList<Double?>(data.length()) }
        // Some responses may name series only inside "values": collect those too.
        val extraIds = LinkedHashSet<String>()
        for (i in 0 until data.length()) {
            val v = data.getJSONObject(i).optJSONObject("values") ?: continue
            v.keys().forEach { if (ids.none { p -> p.first == it }) extraIds.add(it) }
        }
        val allIds = ids + extraIds.map { it to it }
        val allCols = cols + extraIds.map { ArrayList<Double?>(data.length()) }
        for (i in 0 until data.length()) {
            val row = data.getJSONObject(i)
            times.add(parseTime(row.get("timestamp")))
            val v = row.optJSONObject("values")
            allIds.forEachIndexed { c, (id, _) ->
                allCols[c].add(if (v == null || v.isNull(id)) null else v.optDouble(id).takeUnless { it.isNaN() })
            }
        }
        return SeriesSet(times, allIds.mapIndexed { c, (id, name) -> Series(id, name, allCols[c]) })
    }

    private fun parseV1(o: JSONObject, listKey: String): SeriesSet {
        val ts = o.optJSONArray("unix_seconds") ?: JSONArray()
        val times = (0 until ts.length()).map { fromUnix(ts.getLong(it)) }
        val arr = o.optJSONArray(listKey) ?: JSONArray()
        val series = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val name = s.optString("name")
            val d = s.optJSONArray("data") ?: JSONArray()
            Series(name, name, (0 until times.size).map { j -> if (j < d.length()) num(d, j) else null })
        }
        return SeriesSet(times, series)
    }

    private fun num(a: JSONArray, i: Int): Double? =
        if (a.isNull(i)) null else a.optDouble(i).takeUnless { it.isNaN() }

    private fun parseTime(t: Any): ZonedDateTime = when (t) {
        is Number -> fromUnix(t.toLong())
        else -> OffsetDateTime.parse(t.toString()).atZoneSameInstant(SOFIA)
    }

    private fun fromUnix(s: Long): ZonedDateTime = Instant.ofEpochSecond(s).atZone(SOFIA)
}
