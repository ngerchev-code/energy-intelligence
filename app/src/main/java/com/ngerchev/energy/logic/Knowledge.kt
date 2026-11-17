package com.ngerchev.energy.logic

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

data class Source(val title: String, val url: String)

data class Article(
    val id: String, val title: String, val summary: String, val text: String,
    val category: String, val type: String, val difficulty: String, val tags: List<String>,
    val sources: List<Source>, val verification: String, val published: String, val reviewed: String,
    val readingMinutes: Int,
)

/** The "Знаете ли, че...?" library, bundled with the app (works offline). */
class Knowledge(val categories: List<String>, val articles: List<Article>) {

    /** Same fact for everyone on a given day. */
    fun daily(day: LocalDate = LocalDate.now()): Article? =
        articles.takeIf { it.isNotEmpty() }?.let { it[(day.toEpochDay() % it.size).toInt()] }

    fun search(query: String, category: String?): List<Article> {
        val q = query.trim().lowercase()
        return articles.filter { a ->
            (category == null || a.category == category) &&
                (q.isEmpty() || listOf(a.title, a.summary, a.text, a.tags.joinToString(" ")).any { q in it.lowercase() })
        }
    }

    /** Articles sharing the category or a tag. */
    fun related(a: Article): List<Article> =
        articles.filter { it.id != a.id && (it.category == a.category || it.tags.any { t -> t in a.tags }) }.take(3)

    companion object {
        fun parse(json: String): Knowledge {
            val o = JSONObject(json)
            val cats = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getString(it) } }
            val arts = o.getJSONArray("articles").let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Article(
                        id = x.getString("id"), title = x.getString("title"), summary = x.getString("summary"),
                        text = x.getString("text"), category = x.getString("category"), type = x.getString("type"),
                        difficulty = x.getString("difficulty"),
                        tags = x.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getString(it) } },
                        sources = x.getJSONArray("sources").let { s ->
                            (0 until s.length()).map { Source(s.getJSONObject(it).getString("title"), s.getJSONObject(it).getString("url")) }
                        },
                        verification = x.getString("verification"), published = x.getString("published"),
                        reviewed = x.getString("reviewed"), readingMinutes = x.getInt("reading_minutes"),
                    )
                }
            }
            return Knowledge(cats, arts)
        }

        fun load(ctx: Context): Knowledge? = runCatching {
            parse(ctx.assets.open("knowledge.json").bufferedReader().use { it.readText() })
        }.getOrNull()
    }
}
