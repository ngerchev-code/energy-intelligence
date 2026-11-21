package com.ngerchev.energy.logic

import java.util.Locale
import kotlin.math.abs

data class Explanation(val facts: List<String>, val inference: List<String>, val risks: List<String>)

/**
 * Deterministic analyst for the PV+BESS result: reads the model's numbers, never changes them.
 * Facts restate computed values; interpretations are worded as possibilities; risks are rule-based flags.
 */
object PvBessExplain {
    private fun m(v: Double) = String.format(Locale("bg"), "%.2f млн. €", v / 1e6)
    private fun p(v: Double) = String.format(Locale("bg"), "%.0f %%", v * 100)

    fun explain(sim: YearSim, inv: Investment, base: YearSim, baseInv: Investment, sc: List<Scenario>,
                sens: List<Sensitivity>, years: List<Double>, inp: PvBessInput): Explanation {
        val facts = mutableListOf<String>(); val inf = mutableListOf<String>(); val risks = mutableListOf<String>()
        val total = sim.revenue
        if (total > 0) {
            val streams = listOf("PV, продадено веднага" to sim.revPvDirect, "PV, изместено през батерията" to sim.revPvShifted,
                "арбитраж от мрежата" to sim.revArbitrage).sortedByDescending { it.second }
            facts += "Най-голям дял в прихода за година 1 има „${streams[0].first}“: ${m(streams[0].second)} (${p(streams[0].second / total)} от ${m(total)})."
        }
        if (sim.pvMwh > 0) {
            facts += "PV улавя ${p(sim.pvCaptureRate)} от средната цена за периода."
            if (sim.pvCaptureRate < 0.95) inf += "Вероятно причината е, че слънчевата енергия се произвежда в часове с по-ниски цени (обедно поевтиняване); батерията компенсира част от това, като измества енергия към вечерта."
        }
        if (sim.curtailedMwh > 0.5) {
            facts += String.format(Locale("bg"), "Ограничени са %.0f MWh PV (%.1f %% от производството).", sim.curtailedMwh, 100 * sim.curtailedMwh / sim.pvMwh.coerceAtLeast(1e-9))
            inf += if (sim.negativeHours > 0) "Възможно е ограничаването да идва главно от часовете с отрицателна цена (${sim.negativeHours} ч.) или от лимита на присъединяване ${inp.gridMw} MW."
            else "Възможно е ограничаването да идва от лимита на присъединяване ${inp.gridMw} MW."
        }
        if (base.revenue > 0) {
            val d = sim.revenue / base.revenue - 1
            facts += "Статичният модел дава ${m(base.revenue)} приход – с ${p(abs(d))} ${if (d < 0) "повече" else "по-малко"} от симулацията; NPV ${m(baseInv.npv)} срещу ${m(inv.npv)}."
        }
        sens.firstOrNull()?.let { s ->
            facts += "Най-силно влияе „${s.parameter}“: при ±20 % NPV е между ${m(minOf(s.low, s.high))} и ${m(maxOf(s.low, s.high))}."
        }
        if (years.size > 1) facts += "По метеогодини NPV е между ${m(years.min())} и ${m(years.max())}."

        sc.firstOrNull { it.name == "Песимистичен" }?.let { if (it.inv.npv < 0) risks += "В песимистичния сценарий NPV е отрицателно (${m(it.inv.npv)})." }
        if (inv.npv < 0) risks += "В базовия сценарий NPV е отрицателно – при тези допускания проектът не възвръща дисконтовия процент ${p(inp.discountRate)}."
        inv.rows.firstOrNull()?.let { r ->
            if (r.debtService > 0) {
                val dscr = r.cfProject / r.debtService
                facts += String.format(Locale("bg"), "Покритие на дълга (DSCR) в година 1: %.2f.", dscr)
                if (dscr < 1.2) risks += String.format(Locale("bg"), "DSCR %.2f е под 1,20 – обичаен минимум на банките.", dscr)
            }
        }
        if (total > 0 && sim.revArbitrage / total > 0.4) risks += "Над 40 % от прихода е от арбитраж – зависи силно от бъдещите ценови разлики, които може да намалеят с повече батерии в системата."
        if (sim.cycles > 365 * 1.1) risks += String.format(Locale("bg"), "%.0f цикъла годишно – проверете гаранцията на батерията.", sim.cycles)
        risks += "Цените са исторически (последни 365 дни), без прогноза; CAPEX и OPEX са допускания, докато не се заменят с реални оферти."
        return Explanation(facts, inf, risks)
    }
}
