package com.ngerchev.energy

import com.ngerchev.energy.data.EnergyParser
import com.ngerchev.energy.logic.Bess
import com.ngerchev.energy.logic.BessInput
import com.ngerchev.energy.logic.HourPrice
import com.ngerchev.energy.logic.Prices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private fun day(vararg p: Double) = p.mapIndexed { i, v -> HourPrice(i, v) }

    @Test
    fun batteryBuysLowSellsHigh() {
        // 1 MW / 1 MWh, 100% efficient: buy at 10, sell at 100 -> 90 EUR.
        val inp = BessInput(powerMw = 1.0, capacityMwh = 1.0, roundTripEff = 1.0, depthOfDischarge = 1.0)
        val prices = day(50.0, 10.0, 60.0, 100.0, 40.0)
        assertEquals(90.0, Bess.dayProfit(prices, inp), 1e-6)
    }

    @Test
    fun batteryCannotSellBeforeBuying() {
        val inp = BessInput(powerMw = 1.0, capacityMwh = 1.0, roundTripEff = 1.0, depthOfDischarge = 1.0)
        // Expensive first, cheap later: the only valid trade is 20 -> 30.
        val prices = day(200.0, 20.0, 30.0, 5.0)
        assertEquals(10.0, Bess.dayProfit(prices, inp), 1e-6)
    }

    @Test
    fun lossMakingCycleIsSkipped() {
        val inp = BessInput(powerMw = 1.0, capacityMwh = 1.0, roundTripEff = 0.5, depthOfDischarge = 1.0)
        assertEquals(0.0, Bess.dayProfit(day(100.0, 110.0, 120.0), inp), 1e-6)
    }

    @Test
    fun twoHourBatteryUsesTwoSlots() {
        val inp = BessInput(powerMw = 1.0, capacityMwh = 2.0, roundTripEff = 1.0, depthOfDischarge = 1.0)
        val prices = day(10.0, 20.0, 50.0, 100.0, 90.0)
        // buy 10+20, sell 100+90 -> 160
        assertEquals(160.0, Bess.dayProfit(prices, inp), 1e-6)
    }

    @Test
    fun statsFindExtremes() {
        val s = Prices.stats(day(30.0, -5.0, 80.0, 120.0))!!
        assertEquals(-5.0, s.min.eurMwh, 1e-9)
        assertEquals(120.0, s.max.eurMwh, 1e-9)
        assertEquals(1, s.negativeHours)
    }

    @Test
    fun parsesV2Prices() {
        val json = """{"series":[{"id":"price","name":"Day-ahead"}],"data":[
            {"timestamp":"2026-11-14T00:00:00+02:00","values":{"price":101.5}},
            {"timestamp":"2026-11-14T00:15:00+02:00","values":{"price":null}}],
            "license":"x"}"""
        val p = EnergyParser.parsePrices(json)
        assertEquals(1, p.size)
        assertEquals(101.5, p[0].eurMwh, 1e-9)
        assertEquals(0, p[0].time.hour)
    }

    @Test
    fun parsesV1PricesAndGeneration() {
        val prices = """{"unix_seconds":[1794607200,1794610800],"price":[80.0,90.0]}"""
        assertEquals(2, EnergyParser.parsePrices(prices).size)
        val gen = """{"unix_seconds":[1794607200],"production_types":[
            {"name":"Nuclear","data":[2000]},{"name":"Load","data":[4000]},{"name":"Solar","data":[null]}]}"""
        val set = EnergyParser.parseGeneration(gen)
        assertTrue(set.series.none { it.name == "Load" })
        assertEquals(2, set.series.size)
    }
}
