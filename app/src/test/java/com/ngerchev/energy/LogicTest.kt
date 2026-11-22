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

    @Test
    fun financeNpvAndIrr() {
        val r = com.ngerchev.energy.logic.BessResult(30, 0.0, 0.0, 0.0, annualRevenue = 150.0, annualOpex = 0.0,
            annualNet = 150.0, capex = 1000.0, paybackYears = null, profitableDaysShare = 1.0)
        val inp = BessInput(degradationPerYear = 0.0, lifeYears = 10, cycleLife = 100000, discountRate = 0.0)
        val f = Bess.finance(r, inp)
        assertEquals(10, f.years)
        assertEquals(500.0, f.npv, 1e-6)          // 10 x 150 - 1000 at 0 %
        assertEquals(0.0814, f.irr!!, 1e-3)       // known IRR of this annuity
    }

    @Test
    fun cycleLifeShortensProject() {
        val r = com.ngerchev.energy.logic.BessResult(30, 0.0, 0.0, 0.0, 100.0, 0.0, 100.0, 500.0, null, 1.0)
        val f = Bess.finance(r, BessInput(cyclesPerDay = 2, availability = 1.0, cycleLife = 3650, lifeYears = 15))
        assertEquals(5, f.years)
    }

    @Test
    fun knowledgeDailyIsStable() {
        val k = com.ngerchev.energy.logic.Knowledge(listOf("A"), listOf(
            com.ngerchev.energy.logic.Article("1", "t", "s", "x", "A", "T", "D", listOf("tag"), emptyList(), "v", "p", "r", 1),
            com.ngerchev.energy.logic.Article("2", "t2", "s", "x", "A", "T", "D", listOf("tag"), emptyList(), "v", "p", "r", 1)))
        val d = java.time.LocalDate.of(2026, 11, 17)
        assertEquals(k.daily(d), k.daily(d))
        assertEquals(1, k.search("t2", null).size)
        assertEquals(1, k.related(k.articles[0]).size)
    }

    @Test
    fun parsesFeedJson() {
        val json = """{"schema":1,"generated_at":"2026-11-17T15:00Z","sources":[{"id":"entsoe","name":"ENTSO-E","url":"u","status":"not_configured"}],
          "bg":{"prices":{"source":"Energy-Charts","kind":"day-ahead","unit":"EUR/MWh","resolution":"PT15M","retrieved_at":"2026-11-17T15:00Z",
            "points":[["2026-11-17T10:00Z",100.5],["2026-11-17T10:15Z",null]]},
          "flows":{"source":"x","kind":"commercial","neighbours":{"RO":[["2026-11-17T10:00Z",-300]],"GR":[["2026-11-17T10:00Z",120]]}}},
          "europe":{"zones":{"BG":{"name":"България","source":"x","resolution":"PT15M","today":{"avg":100,"min":50,"max":150},"tomorrow":null,"daily":[["2026-11-16",90.0]]}}}}"""
        val f = com.ngerchev.energy.data.FeedParser.parse(json)
        assertEquals(1, f.prices.size)
        assertEquals(12, f.prices[0].time.hour)            // 10:00 UTC = 12:00 Sofia (winter)
        assertEquals(false, f.entsoeConfigured)
        assertEquals(-180.0, f.flows!!.series.sumOf { it.values[0] ?: 0.0 }, 1e-9)
        assertEquals(100.0, f.europe[0].today!!.avg, 1e-9)
    }

    private fun quarters(startUtc: String, hours: Int) = (0 until hours * 4).map {
        com.ngerchev.energy.data.PricePoint(
            java.time.Instant.parse(startUtc).plusSeconds(900L * it).atZone(EnergyParser.SOFIA), 50.0)
    }

    @Test
    fun dstAutumnDayHas25HoursButHourlyViewMergesTheRepeatedHour() {
        val day = java.time.LocalDate.of(2026, 10, 25)
        val pts = quarters("2026-10-24T21:00:00Z", 25)
        assertEquals(100, pts.count { it.time.toLocalDate() == day })   // all 25 hours belong to the 25th
        // Known limitation: the hourly view groups by clock hour, so 03:00 appears once (averaged).
        assertEquals(24, Prices.hourly(pts, day).size)
    }

    @Test
    fun dstSpringDayHas23Hours() {
        val day = java.time.LocalDate.of(2026, 3, 29)
        val h = Prices.hourly(quarters("2026-03-28T22:00:00Z", 23), day)
        assertEquals(23, h.size)
        assertTrue(h.none { it.hour == 3 })                             // 03:00 does not exist that night
        assertEquals(1, Prices.fullDays(quarters("2026-03-28T22:00:00Z", 23)).size)
    }

    @Test fun lessonsParse() {
        val json = javaClass.classLoader!!.getResource("lessons_sample.json")!!.readText()
        val l = com.ngerchev.energy.data.FeedParser.parseLessons(json)
        assertEquals(1, l.size)
        assertEquals("2026-W46", l[0].id)
        assertTrue(l[0].facts.isNotEmpty() && l[0].question.isNotBlank() && l[0].conceptTitle.isNotBlank())
    }

    @Test fun updaterParsesRelease() {
        assertEquals(12, com.ngerchev.energy.logic.Updater.codeOf("v1.0.12"))
        val json = """{"tag_name":"v1.0.12","body":"Обновяване","published_at":"2026-11-22T07:00:00Z",
            "assets":[{"name":"EnergyIntelligence.apk","size":123,"browser_download_url":"https://github.com/x/EnergyIntelligence.apk"}]}"""
        val u = com.ngerchev.energy.logic.Updater.parse(json)!!
        assertEquals(12, u.code); assertEquals("1.0.12", u.name); assertEquals(123L, u.size)
        assertEquals(null, com.ngerchev.energy.logic.Updater.parse("""{"tag_name":"v1.0.13","assets":[]}"""))
    }
}
