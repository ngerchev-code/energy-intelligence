package com.ngerchev.energy.logic

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * PV + BESS investment model. Deterministic, no AI, no randomness: same inputs -> same numbers.
 *
 * Units: power MW, energy MWh, prices EUR/MWh, money EUR. One time step = 1 hour.
 * Every default below is an editable ASSUMPTION, not a market quote.
 */
data class PvBessInput(
    val pvMwp: Double = 10.0,
    val bessMw: Double = 20.0,
    val bessMwh: Double = 40.0,
    val gridMw: Double = 20.0,              // connection limit, same for export and import
    val socMin: Double = 0.10,
    val socMax: Double = 0.90,
    val etaCharge: Double = 0.94,           // AC -> stored
    val etaDischarge: Double = 0.94,        // stored -> AC
    val cyclesPerDay: Double = 1.0,         // limit on stored energy discharged per day, in usable capacities
    val minSpread: Double = 10.0,           // EUR per discharged MWh a trade must earn after losses
    val curtailNegative: Boolean = true,    // do not export PV at negative prices
    val gridCharging: Boolean = true,       // battery may charge from the grid
    val pvDegradation: Double = 0.005,      // per year
    val bessDegradation: Double = 0.02,     // per year (capacity fade)
    val capexPvPerKwp: Double = 550.0,
    val capexBessPerKwh: Double = 250.0,
    val capexGrid: Double = 1_000_000.0,    // connection, substation, permits
    val opexPvPerKwpYear: Double = 10.0,
    val opexBessPerKwYear: Double = 8.0,
    val lifeYears: Int = 20,
    val discountRate: Double = 0.08,
    val debtShare: Double = 0.60,
    val interestRate: Double = 0.06,
    val loanYears: Int = 10,
    // Scenario multipliers (1.0 = base).
    val priceFactor: Double = 1.0,
    val pvFactor: Double = 1.0,
    val capexFactor: Double = 1.0,
    val opexFactor: Double = 1.0,
)

/** One hour of input data: real price and real PV output per kWp. */
data class HourIn(val dayKey: Long, val price: Double, val pvKwPerKwp: Double)

data class YearSim(
    val hours: Int,
    val pvMwh: Double,
    val pvSoldMwh: Double,          // exported directly
    val pvToBessMwh: Double,        // PV charged into the battery (AC side)
    val curtailedMwh: Double,
    val gridChargeMwh: Double,
    val dischargeMwh: Double,
    val dischargeFromPvMwh: Double,
    val dischargeFromGridMwh: Double,
    val revPvDirect: Double,        // PV sold at the hour it was produced
    val revPvShifted: Double,       // PV stored and sold later
    val revArbitrage: Double,       // grid-charged energy: sales minus purchase cost
    val cycles: Double,             // equivalent full cycles of usable capacity
    val avgPrice: Double,
    val negativeHours: Int,
) {
    val revenue get() = revPvDirect + revPvShifted + revArbitrage
    /** PV value actually captured vs. selling the whole yield at the average price. */
    val pvCaptureRate get() = if (pvMwh > 0 && avgPrice != 0.0) (revPvDirect + revPvShifted) / (pvMwh * avgPrice) else 0.0
}

data class YearRow(val year: Int, val revenue: Double, val opex: Double, val debtService: Double,
                   val cfProject: Double, val cfEquity: Double)

data class Investment(
    val capex: Double,
    val debt: Double,
    val rows: List<YearRow>,
    val npv: Double,
    val irr: Double?,
    val equityIrr: Double?,
    val simplePayback: Double?,
    val discountedPayback: Double?,
)

data class Scenario(val name: String, val sim: YearSim, val inv: Investment)
data class Sensitivity(val parameter: String, val low: Double, val high: Double)   // NPV at −/+ change

object PvBess {

    private const val EPS = 1e-9

    // ------------------------------------------------------------------ dispatch

    fun simulateYear(hours: List<HourIn>, inp: PvBessInput): YearSim {
        var acc = Acc()
        var start = 0
        while (start < hours.size) {
            var end = start
            while (end < hours.size && hours[end].dayKey == hours[start].dayKey) end++
            acc = simulateDay(hours.subList(start, end), inp, acc)
            start = end
        }
        val usable = (inp.socMax - inp.socMin) * inp.bessMwh
        return YearSim(
            hours = hours.size, pvMwh = acc.pv, pvSoldMwh = acc.pvSold, pvToBessMwh = acc.pvToBess,
            curtailedMwh = acc.curtailed, gridChargeMwh = acc.gridCharge, dischargeMwh = acc.dis,
            dischargeFromPvMwh = acc.disPv, dischargeFromGridMwh = acc.disGrid,
            revPvDirect = acc.revPv, revPvShifted = acc.revShift, revArbitrage = acc.revArb,
            cycles = if (usable > 0) acc.dis / inp.etaDischarge / usable else 0.0,
            avgPrice = if (hours.isEmpty()) 0.0 else hours.sumOf { it.price * inp.priceFactor } / hours.size,
            negativeHours = hours.count { it.price < 0 },
        )
    }

