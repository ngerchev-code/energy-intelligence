package com.ngerchev.energy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.MainViewModel
import com.ngerchev.energy.logic.Updater
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun BigButton(text: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(if (!enabled) EColors.Tile else if (primary) EColors.Lime else EColors.Bg)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled && primary) EColors.OnLime else if (enabled) EColors.Text else EColors.Muted,
            fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** „Обновяване“: current version, automatic/manual check, download and install from GitHub Releases. */
@Composable
fun UpdateScreen(st: MainViewModel.UpdateState, auto: Boolean, onAuto: (Boolean) -> Unit,
                 onCheck: () -> Unit, onDownload: () -> Unit, padding: PaddingValues) {
    val ctx = LocalContext.current
    val current = Updater.currentName(ctx)
    val info = st.info
    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(color = if (st.available) EColors.Lime else EColors.Tile, radius = 30.dp) {
                val on = if (st.available) EColors.OnLime else EColors.Text
                Text("ВЕРСИЯ $current", color = on, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text(when {
                    st.checking -> "Проверявам за нова версия…"
                    st.available && info != null -> "Има нова версия ${info.name}"
                    info != null -> "Имате последната версия"
                    else -> "Още не е проверено"
                }, color = on, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (st.checkedAt > 0) Text("Последна проверка: " +
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("bg")).format(Date(st.checkedAt)),
                    color = on, fontFamily = Body, fontSize = 12.sp)
            }
        }
        item {
            Tile {
                SectionTitle("Режим")
                ChipRow(listOf("Автоматично", "Ръчно"), if (auto) "Автоматично" else "Ръчно",
                    { onAuto(it == "Автоматично") }, allLabel = null)
                Spacer(Modifier.height(8.dp))
                BodyText(if (auto) "При всяко отваряне приложението проверява за нова версия и я изтегля само. " +
                    "Остава едно докосване „Инсталирай“ – Android винаги иска потвърждение."
                else "Проверявате само когато натиснете „Провери сега“.", EColors.Muted, 13)
            }
        }
        item {
            Tile {
                BigButton(if (st.checking) "Проверявам…" else "Провери сега", enabled = !st.checking, primary = !st.available, onClick = onCheck)
                if (st.available && info != null) {
                    Spacer(Modifier.height(10.dp))
                    when {
                        st.progress != null -> {
                            Text("Изтегляне ${(st.progress * 100).toInt()} %", color = EColors.Text, fontFamily = Body,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(EColors.Bg)) {
                                Box(Modifier.fillMaxWidth(st.progress).height(8.dp).background(EColors.Lime))
                            }
                        }
                        st.apk != null -> BigButton("Инсталирай ${info.name}") {
                            if (Updater.canInstall(ctx)) Updater.install(ctx, File(st.apk)) else Updater.openInstallPermission(ctx)
                        }
                        else -> BigButton("Изтегли ${info.name} (${"%.1f".format(info.size / 1048576.0)} MB)", onClick = onDownload)
                    }
                    if (st.apk != null && !Updater.canInstall(ctx)) {
                        Spacer(Modifier.height(8.dp))
                        BodyText("Първия път Android ще отвори настройка „Разрешаване от този източник“ – включете я, " +
                            "върнете се и натиснете „Инсталирай“ отново.", EColors.Muted, 13)
                    }
                }
                st.error?.let { Spacer(Modifier.height(8.dp)); BodyText("Грешка: $it", EColors.Coral, 13) }
            }
        }
        if (info != null && info.notes.isNotBlank()) item {
            Tile {
                SectionTitle("Какво е новото", info.name)
                BodyText(info.notes)
            }
        }
        item {
            Notice("Откъде идват версиите", "От GitHub Releases на проекта (ngerchev-code/energy-intelligence), " +
                "подписани със същия ключ. Данните и настройките в приложението се запазват при обновяване.")
        }
    }
}

/** Small strip under the header when a newer version was found. */
@Composable
fun UpdateBanner(st: MainViewModel.UpdateState, onOpen: () -> Unit) {
    val info = st.info ?: return
    if (!st.available) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
        .background(EColors.Lime).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(if (st.apk != null) "Версия ${info.name} е изтеглена · Инсталирай" else "Нова версия ${info.name} · Обнови",
            color = EColors.OnLime, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
