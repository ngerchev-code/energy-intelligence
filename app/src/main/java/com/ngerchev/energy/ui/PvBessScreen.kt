package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.Investment
import com.ngerchev.energy.logic.PvBess
import com.ngerchev.energy.logic.PvBessInput
import com.ngerchev.energy.logic.Scenario
import com.ngerchev.energy.logic.Sensitivity
import com.ngerchev.energy.logic.YearSim
import com.ngerchev.energy.logic.buildHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PvBessResult(
    val sim: YearSim, val inv: Investment, val base: YearSim, val baseInv: Investment,
    val scenarios: List<Scenario>, val sens: List<Sensitivity>, val hours: Int, val from: String, val to: String,
    val years: List<Triple<Int, YearSim, Investment>>,
)

/** PV + BESS investment calculator on real prices (ENTSO-E, last 12 months) and a real PV year (PVGIS). */
@Composable
fun PvBessScreen(data: MarketData, input: PvBessInput, onInput: (PvBessInput) -> Unit,
                 siteName: String, onSite: (String) -> Unit, padding: PaddingValues) {
    val year = data.year
    val pv = data.pv
    val site = pv?.sites?.firstOrNull { it.name == siteName } ?: pv?.sites?.firstOrNull()
    var result by remember { mutableStateOf<PvBessResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pvYears = data.pvYears
    LaunchedEffect(input, site, year, pvYears) {
        if (year == null || site == null || year.hours.size < 24 * 30) return@LaunchedEffect
        delay(350)                                   // wait until the slider stops
        busy = true
        result = withContext(Dispatchers.Default) {
            val hours = buildHours(year, site)
            val sim = PvBess.simulateYear(hours, input)
            val base = PvBess.staticBaseline(hours, input)
            val z = com.ngerchev.energy.data.EnergyParser.SOFIA
            PvBessResult(sim, PvBess.invest(sim, input), base, PvBess.invest(base, input),
                PvBess.scenarios(hours, input), PvBess.sensitivity(hours, input), hours.size,
                fmtTime(year.hours.first().first.atZone(z)), fmtTime(year.hours.last().first.atZone(z)),
                pvYears?.sites?.filter { it.name == site.name }?.map { y ->
                    val ys = PvBess.simulateYear(buildHours(year, y), input)
                    Triple(y.year, ys, PvBess.invest(ys, input))
                }.orEmpty())
        }
        busy = false
    }

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(radius = 30.dp) {
                Text("PV + BESS инвестиция", color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                BodyText("Часова симулация върху реалните цени за последните 12 месеца и реалното слънчево производство " +
                    "за избраното място. Всички стойности по-долу са редактируеми допускания.", EColors.Muted, 13)
            }
        }
        if (year == null || pv == null || site == null) {
            item {
                Notice("Данните за модела още не са заредени",
                    "Нужни са годишните почасови цени (ENTSO-E) и слънчевият профил (PVGIS) от сървърната задача. " +
                        "Обновете след няколко минути.")
            }
            return@LazyColumn
        }
        item {
            Tile {
                SectionTitle("Данни, на които стъпва моделът")
                KeyValue("Цени", "${year.source}, ${result?.from ?: "…"} – ${result?.to ?: "…"}")
                KeyValue("Часове с цена", "${year.hours.size}")
                KeyValue("Слънце", "PVGIS ${site.radiationDb ?: ""}, година ${site.year}")
                KeyValue("Добив на място", "${fmt0(site.kwhPerKwp)} kWh/kWp годишно")
                Label("PVGIS допускания: ${pv.assumptions}")
                Fresh(Kind.HISTORY, null, year.retrievedAt, "исторически данни, не прогноза")
                Spacer(Modifier.height(8.dp))
                ChipRow(pv.sites.map { it.name }, site.name, { it?.let(onSite) }, allLabel = null)
            }
        }
        val r = result
        if (r == null) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EColors.Lime) }
        } else {
            item {
                Tile(color = EColors.Lime, radius = 30.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Резултат · базов сценарий", color = EColors.OnLime, fontFamily = Body, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (busy) CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), color = EColors.OnLime, strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Num("NPV " + eur(r.inv.npv), 28.sp, EColors.OnLime)
                    Text("при дисконт ${fmt1(input.discountRate * 100)} % за ${input.lifeYears} год.", color = EColors.OnLime, fontFamily = Body, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("IRR проект", r.inv.irr?.let { fmt1(it * 100) + " %" } ?: "—", "", EColors.Text, EColors.OnLime)
                        Stat("IRR собств. капитал", r.inv.equityIrr?.let { fmt1(it * 100) + " %" } ?: "—", "", EColors.Text, EColors.OnLime)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Откупуване", r.inv.simplePayback?.let { fmt1(it) } ?: "няма", "год.", EColors.Text, EColors.OnLime)
                        Stat("Дисконтирано", r.inv.discountedPayback?.let { fmt1(it) } ?: "няма", "год.", EColors.Text, EColors.OnLime)
                    }
                }
            }
            if (r.years.size > 1) item {
                Tile {
                    SectionTitle("По метеогодини", "${site.name}, PVGIS ${r.years.first().first}–${r.years.last().first}")
                    r.years.forEach { (y, ys, inv) ->
                        KeyValue("$y · ${fmt0(ys.pvMwh)} MWh", "NPV ${eur(inv.npv)} · IRR ${inv.irr?.let { fmt1(it * 100) + "%" } ?: "—"}")
                    }
                    val npvs = r.years.map { it.third.npv }
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Средно NPV", eur(npvs.average()))
                    KeyValue("Диапазон NPV", "${eur(npvs.min())} … ${eur(npvs.max())}")
                    Label("Същите цени (последни 365 дни), различно време. Показва колко зависи резултатът от годината.")
                }
            } else item {
                Label("Диапазон по метеогодини 2019–2023 засега има за Плевен.")
            }
            item {
                val ex = remember(r) {
                    com.ngerchev.energy.logic.PvBessExplain.explain(r.sim, r.inv, r.base, r.baseInv, r.scenarios, r.sens,
                        r.years.map { it.third.npv }, input)
                }
                Tile(color = EColors.Track) {
                    SectionTitle("Анализ на резултата", "детерминистичен, без AI")
                    Badge("ФАКТИ ОТ МОДЕЛА", EColors.Lime)
                    ex.facts.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it", size = 13) }
                    if (ex.inference.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp)); Badge("ТЪЛКУВАНЕ (НЕ Е ФАКТ)", EColors.Sand)
                        ex.inference.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it", EColors.Muted, 13) }
                    }
                    Spacer(Modifier.height(10.dp)); Badge("РИСКОВЕ", EColors.Coral)
                    ex.risks.forEach { Spacer(Modifier.height(6.dp)); BodyText("• $it", size = 13) }
                }
            }
            item {
                Tile {
                    SectionTitle("Година 1 · енергия", "MWh")
                    KeyValue("PV производство", fmt0(r.sim.pvMwh))
                    KeyValue("PV продадено директно", fmt0(r.sim.pvSoldMwh))
                    KeyValue("PV към батерията", fmt0(r.sim.pvToBessMwh))
                    KeyValue("PV ограничено (curtailment)", fmt0(r.sim.curtailedMwh))
                    KeyValue("Зареждане от мрежата", fmt0(r.sim.gridChargeMwh))
                    KeyValue("Разреждане на BESS", fmt0(r.sim.dischargeMwh))
                    KeyValue("Еквивалентни цикли", fmt0(r.sim.cycles))
                    KeyValue("Часове с отрицателна цена", "${r.sim.negativeHours}")
                }
            }
            item {
                Tile {
                    SectionTitle("Година 1 · приходи", "€, без двойно отчитане")
                    KeyValue("PV, продадено в часа на производство", eur(r.sim.revPvDirect))
                    KeyValue("PV, изместено през батерията", eur(r.sim.revPvShifted))
                    KeyValue("Арбитраж от мрежата (нето)", eur(r.sim.revArbitrage))
                    KeyValue("Общо", eur(r.sim.revenue))
                    KeyValue("Оперативни разходи", "− " + eur(PvBess.opex(input)))
                    KeyValue("CAPEX", eur(r.inv.capex))
                    KeyValue("Дълг / собствен капитал", "${eur(r.inv.debt)} / ${eur(r.inv.capex - r.inv.debt)}")
                    KeyValue("Уловена цена на PV", "${fmt0(r.sim.pvCaptureRate * 100)} % от средната")
                    Label("Балансиращи услуги не са включени (няма разрешени публични данни за цените им).")
                }
            }
            item {
                Tile {
                    SectionTitle("Сценарии")
                    r.scenarios.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(s.name, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("NPV ${eur(s.inv.npv)} · IRR ${s.inv.irr?.let { fmt1(it * 100) + "%" } ?: "—"}",
                                color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Label("Песимистичен: цени −15 %, PV −5 %, CAPEX и OPEX +10 %. Оптимистичен: обратно. Цените са исторически, умножени по коефициент – не прогноза.")
                }
            }
            item {
                Tile {
                    SectionTitle("Чувствителност на NPV", "±20 %")
                    val span = r.sens.maxOfOrNull { maxOf(abs(it.low - r.inv.npv), abs(it.high - r.inv.npv)) }?.takeIf { it > 0 } ?: 1.0
                    r.sens.forEach { s -> Tornado(s, r.inv.npv, span) }
                }
            }
            item {
                Tile(color = EColors.Track) {
                    SectionTitle("Сравнение със статичен модел", "същите данни и допускания")
                    KeyValue("Приход година 1 – статичен", eur(r.base.revenue))
                    KeyValue("Приход година 1 – симулация", eur(r.sim.revenue))
                    KeyValue("NPV – статичен", eur(r.baseInv.npv))
                    KeyValue("NPV – симулация", eur(r.inv.npv))
                    val diff = if (r.base.revenue != 0.0) (r.sim.revenue / r.base.revenue - 1) * 100 else 0.0
                    KeyValue("Разлика в прихода", "${if (diff >= 0) "+" else "−"}${fmt1(abs(diff))} %")
                    BodyText("Статичният модел умножава годишния добив по средната цена и брои по един идеален цикъл на " +
                        "ден, без SoC, лимит на мрежата и взаимодействие PV–батерия. Разликата показва колко влияят " +
                        "тези ограничения; дали симулацията е по-точна, ще покаже бектестът (M3).", EColors.Muted, 12)
                }
            }
        }
        item {
            Tile {
                SectionTitle("Обект")
                Param("PV мощност", input.pvMwp, 0f..100f, "MWp", 0.5) { onInput(input.copy(pvMwp = it)) }
                Param("BESS мощност", input.bessMw, 0f..100f, "MW", 0.5) { onInput(input.copy(bessMw = it)) }
                Param("BESS капацитет", input.bessMwh, 0f..400f, "MWh", 1.0) { onInput(input.copy(bessMwh = it)) }
                Param("Присъединяване към мрежата", input.gridMw, 1f..150f, "MW", 0.5) { onInput(input.copy(gridMw = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (input.gridCharging) "✓ Зареждане от мрежата" else "Без зареждане от мрежата", input.gridCharging) {
                        onInput(input.copy(gridCharging = !input.gridCharging))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Chip(if (input.curtailNegative) "✓ Спиране на PV при цена < 0" else "PV продава и при цена < 0", input.curtailNegative) {
                    onInput(input.copy(curtailNegative = !input.curtailNegative))
                }
            }
        }
        item {
            Tile {
                SectionTitle("Батерия")
                Param("Мин. заряд (SoC)", input.socMin * 100, 0f..40f, "%", 1.0) { onInput(input.copy(socMin = it / 100)) }
                Param("Макс. заряд (SoC)", input.socMax * 100, 60f..100f, "%", 1.0) { onInput(input.copy(socMax = it / 100)) }
                Param("Ефективност заряд", input.etaCharge * 100, 80f..100f, "%", 0.5) { onInput(input.copy(etaCharge = it / 100)) }
                Param("Ефективност разряд", input.etaDischarge * 100, 80f..100f, "%", 0.5) { onInput(input.copy(etaDischarge = it / 100)) }
                Param("Цикли на ден (макс.)", input.cyclesPerDay, 0.5f..3f, "", 0.5) { onInput(input.copy(cyclesPerDay = it)) }
                Param("Мин. печалба за цикъл", input.minSpread, 0f..50f, "€/MWh", 1.0) { onInput(input.copy(minSpread = it)) }
                Param("Деградация BESS", input.bessDegradation * 100, 0f..5f, "%/год.", 0.5) { onInput(input.copy(bessDegradation = it / 100)) }
                Param("Деградация PV", input.pvDegradation * 100, 0f..2f, "%/год.", 0.1) { onInput(input.copy(pvDegradation = it / 100)) }
            }
        }
        item {
            Tile {
                SectionTitle("Финанси")
                Param("CAPEX PV", input.capexPvPerKwp, 200f..1200f, "€/kWp", 10.0) { onInput(input.copy(capexPvPerKwp = it)) }
                Param("CAPEX BESS", input.capexBessPerKwh, 80f..600f, "€/kWh", 5.0) { onInput(input.copy(capexBessPerKwh = it)) }
                Param("Присъединяване и др.", input.capexGrid / 1e6, 0f..20f, "млн. €", 0.1) { onInput(input.copy(capexGrid = it * 1e6)) }
                Param("OPEX PV", input.opexPvPerKwpYear, 0f..40f, "€/kWp/год.", 1.0) { onInput(input.copy(opexPvPerKwpYear = it)) }
                Param("OPEX BESS", input.opexBessPerKwYear, 0f..40f, "€/kW/год.", 1.0) { onInput(input.copy(opexBessPerKwYear = it)) }
                Param("Срок", input.lifeYears.toDouble(), 5f..35f, "год.", 1.0) { onInput(input.copy(lifeYears = it.roundToInt())) }
                Param("Дисконтов процент", input.discountRate * 100, 0f..20f, "%", 0.5) { onInput(input.copy(discountRate = it / 100)) }
                Param("Дял на дълга", input.debtShare * 100, 0f..90f, "%", 5.0) { onInput(input.copy(debtShare = it / 100)) }
                Param("Лихва", input.interestRate * 100, 0f..15f, "%", 0.25) { onInput(input.copy(interestRate = it / 100)) }
                Param("Срок на кредита", input.loanYears.toDouble(), 1f..20f, "год.", 1.0) { onInput(input.copy(loanYears = it.roundToInt())) }
                Chip("Върни стойностите по подразбиране", false) { onInput(PvBessInput()) }
            }
        }
        item {
            Notice("Как работи моделът",
                "Всеки ден батерията започва и завършва на минималния заряд. Алгоритъмът сдвоява най-изгодните часове " +
                    "за зареждане и разреждане, като спазва мощността, капацитета, SoC, загубите, лимита на мрежата и " +
                    "броя цикли. Безплатно се зарежда само PV, което иначе би било ограничено. Всеки MWh се отчита веднъж – " +
                    "при продажбата. Приходите в следващите години намаляват с деградацията; цените остават на нивото на " +
                    "последните 12 месеца. Данъците не са моделирани. Методиката: docs/SCIENTIFIC_METHODOLOGY.md.")
        }
    }
}

@Composable
private fun Tornado(s: Sensitivity, base: Double, span: Double) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(s.parameter, color = EColors.Text, fontFamily = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            val lo = ((base - minOf(s.low, s.high)) / span).toFloat().coerceIn(0f, 1f)
            val hi = ((maxOf(s.low, s.high) - base) / span).toFloat().coerceIn(0f, 1f)
            Box(Modifier.weight(1f).height(14.dp)) {
                Box(Modifier.fillMaxWidth(lo).height(14.dp).align(Alignment.CenterEnd).clip(RoundedCornerShape(4.dp)).background(EColors.Coral))
            }
            Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(1f).height(14.dp)) {
                Box(Modifier.fillMaxWidth(hi).height(14.dp).clip(RoundedCornerShape(4.dp)).background(EColors.Lime))
            }
        }
        Label("${eur(minOf(s.low, s.high))} … ${eur(maxOf(s.low, s.high))}")
    }
}