    data class Acc(
        val pv: Double = 0.0, val pvSold: Double = 0.0, val pvToBess: Double = 0.0, val curtailed: Double = 0.0,
        val gridCharge: Double = 0.0, val dis: Double = 0.0, val disPv: Double = 0.0, val disGrid: Double = 0.0,
        val revPv: Double = 0.0, val revShift: Double = 0.0, val revArb: Double = 0.0,
    )

    /**
     * One day, battery starts and ends at SoC min (days are independent).
     * Greedy pairing: repeatedly take the most profitable (charge hour i < discharge hour j) pair and move as
     * much energy as all limits allow — battery power, grid limit, SoC window between i and j, daily cycle limit.
     * Charging sources at hour i, cheapest first: curtailed PV (free), then PV that would be exported or grid
     * energy (both cost the hour's price). Each discharged MWh is counted once, at its sale price.
     */
    fun simulateDay(day: List<HourIn>, inp: PvBessInput, acc0: Acc = Acc()): Acc {
        val n = day.size
        val p = DoubleArray(n) { day[it].price * inp.priceFactor }
        val pv = DoubleArray(n) { day[it].pvKwPerKwp * inp.pvMwp * inp.pvFactor }
        val pvDirect = DoubleArray(n) { if (inp.curtailNegative && p[it] < 0) 0.0 else min(pv[it], inp.gridMw) }
        val free = DoubleArray(n) { pv[it] - pvDirect[it] }
        val chFree = DoubleArray(n); val chPv = DoubleArray(n); val chGrid = DoubleArray(n); val dis = DoubleArray(n)
        val lo = inp.socMin * inp.bessMwh
        val hi = inp.socMax * inp.bessMwh
        val soc = DoubleArray(n) { lo }                       // stored energy at the end of each hour
        val rt = inp.etaCharge * inp.etaDischarge
        val dailyStoredOut = inp.cyclesPerDay * (hi - lo)
        var storedOut = 0.0
        var disPv = 0.0; var disGrid = 0.0; var revShift = 0.0; var revArbSales = 0.0
        val blocked = HashSet<Int>()

        if (inp.bessMw > EPS && hi - lo > EPS) while (true) {
            var bi = -1; var bj = -1; var bSrc = -1; var bProfit = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                if (dis[i] > EPS) continue
                val chTot = chFree[i] + chPv[i] + chGrid[i]
                if (inp.bessMw - chTot <= EPS) continue
                // 0 = free PV, 1 = PV at market, 2 = grid
                val src = when {
                    inp.gridCharging && p[i] < 0 && inp.gridMw - chGrid[i] > EPS -> 2
                    free[i] - chFree[i] > EPS -> 0
                    pvDirect[i] - chPv[i] > EPS -> 1
                    inp.gridCharging && inp.gridMw - chGrid[i] > EPS -> 2
                    else -> -1
                }
                if (src < 0) continue
                val cost = if (src == 0) 0.0 else p[i]
                for (j in i + 1 until n) {
                    if (chFree[j] + chPv[j] + chGrid[j] > EPS || blocked.contains(i * 100 + j)) continue
                    val exportUsed = pvDirect[j] - chPv[j] + dis[j]
                    if (min(inp.bessMw - dis[j], inp.gridMw - exportUsed) <= EPS) continue
                    val profit = p[j] - cost / rt
                    if (profit > bProfit) { bProfit = profit; bi = i; bj = j; bSrc = src }
                }
            }
            if (bi < 0 || bProfit < inp.minSpread) break

            var maxSoc = Double.NEGATIVE_INFINITY
            for (t in bi until bj) maxSoc = max(maxSoc, soc[t])
            val srcRemain = when (bSrc) { 0 -> free[bi] - chFree[bi]; 1 -> pvDirect[bi] - chPv[bi]; else -> inp.gridMw - chGrid[bi] }
            val chargeRoom = inp.bessMw - (chFree[bi] + chPv[bi] + chGrid[bi])
            val d = minOf(
                inp.bessMw - dis[bj],
                inp.gridMw - (pvDirect[bj] - chPv[bj] + dis[bj]),
                min(srcRemain, chargeRoom) * rt,
                (hi - maxSoc) * inp.etaDischarge,
                (dailyStoredOut - storedOut) * inp.etaDischarge,
            )
            if (d <= 1e-6) { blocked.add(bi * 100 + bj); continue }
            val chargeAc = d / rt
            when (bSrc) { 0 -> chFree[bi] += chargeAc; 1 -> chPv[bi] += chargeAc; else -> chGrid[bi] += chargeAc }
            dis[bj] += d
            storedOut += d / inp.etaDischarge
            for (t in bi until bj) soc[t] += chargeAc * inp.etaCharge
            if (bSrc == 2) { disGrid += d; revArbSales += d * p[bj] } else { disPv += d; revShift += d * p[bj] }
        }

