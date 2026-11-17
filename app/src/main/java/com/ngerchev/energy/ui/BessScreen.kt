package com.ngerchev.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.Bess
import com.ngerchev.energy.logic.BessInput
import com.ngerchev.energy.logic.Prices
import com.ngerchev.energy.logic.TECH_PRESETS
import kotlin.math.roundToInt

/** Battery storage (BESS) arbitrage calculator on the real prices of the last 30 days. */
@Composable
fun BessScreen(data: MarketData, input: BessInput, onChange: (BessInput) -> Unit, padding: PaddingValues) {
    val days = remember(data.prices30d) { Prices.fullDays(data.prices30d) }
    val res = remember(days, input) { Bess.evaluate(days, input) }
    val fin = remember(res, input) { res?.let { Bess.finance(it, input) } }

    LazyColumn(
        contentPadding = screenPadding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Tile(color = EColors.Lime, radius = 30.dp) {
                Text("Батерия ${fmt1(input.powerMw)} MW / ${fmt1(input.capacityMwh)} MWh",
                    color = EColors.OnLime, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Body)
                Spacer(Modifier.height(6.dp))
                if (res == null) {
                    Text("Няма достатъчно ценови данни.", color = EColors.OnLime, fontFamily = Body)
                } else {
                    Num(eur(res.annualNet), 34.sp, EColors.OnLime)
                    Text("нето годишно · по реалните цени за ${days.size} дни",
                        color = EColors.OnLime, fontSize = 12.sp, fontFamily = Body)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Откупуване", res.paybackYears?.let { fmt1(it) } ?: "—", "год.", EColors.Text, EColors.OnLime)
                        Stat("Среден ден", eur(res.avgDailyProfit), "", EColors.Text, EColors.OnLime)
                    }
                }
            }
        }
        if (res != null) item {
            Tile {
                Line("Приход от арбитраж / год.", eur(res.annualRevenue))
                Line("Поддръжка / год.", "− " + eur(res.annualOpex))
                Line("Инвестиция", eur(res.capex))
                Line("Най-добър ден", eur(res.bestDay))
                Line("Най-слаб ден", eur(res.worstDay))
                Line("Печеливши дни", "${(res.profitableDaysShare * 100).roundToInt()} %")
                Line("Цикли годишно", fmt0(input.cyclesPerDay * 365 * input.availability))
                if (fin != null) {
                    Spacer(Modifier.height(8.dp))
                    Line("Срок на проекта", "${fin.years} год. (живот или цикли)")
                    Line("NPV при ${fmt0(input.discountRate * 100)} %", eur(fin.npv))
                    Line("IRR", fin.irr?.let { fmt1(it * 100) + " %" } ?: "—")
                }
                Fresh(Kind.SCENARIO, data.feed?.pricesMeta, data.fetchedAt, "симулация по исторически цени")
            }
        }
        item {
            Tile {
                SectionTitle("Параметри", "Промените се смятат веднага")
                Label("Технология (типични допускания – проверете с производителя)")
                Spacer(Modifier.height(6.dp))
                ChipRow(TECH_PRESETS.map { it.name }, input.tech, { name ->
                    TECH_PRESETS.firstOrNull { it.name == name }?.let { t ->
                        onChange(input.copy(tech = t.name, roundTripEff = t.eff, capexEurPerKwh = t.capex,
                            degradationPerYear = t.degradation, cycleLife = t.cycles))
                    }
                }, allLabel = null)
                TECH_PRESETS.firstOrNull { it.name == input.tech }?.let { Label(it.note) }
                Spacer(Modifier.height(8.dp))
                Param("Мощност", input.powerMw, 0.5f..50f, "MW", 0.5) { onChange(input.copy(powerMw = it)) }
                Param("Капацитет", input.capacityMwh, 0.5f..200f, "MWh", 0.5) { onChange(input.copy(capacityMwh = it)) }
                Param("Ефективност (кръгова)", input.roundTripEff * 100, 70f..95f, "%", 1.0) {
                    onChange(input.copy(roundTripEff = it / 100))
                }
                Param("Дълбочина на разреждане", input.depthOfDischarge * 100, 70f..100f, "%", 1.0) {
                    onChange(input.copy(depthOfDischarge = it / 100))
                }
                Param("Цикли на ден", input.cyclesPerDay.toDouble(), 1f..2f, "", 1.0) {
                    onChange(input.copy(cyclesPerDay = it.roundToInt()))
                }
                Param("Цена на системата", input.capexEurPerKwh, 100f..600f, "€/kWh", 5.0) {
                    onChange(input.copy(capexEurPerKwh = it))
                }
                Param("Поддръжка", input.opexEurPerMwYear / 1000, 0f..40f, "хил. €/MW/год.", 0.5) {
                    onChange(input.copy(opexEurPerMwYear = it * 1000))
                }
                Param("Деградация на капацитета", input.degradationPerYear * 100, 0f..5f, "%/год.", 0.5) {
                    onChange(input.copy(degradationPerYear = it / 100))
                }
                Param("Живот на цикли", input.cycleLife.toDouble(), 2000f..20000f, "цикъла", 500.0) {
                    onChange(input.copy(cycleLife = it.roundToInt()))
                }
                Param("Срок на проекта", input.lifeYears.toDouble(), 5f..25f, "год.", 1.0) {
                    onChange(input.copy(lifeYears = it.roundToInt()))
                }
                Param("Дисконтов процент", input.discountRate * 100, 2f..15f, "%", 0.5) {
                    onChange(input.copy(discountRate = it / 100))
                }
                Text("Продължителност: ${fmt1(input.capacityMwh / input.powerMw)} ч. при пълна мощност",
                    color = EColors.Muted, fontSize = 12.sp, fontFamily = Body)
            }
        }
        item {
            Tile {
                SectionTitle("Как се смята")
                Text(
                    "Всеки ден батерията купува енергия в най-евтините часове и я продава в по-скъпите часове " +
                        "след това (при 2 цикъла денят се дели на две половини). Продадената енергия е купената по " +
                        "кръговата ефективност. Губещи цикли се пропускат. Годишната сума е средният ден × 365 × 97 % " +
                        "наличност минус поддръжката. Деградацията и срокът влизат в NPV/IRR. Не са включени мрежови такси, балансиране, " +
                        "приходи от балансиращи услуги (aFRR/mFRR) и финансиране — реалната доходност може да е " +
                        "по-висока от услуги към ЕСО или по-ниска заради такси. Това е ориентировъчен модел, не " +
                        "инвестиционен съвет.",
                    color = EColors.Muted, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Body,
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = EColors.Muted, fontSize = 14.sp, fontFamily = Body, modifier = Modifier.weight(1f))
        Text(value, color = EColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Body)
    }
}

@Composable
private fun Param(
    label: String, value: Double, range: ClosedFloatingPointRange<Float>, unit: String, step: Double,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = EColors.Text, fontSize = 14.sp, fontFamily = Body, modifier = Modifier.weight(1f))
            Text(
                (if (step >= 1.0) fmt0(value) else fmt1(value)) + if (unit.isNotEmpty()) " $unit" else "",
                color = EColors.Lime, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Body,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { v -> onChange(((v / step).roundToInt() * step)) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = EColors.Teal, activeTrackColor = EColors.Teal, inactiveTrackColor = EColors.Track,
            ),
        )
    }
}
