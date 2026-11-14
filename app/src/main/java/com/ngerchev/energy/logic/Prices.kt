package com.ngerchev.energy.logic

import com.ngerchev.energy.data.PricePoint
import java.time.LocalDate

/** Average price for one clock hour. */
data class HourPrice(val hour: Int, val eurMwh: Double)

data class DayStats(
    val avg: Double,
    val min: HourPrice,
    val max: HourPrice,
    val negativeHours: Int,
    /** Spread between the 2 most expensive and the 2 cheapest hours: what a 2-hour battery could earn. */
    val spread2h: Double,
)

object Prices {

    /** 15-minute (or hourly) prices of one day turned into hourly averages, ordered by hour. */
    fun hourly(points: List<PricePoint>, day: LocalDate): List<HourPrice> =
        points.filter { it.time.toLocalDate() == day }
            .groupBy { it.time.hour }
            .map { (h, ps) -> HourPrice(h, ps.map { it.eurMwh }.average()) }
            .sortedBy { it.hour }

    fun stats(hours: List<HourPrice>): DayStats? {
        if (hours.isEmpty()) return null
        val sorted = hours.sortedBy { it.eurMwh }
        val n = minOf(2, sorted.size / 2).coerceAtLeast(1)
        val spread = sorted.takeLast(n).map { it.eurMwh }.average() - sorted.take(n).map { it.eurMwh }.average()
        return DayStats(
            avg = hours.map { it.eurMwh }.average(),
            min = sorted.first(),
            max = sorted.last(),
            negativeHours = hours.count { it.eurMwh < 0 },
            spread2h = spread,
        )
    }

    /** Daily averages for every day present in the data, oldest first. */
    fun dailyAverages(points: List<PricePoint>): List<Pair<LocalDate, Double>> =
        points.groupBy { it.time.toLocalDate() }
            .map { (d, ps) -> d to ps.map { it.eurMwh }.average() }
            .sortedBy { it.first }

    /** Days for which a full set of hourly prices exists, as hourly lists. */
    fun fullDays(points: List<PricePoint>): List<List<HourPrice>> =
        points.map { it.time.toLocalDate() }.distinct().sorted()
            .map { hourly(points, it) }
            .filter { it.size >= 23 }
}
