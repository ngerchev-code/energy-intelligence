package com.ngerchev.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.MarketData

@Composable
fun AboutScreen(data: MarketData?, padding: PaddingValues) {
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                SectionTitle("Energy Intelligence Bulgaria", "Версия $version")
                Text(
                    "Анализ на електроенергийния пазар в България: цени ден напред, производство по източници, " +
                        "трансграничен обмен и доходност на батерийни системи (BESS).",
                    color = EColors.Text, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
        item {
            Card {
                SectionTitle("Източници на данни")
                Text(
                    "• Energy-Charts (Fraunhofer ISE), api.energy-charts.info — цени, производство, обмен.\n" +
                        "• Първичен източник: ENTSO-E Transparency Platform и БНЕБ (IBEX).\n" +
                        "• Цените са в €/MWh, зона BG, без мрежови такси, акциз и ДДС.\n" +
                        "• Обменът е по търговски графици: положително = внос, отрицателно = износ.",
                    color = EColors.Muted, fontSize = 13.sp, lineHeight = 20.sp,
                )
                data?.license?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Лиценз на цените: $it", color = EColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card {
                SectionTitle("Предстои")
                Text(
                    "• Директна връзка с ENTSO-E (личен API ключ) — прогнози за товара и ВЕИ.\n" +
                        "• Известия при отрицателни или много високи цени.\n" +
                        "• Сравнение със съседните зони (RO, GR, HU).\n" +
                        "• Калкулатор за фотоволтаик + батерия и балансиращ пазар.",
                    color = EColors.Muted, fontSize = 13.sp, lineHeight = 20.sp,
                )
            }
        }
    }
}
