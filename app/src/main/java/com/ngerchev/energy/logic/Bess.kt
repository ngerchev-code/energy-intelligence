package com.ngerchev.energy.logic

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Inputs for a battery energy storage system (BESS) doing day-ahead arbitrage. */
data class BessInput(
    val powerMw: Double = 1.0,
    val capacityMwh: Double = 2.0,
    val roundTripEff: Double = 0.88,
    val depthOfDischarge: Double = 0.90,
    val cyclesPerDay: Int = 1,
    val capexEurPerKwh: Double = 250.0,
    val opexEurPerMwYear: Double = 10_000.0,
    val availability: Double = 0.97,
)

data class BessResult(
    val daysAnalysed: Int,
    val avgDailyProfit: Double,
    val bestDay: Double,
    val worstDay: Double,
    val annualRevenue: Double,
    val annualOpex: Double,
    val annualNet: Double,
    val capex: Double,
    val paybackYears: Double?,
    val profitableDaysShare: Double,
)

object Bess {

    /**
     * Profit of one day, in EUR. Each cycle buys the usable energy in the cheapest hours and sells it in
     * the most expensive later hours of its window. With 2 cycles the day is split in two halves.
     * Energy sold = energy bought x round-trip efficiency. A cycle that would lose money is skipped.
     */
    fun dayProfit(hours: List<HourPrice>, inp: BessInput): Double {
        if (hours.isEmpty() || inp.powerMw <= 0 || inp.capacityMwh <= 0) return 0.0
        val prices = hours.sortedBy { it.hour }.map { it.eurMwh }
        val cycles = inp.cyclesPerDay.coerceIn(1, 2)
        val windows = if (cycles == 1) listOf(prices) else {
            val half = prices.size / 2
            listOf(prices.subList(0, half), prices.subList(half, prices.size))
        }
        return windows.sumOf { bestCycle(it, inp) }
    }

    /** Best single charge -> discharge cycle within the window (charge strictly before discharge). */
    fun bestCycle(window: List<Double>, inp: BessInput): Double {
        val energy = inp.capacityMwh * inp.depthOfDischarge
        val hoursNeeded = energy / inp.powerMw
        val h = ceil(hoursNeeded - 1e-9).toInt().coerceAtLeast(1)
        if (window.size < 2 * h) return 0.0
        // Energy per hour slot: full power, the last slot only the remainder.
        val slots = List(h) { i -> min(inp.powerMw, energy - inp.powerMw * i) }
        var best = 0.0
        for (split in h..window.size - h) {
            val buy = window.subList(0, split).sorted().take(h)
            val sell = window.subList(split, window.size).sortedDescending().take(h)
            // Cheapest price on the biggest slot, highest price on the biggest slot.
            val cost = buy.zip(slots).sumOf { (p, e) -> p * e }
            val income = sell.zip(slots).sumOf { (p, e) -> p * e * inp.roundTripEff }
            best = max(best, income - cost)
        }
        return best
    }

    fun evaluate(days: List<List<HourPrice>>, inp: BessInput): BessResult? {
        if (days.isEmpty()) return null
        val profits = days.map { dayProfit(it, inp) }
        val avg = profits.average()
        val annualRevenue = avg * 365 * inp.availability
        val annualOpex = inp.opexEurPerMwYear * inp.powerMw
        val net = annualRevenue - annualOpex
        val capex = inp.capexEurPerKwh * inp.capacityMwh * 1000
        return BessResult(
            daysAnalysed = days.size,
            avgDailyProfit = avg,
            bestDay = profits.max(),
            worstDay = profits.min(),
            annualRevenue = annualRevenue,
            annualOpex = annualOpex,
            annualNet = net,
            capex = capex,
            paybackYears = if (net > 0) capex / net else null,
            profitableDaysShare = profits.count { it > 0 }.toDouble() / profits.size,
        )
    }
}
