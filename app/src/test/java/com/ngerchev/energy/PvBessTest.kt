package com.ngerchev.energy

import com.ngerchev.energy.logic.HourIn
import com.ngerchev.energy.logic.PvBess
import com.ngerchev.energy.logic.PvBessInput
import com.ngerchev.energy.logic.YearSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values are worked out by hand in the comments. */
class PvBessTest {

    // 1 MW / 1 MWh battery, full SoC window, lossless, no PV, no spread threshold.
    private val ideal = PvBessInput(pvMwp = 0.0, bessMw = 1.0, bessMwh = 1.0, gridMw = 10.0, socMin = 0.0, socMax = 1.0,
        etaCharge = 1.0, etaDischarge = 1.0, minSpread = 0.0)

    private fun day(prices: List<Double>, pv: List<Double> = prices.map { 0.0 }) =
        prices.indices.map { HourIn(1L, prices[it], pv[it]) }

    @Test fun arbitrageLossless() {
        // buy 1 MWh at 10, sell at 100 -> 90
        val s = PvBess.simulateYear(day(listOf(10.0, 100.0)), ideal)
        assertEquals(90.0, s.revArbitrage, 1e-9)
        assertEquals(1.0, s.cycles, 1e-9)
    }

    @Test fun lossesAndPowerLimit() {
        // eta 0.9/0.9: charging is capped at 1 MWh AC (1 MW x 1 h) -> stored 0.9 -> sold 0.81.
        // 0.81 x 100 - 1 x 10 = 71
        val s = PvBess.simulateYear(day(listOf(10.0, 100.0)), ideal.copy(etaCharge = 0.9, etaDischarge = 0.9))
        assertEquals(0.81, s.dischargeMwh, 1e-9)
        assertEquals(71.0, s.revArbitrage, 1e-9)
    }

    @Test fun socWindowLimitsEnergy() {
        // SoC 10–90 % of 1 MWh -> only 0.8 MWh can be moved: 0.8 x (100 - 10) = 72
        val s = PvBess.simulateYear(day(listOf(10.0, 100.0)), ideal.copy(socMin = 0.1, socMax = 0.9))
        assertEquals(72.0, s.revArbitrage, 1e-9)
    }

    @Test fun noTradeBelowMinimumSpread() {
        val s = PvBess.simulateYear(day(listOf(50.0, 55.0)), ideal.copy(minSpread = 10.0))
        assertEquals(0.0, s.dischargeMwh, 1e-12)
    }

    @Test fun cannotSellBeforeBuying() {
        // expensive first: the only allowed trade is 20 -> 30
        val s = PvBess.simulateYear(day(listOf(200.0, 20.0, 30.0)), ideal)
        assertEquals(10.0, s.revArbitrage, 1e-9)
    }

    @Test fun curtailedPvIsStoredForFreeAndCountedOnce() {
        // PV 2 MW at hour 0, grid limit 1 MW: 1 MWh exported (50), 1 MWh would be curtailed -> stored for free
        // and sold at hour 1 (50). Total revenue 100 = PV energy x price, no double counting.
        val inp = ideal.copy(pvMwp = 1.0, gridMw = 1.0)
        val s = PvBess.simulateYear(day(listOf(50.0, 50.0), listOf(2.0, 0.0)), inp)
        assertEquals(50.0, s.revPvDirect, 1e-9)
        assertEquals(50.0, s.revPvShifted, 1e-9)
        assertEquals(0.0, s.revArbitrage, 1e-9)
        assertEquals(0.0, s.curtailedMwh, 1e-9)
        assertEquals(100.0, s.revenue, 1e-9)
    }

    @Test fun negativePriceCurtailsPvAndPaysForCharging() {
        // price −20 then 30; PV is not exported at a negative price; the battery charges from the grid and is paid
        // 20 for it, then sells at 30 -> arbitrage 50; the 1 MWh of PV is curtailed.
        val inp = ideal.copy(pvMwp = 1.0, gridMw = 5.0)
        val s = PvBess.simulateYear(day(listOf(-20.0, 30.0), listOf(1.0, 0.0)), inp)
        assertEquals(50.0, s.revArbitrage, 1e-9)
        assertEquals(1.0, s.curtailedMwh, 1e-9)
        assertEquals(0.0, s.revPvDirect, 1e-9)
    }

