package com.ngerchev.energy.data

import java.time.ZonedDateTime

/** One day-ahead price interval (15 min or 1 h), in EUR/MWh. */
data class PricePoint(val time: ZonedDateTime, val eurMwh: Double)

/** A named time series in MW, e.g. "Solar" or the exchange with Romania. */
data class Series(val id: String, val name: String, val values: List<Double?>)

/** Several MW series that share the same timestamps. */
data class SeriesSet(val times: List<ZonedDateTime>, val series: List<Series>) {
    /** Index of the last timestamp at which at least one series has a value. */
    fun lastFilledIndex(): Int {
        for (i in times.indices.reversed()) {
            if (series.any { it.values.getOrNull(i) != null }) return i
        }
        return -1
    }
}

data class MarketData(
    val prices30d: List<PricePoint>,
    val tomorrow: List<PricePoint>,
    val generation: SeriesSet?,
    val flows: SeriesSet?,
    val license: String?,
    val fetchedAt: ZonedDateTime,
    val fromCache: Boolean = false,
    /** The server feed (ENTSO-E etc.), when it could be loaded. */
    val feed: Feed? = null,
    val news: List<NewsItem> = emptyList(),
    /** Last 12 months of hourly day-ahead prices (ENTSO-E), for the investment model. */
    val year: PriceYear? = null,
    /** Real-year PV output per kWp for several sites (PVGIS). */
    val pv: PvProfiles? = null,
    /** Same site, several real meteorological years (PVGIS), for the year-to-year range. */
    val pvYears: PvProfiles? = null,
    /** „Самоучител“: weekly lessons built on the server every Sunday from the real data. */
    val lessons: List<Lesson> = emptyList(),
)

data class LessonNews(val title: String, val link: String, val source: String)

data class Lesson(
    val id: String, val number: Int, val title: String, val published: java.time.ZonedDateTime?,
    val facts: List<String>, val why: List<String>,
    val conceptTitle: String, val conceptText: String, val conceptWhere: String,
    val question: String, val answer: String,
    val news: List<LessonNews>, val sources: List<String>, val note: String,
)

data class PriceYear(val source: String, val retrievedAt: java.time.ZonedDateTime?, val hours: List<Pair<java.time.Instant, Double>>)

data class PvSite(val name: String, val lat: Double, val lon: Double, val year: Int, val kwhPerKwp: Double,
                  val radiationDb: String?, val kw: DoubleArray)

data class PvProfiles(val source: String, val assumptions: String, val retrievedAt: java.time.ZonedDateTime?, val sites: List<PvSite>)
