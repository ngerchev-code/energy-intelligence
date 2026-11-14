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
)
