package com.ngerchev.energy.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngerchev.energy.logic.Article
import com.ngerchev.energy.logic.Knowledge

private class Favs(ctx: Context) {
    private val p = ctx.getSharedPreferences("favs", Context.MODE_PRIVATE)
    var ids by mutableStateOf(p.getStringSet("ids", emptySet())!!.toSet())
    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
        p.edit().putStringSet("ids", ids).apply()
    }
}

/** "Знаете ли, че...?" – searchable energy encyclopedia. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgeScreen(kb: Knowledge?, padding: PaddingValues) {
    val ctx = LocalContext.current
    val favs = remember { Favs(ctx) }
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFav by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    if (kb == null) { Notice("Справочникът не можа да се зареди", ""); return }

    val open = kb.articles.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        ArticleView(open, kb, open.id in favs.ids, { favs.toggle(open.id) }, { openId = it }, padding)
        return
    }
    val list = kb.search(query, cat).filter { !onlyFav || it.id in favs.ids }
    val daily = kb.daily()

    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(color = EColors.Lime, radius = 30.dp) {
                Text("ЗНАЕТЕ ЛИ, ЧЕ...?", color = EColors.OnLime, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Енергия, технологии и открития за любознателните", color = EColors.OnLime, fontFamily = Body, fontSize = 13.sp)
            }
        }
        if (daily != null && query.isBlank() && cat == null && !onlyFav) item {
            Tile(modifier = Modifier.clickable(role = Role.Button) { openId = daily.id }) {
                Badge("ФАКТ НА ДЕНЯ", EColors.Sand)
                Spacer(Modifier.height(8.dp))
                Text(daily.title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                BodyText(daily.summary, EColors.Muted, 13)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(EColors.Tile).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", color = EColors.Muted, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = EColors.Text, fontFamily = Body, fontSize = 15.sp),
                    cursorBrush = SolidColor(EColors.Lime),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Търсене в справочника" },
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Търсене: батерии, синтез, IBEX…", color = EColors.Muted, fontFamily = Body, fontSize = 15.sp)
                        inner()
                    },
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (onlyFav) "★ Любими" else "☆ Любими", onlyFav) { onlyFav = !onlyFav }
            }
        }
        item {
            val used = kb.articles.map { it.category }.toSet()
            ChipRow(kb.categories.filter { it in used }, cat, { cat = it })
        }
        if (list.isEmpty()) item { Notice("Нищо не е намерено", "Опитайте с друга дума или махнете филтъра.") }
        items(list, key = { it.id }) { a ->
            Tile(modifier = Modifier.clickable(role = Role.Button) { openId = a.id }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(a.category, EColors.Sand); Badge(a.type, EColors.Track)
                }
                Spacer(Modifier.height(8.dp))
                Text(a.title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                BodyText(a.summary, EColors.Muted, 13)
                Spacer(Modifier.height(6.dp))
                Label("${a.readingMinutes} мин. четене · ${a.difficulty}${if (a.id in favs.ids) " · ★" else ""}")
            }
        }
        item {
            BodyText("Броят на статиите расте. Текстовете са съставени с AI и са отбелязани така; всеки има връзки към " +
                "източници за проверка.", EColors.Muted, 12)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArticleView(a: Article, kb: Knowledge, fav: Boolean, onFav: () -> Unit, onOpen: (String) -> Unit, padding: PaddingValues) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Tile(radius = 30.dp) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(a.category, EColors.Sand); Badge(a.type, EColors.Lime); Badge(a.difficulty, EColors.Sky)
                }
                Spacer(Modifier.height(10.dp))
                Text(a.title, color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
                Spacer(Modifier.height(8.dp))
                Text(a.summary, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(10.dp))
                BodyText(a.text, size = 15)
                Spacer(Modifier.height(10.dp))
                Label("${a.readingMinutes} мин. · публикувано ${a.published} · последен преглед ${a.reviewed}")
                Spacer(Modifier.height(6.dp))
                Badge(a.verification, EColors.Coral)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (fav) "★ В любими" else "☆ Запази", fav, onFav)
                    Chip("Сподели", false) {
                        shareText(ctx, a.title, "${a.title}\n\n${a.summary}\n\nИзточник: ${a.sources.firstOrNull()?.url ?: ""}\n— Енерго BG")
                    }
                }
            }
        }
        item {
            Tile {
                SectionTitle("Източници")
                a.sources.forEach { LinkText(it.title, it.url) }
                Label("Тагове: ${a.tags.joinToString()}")
            }
        }
        val rel = kb.related(a)
        if (rel.isNotEmpty()) item {
            Tile {
                SectionTitle("Свързани")
                rel.forEach { r ->
                    Text(r.title, color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(r.id) }.padding(vertical = 8.dp))
                }
            }
        }
    }
}