        var pvSold = 0.0; var revPv = 0.0; var gridCost = 0.0
        for (i in 0 until n) {
            val sold = pvDirect[i] - chPv[i]
            pvSold += sold; revPv += sold * p[i]; gridCost += chGrid[i] * p[i]
        }
        return Acc(
            pv = acc0.pv + pv.sum(), pvSold = acc0.pvSold + pvSold,
            pvToBess = acc0.pvToBess + chFree.sum() + chPv.sum(),
            curtailed = acc0.curtailed + (0 until n).sumOf { free[it] - chFree[it] },
            gridCharge = acc0.gridCharge + chGrid.sum(), dis = acc0.dis + dis.sum(),
            disPv = acc0.disPv + disPv, disGrid = acc0.disGrid + disGrid,
            revPv = acc0.revPv + revPv, revShift = acc0.revShift + revShift,
            revArb = acc0.revArb + revArbSales - gridCost,
        )
    }

    // ------------------------------------------------------------------ finance

    fun annuity(principal: Double, rate: Double, years: Int): Double =
        if (years <= 0 || principal <= 0) 0.0
        else if (rate == 0.0) principal / years
        else principal * rate / (1 - (1 + rate).pow(-years))

    fun npv(rate: Double, flows: List<Double>) = flows.mapIndexed { t, c -> c / (1 + rate).pow(t) }.sum()

    fun irr(flows: List<Double>): Double? {
        if (flows.none { it < 0 } || flows.none { it > 0 }) return null
        var lo = -0.99; var hi = 2.0
        if (npv(lo, flows) * npv(hi, flows) > 0) return null
        repeat(200) { val mid = (lo + hi) / 2; if (npv(mid, flows) * npv(lo, flows) > 0) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    /** Years until cumulative cash flow turns positive, interpolated inside the year; null if never. */
    fun payback(flows: List<Double>, rate: Double = 0.0): Double? {
        var cum = 0.0
        flows.forEachIndexed { t, c ->
            val v = c / (1 + rate).pow(t)
            if (cum < 0 && cum + v >= 0 && t > 0) return t - 1 + (-cum / v)
            cum += v
        }
        return null
    }

    fun capex(inp: PvBessInput) =
        (inp.pvMwp * 1000 * inp.capexPvPerKwp + inp.bessMwh * 1000 * inp.capexBessPerKwh + inp.capexGrid) * inp.capexFactor

    fun opex(inp: PvBessInput) =
        (inp.pvMwp * 1000 * inp.opexPvPerKwpYear + inp.bessMw * 1000 * inp.opexBessPerKwYear) * inp.opexFactor

    /**
     * Yearly cash flows from the year-1 simulation. PV revenue fades with PV degradation; battery revenue
     * (shifted PV and arbitrage) with battery capacity fade. Prices stay at the historical level (no forecast).
     * Taxes are not modelled.
     */
    fun invest(sim: YearSim, inp: PvBessInput): Investment {
        val capex = capex(inp)
        val opex = opex(inp)
        val debt = capex * inp.debtShare
        val ds = annuity(debt, inp.interestRate, inp.loanYears)
        val rows = (1..inp.lifeYears).map { y ->
            val rev = sim.revPvDirect * (1 - inp.pvDegradation).pow(y - 1) +
                (sim.revPvShifted + sim.revArbitrage) * (1 - inp.bessDegradation).pow(y - 1)
            val service = if (y <= inp.loanYears) ds else 0.0
            YearRow(y, rev, opex, service, rev - opex, rev - opex - service)
        }
        val project = listOf(-capex) + rows.map { it.cfProject }
        val equity = listOf(-(capex - debt)) + rows.map { it.cfEquity }
        return Investment(capex, debt, rows, npv(inp.discountRate, project), irr(project), irr(equity),
            payback(project), payback(project, inp.discountRate))
    }

    // ------------------------------------------------------------------ scenarios & sensitivity

    val SCENARIOS = listOf(
        "Песимистичен" to PvBessInput(priceFactor = 0.85, pvFactor = 0.95, capexFactor = 1.10, opexFactor = 1.10),
        "Базов" to PvBessInput(),
        "Оптимистичен" to PvBessInput(priceFactor = 1.15, pvFactor = 1.05, capexFactor = 0.90, opexFactor = 0.90),
    )

    fun scenarios(hours: List<HourIn>, base: PvBessInput): List<Scenario> = SCENARIOS.map { (name, f) ->
        val inp = base.copy(priceFactor = f.priceFactor, pvFactor = f.pvFactor, capexFactor = f.capexFactor, opexFactor = f.opexFactor)
        val sim = simulateYear(hours, inp)
        Scenario(name, sim, invest(sim, inp))
    }

    /** NPV when one input moves by −/+ [delta] (relative), all else at base. */
    fun sensitivity(hours: List<HourIn>, base: PvBessInput, delta: Double = 0.2): List<Sensitivity> {
        val baseSim = simulateYear(hours, base)
        fun npvOf(inp: PvBessInput, resim: Boolean) = invest(if (resim) simulateYear(hours, inp) else baseSim, inp).npv
        return listOf(
            Sensitivity("Цени на електроенергията", npvOf(base.copy(priceFactor = 1 - delta), true), npvOf(base.copy(priceFactor = 1 + delta), true)),
            Sensitivity("PV производство", npvOf(base.copy(pvFactor = 1 - delta), true), npvOf(base.copy(pvFactor = 1 + delta), true)),
            Sensitivity("CAPEX", npvOf(base.copy(capexFactor = 1 + delta), false), npvOf(base.copy(capexFactor = 1 - delta), false)),
            Sensitivity("OPEX", npvOf(base.copy(opexFactor = 1 + delta), false), npvOf(base.copy(opexFactor = 1 - delta), false)),
            Sensitivity("Дисконтов процент", npvOf(base.copy(discountRate = base.discountRate * (1 + delta)), false),
                npvOf(base.copy(discountRate = base.discountRate * (1 - delta)), false)),
            Sensitivity("Ефективност на BESS (±3 пр.п.)",
                npvOf(base.copy(etaCharge = base.etaCharge - 0.03, etaDischarge = base.etaDischarge - 0.03), true),
                npvOf(base.copy(etaCharge = min(1.0, base.etaCharge + 0.03), etaDischarge = min(1.0, base.etaDischarge + 0.03)), true)),
        ).sortedByDescending { kotlin.math.abs(it.high - it.low) }
    }

    // ------------------------------------------------------------------ static baseline

    /**
     * The classic spreadsheet estimate used as the comparison baseline:
     * PV = annual yield x average price; BESS = for each day, one block of usable energy bought at the mean of
     * the cheapest hours and sold at the mean of the most expensive hours (no SoC timing, no grid limit,
     * no PV interaction), times cycles per day.
     */
    fun staticBaseline(hours: List<HourIn>, inp: PvBessInput): YearSim {
        val yieldMwh = hours.sumOf { it.pvKwPerKwp * inp.pvMwp * inp.pvFactor }
        val avg = if (hours.isEmpty()) 0.0 else hours.sumOf { it.price * inp.priceFactor } / hours.size
        val usable = (inp.socMax - inp.socMin) * inp.bessMwh
        val k = if (inp.bessMw > 0) ceil(usable / inp.bessMw).toInt().coerceAtLeast(1) else 1
        var bess = 0.0
        hours.groupBy { it.dayKey }.values.forEach { d ->
            val s = d.map { it.price * inp.priceFactor }.sorted()
            if (s.size >= 2 * k) {
                val low = s.take(k).average(); val high = s.takeLast(k).average()
                bess += max(0.0, usable * inp.etaDischarge * high - usable / inp.etaCharge * low) * inp.cyclesPerDay
            }
        }
        val days = hours.map { it.dayKey }.distinct().size
        return YearSim(hours.size, yieldMwh, yieldMwh, 0.0, 0.0, 0.0, usable * inp.etaDischarge * days * inp.cyclesPerDay,
            0.0, 0.0, yieldMwh * avg, 0.0, bess, days * inp.cyclesPerDay, avg, hours.count { it.price < 0 })
    }
}

/** Joins real hourly prices with a real-year PV profile (same calendar day and UTC hour). */
fun buildHours(year: com.ngerchev.energy.data.PriceYear, site: com.ngerchev.energy.data.PvSite): List<HourIn> =
    year.hours.map { (t, price) ->
        val u = t.atZone(java.time.ZoneOffset.UTC)
        var doy = u.dayOfYear
        if (u.toLocalDate().isLeapYear && doy >= 60) doy -= 1     // 29 Feb uses 28 Feb
        val kw = site.kw.getOrElse((doy - 1) * 24 + u.hour) { 0.0 }
        HourIn(t.atZone(com.ngerchev.energy.data.EnergyParser.SOFIA).toLocalDate().toEpochDay(), price, kw)
    }
