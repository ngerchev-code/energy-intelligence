package com.ngerchev.energy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.data.Lesson

/** „Самоучител“ – a lesson every Sunday about what happened on the market that week (server-built, real data). */
@Composable
fun TutorialScreen(lessons: List<Lesson>, padding: PaddingValues) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAnswer by rememberSaveable(openId) { mutableStateOf(false) }
    val open = lessons.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Tile(color = EColors.Lime, radius = 30.dp) {
                    Text("УРОК ${open.number}", color = EColors.OnLime, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text(open.title.substringAfter("· "), color = EColors.OnLime, fontFamily = Body, fontSize = 13.sp)
                }
            }
            item {
                Tile {
                    SectionTitle("1 · Какво стана тази седмица", "ИСТОРИЯ · реални данни")
                    open.facts.forEach { Row(Modifier.padding(vertical = 4.dp)) { BodyText("•  $it") } }
                }
            }
            if (open.why.isNotEmpty()) item {
                Tile {
                    SectionTitle("2 · Как да го разчетем")
                    open.why.forEach { Row(Modifier.padding(vertical = 4.dp)) { BodyText(it) } }
                }
            }
            item {
                Tile {
                    SectionTitle("${if (open.why.isEmpty()) 2 else 3} · Понятие на седмицата")
                    Text(open.conceptTitle, color = EColors.Lime, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    BodyText(open.conceptText)
                    Spacer(Modifier.height(8.dp))
                    BodyText("Къде в приложението: ${open.conceptWhere}", EColors.Muted, 13)
                }
            }
            item {
                Tile(modifier = Modifier.clickable(role = Role.Button) { showAnswer = !showAnswer }) {
                    SectionTitle("Проверете се", "със сметка от реалните цени")
                    BodyText(open.question)
                    Spacer(Modifier.height(10.dp))
                    if (showAnswer) BodyText(open.answer, EColors.Lime)
                    else Text("Докоснете за отговора", color = EColors.Muted, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            if (open.news.isNotEmpty()) item {
                Tile {
                    SectionTitle("За четене", "новини от седмицата")
                    open.news.forEach { n -> LinkText("${n.title} · ${n.source}", n.link) }
                }
            }
            item { Notice("Източници", open.sources.joinToString(" · ") + "\n" + open.note) }
        }
        return
    }

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(color = EColors.Lime, radius = 30.dp) {
                Text("САМОУЧИТЕЛ", color = EColors.OnLime, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Нов урок всяка неделя: какво стана на пазара, защо и едно понятие с реални числа",
                    color = EColors.OnLime, fontFamily = Body, fontSize = 13.sp)
            }
        }
        if (lessons.isEmpty()) item {
            Notice("Още няма урок", "Първият урок се публикува в неделя след 08:00. Обновете след това.")
        }
        items(lessons, key = { it.id }) { l ->
            Tile(modifier = Modifier.clickable(role = Role.Button) { openId = l.id }) {
                Row {
                    Badge(if (l == lessons.first()) "НОВ" else "АРХИВ", if (l == lessons.first()) EColors.Lime else EColors.Muted)
                    Spacer(Modifier.width(8.dp))
                    Text(fmtTime(l.published), color = EColors.Muted, fontFamily = Body, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(l.title, color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                BodyText("Понятие: ${l.conceptTitle}", EColors.Muted, 13)
            }
        }
    }
}