    @Test fun gridLimitCapsDischarge() {
        // PV 0.6 MW at the selling hour already uses the 1 MW connection -> only 0.4 MWh can be discharged.
        val inp = ideal.copy(pvMwp = 1.0, gridMw = 1.0)
        val s = PvBess.simulateYear(day(listOf(10.0, 100.0), listOf(0.0, 0.6)), inp)
        assertEquals(0.4, s.dischargeMwh, 1e-9)
        assertEquals(0.6 * 100 + 0.4 * 100 - 0.4 * 10, s.revenue, 1e-9)
    }

    @Test fun annuityKnownValue() {
        // 1000 at 10 % over 2 years: 1000 x 0.1 / (1 - 1.1^-2) = 576.19
        assertEquals(576.19, PvBess.annuity(1000.0, 0.10, 2), 0.01)
        assertEquals(500.0, PvBess.annuity(1000.0, 0.0, 2), 1e-9)
    }

    @Test fun investmentNpvIrrPayback() {
        // CAPEX 1000, +150 a year for 10 years, 0 % discount: NPV 500; IRR 8.14 %; payback 6 + 100/150 = 6.67 years
        val inp = PvBessInput(pvMwp = 0.0, bessMwh = 0.0, bessMw = 0.0, capexGrid = 1000.0, opexPvPerKwpYear = 0.0,
            opexBessPerKwYear = 0.0, lifeYears = 10, discountRate = 0.0, debtShare = 0.0, pvDegradation = 0.0)
        val sim = YearSim(8760, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 150.0, 0.0, 0.0, 0.0, 0.0, 0)
        val inv = PvBess.invest(sim, inp)
        assertEquals(500.0, inv.npv, 1e-6)
        assertEquals(0.0814, inv.irr!!, 1e-3)
        assertEquals(6.667, inv.simplePayback!!, 1e-3)
    }

    @Test fun debtLowersEquityButNotProjectCashFlow() {
        val inp = PvBessInput(pvMwp = 0.0, bessMwh = 0.0, bessMw = 0.0, capexGrid = 1000.0, opexPvPerKwpYear = 0.0,
            opexBessPerKwYear = 0.0, lifeYears = 10, debtShare = 0.5, interestRate = 0.05, loanYears = 5, pvDegradation = 0.0)
        val sim = YearSim(8760, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 150.0, 0.0, 0.0, 0.0, 0.0, 0)
        val inv = PvBess.invest(sim, inp)
        assertEquals(150.0, inv.rows[0].cfProject, 1e-9)
        assertEquals(150.0 - PvBess.annuity(500.0, 0.05, 5), inv.rows[0].cfEquity, 1e-9)
        assertEquals(150.0, inv.rows[5].cfEquity, 1e-9)       // year 6: loan repaid
        assertTrue(inv.equityIrr!! > inv.irr!!)                // leverage at 5 % < project return
    }

    @Test fun neverPaysBack() {
        assertNull(PvBess.payback(listOf(-1000.0, 10.0, 10.0)))
    }

    @Test fun staticBaselineHandValue() {
        // one day [10, 100], 1 MWh usable, lossless: 1 x 100 - 1 x 10 = 90
        val s = PvBess.staticBaseline(day(listOf(10.0, 100.0)), ideal)
        assertEquals(90.0, s.revArbitrage, 1e-9)
    }

    @Test fun deterministic() {
        val d = (0 until 72).map { HourIn((it / 24).toLong(), 50 + 40 * kotlin.math.sin(it / 3.0), if (it % 24 in 7..17) 0.5 else 0.0) }
        assertEquals(PvBess.simulateYear(d, PvBessInput()), PvBess.simulateYear(d, PvBessInput()))
    }
}
