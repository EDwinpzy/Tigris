package com.leslie.tvhome

import org.json.JSONObject
import java.util.Locale

/** Parses Douban's public movie and TV ranking responses into shelf items. */
internal object DoubanFilms {

    data class Entry(val id: String, val title: String, val kind: String, val meta: String)

    /** Extracts identity and Chinese metadata; artwork is fetched separately as a 16:9 still. */
    fun entries(body: String, kind: String): List<Entry> {
        val root = try {
            JSONObject(body.removePrefix("﻿").trim())
        } catch (_: Exception) {
            return emptyList()
        }
        val subjects = root.optJSONArray("subjects")
            ?: root.optJSONArray("subject_collection_items")
            ?: return emptyList()
        val out = ArrayList<Entry>(ShelfLimits.MAX_ITEMS)
        for (i in 0 until subjects.length()) {
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            val row = subjects.optJSONObject(i) ?: continue
            val id = row.optString("id").trim()
            val title = row.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val meta = listOf(kind, rating(row), row.optString("episodes_info").trim())
                .filter { it.isNotEmpty() }.joinToString(" · ").take(ShelfLimits.MAX_META)
            out += Entry(id, title.take(ShelfLimits.MAX_TITLE), kind, meta)
        }
        return out
    }

    fun interleave(moviesBody: String, tvBody: String): List<Shelf.Item> {
        val movies = parseOne(moviesBody, "电影")
        val tv = parseOne(tvBody, "剧集")
        val out = ArrayList<Shelf.Item>(ShelfLimits.MAX_ITEMS)
        val count = maxOf(movies.size, tv.size)
        for (i in 0 until count) {
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            movies.getOrNull(i)?.let { out += it }
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            tv.getOrNull(i)?.let { out += it }
        }
        return out
    }

    fun parseOne(body: String, kind: String = ""): List<Shelf.Item> {
        val root = try {
            JSONObject(body.removePrefix("﻿").trim())
        } catch (_: Exception) {
            return emptyList()
        }
        val subjects = root.optJSONArray("subjects")
            ?: root.optJSONArray("subject_collection_items")
            ?: return emptyList()
        val out = ArrayList<Shelf.Item>(ShelfLimits.MAX_ITEMS)
        for (i in 0 until subjects.length()) {
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            val row = subjects.optJSONObject(i) ?: continue
            val title = row.optString("title").trim()
            val image = poster(row)
            if (title.isEmpty() || image.isEmpty()) continue
            val actualKind = kind.ifEmpty {
                when (row.optString("type")) {
                    "tv" -> "剧集"
                    "movie" -> "电影"
                    else -> "豆瓣"
                }
            }
            val meta = listOf(
                actualKind,
                rating(row),
                row.optString("episodes_info").trim(),
            ).filter { it.isNotEmpty() }.joinToString(" · ").take(ShelfLimits.MAX_META)
            out += Shelf.Item(title.take(ShelfLimits.MAX_TITLE), meta, image)
        }
        return out
    }

    private fun poster(row: JSONObject): String {
        val raw = when (val cover = row.opt("cover")) {
            is String -> cover
            is JSONObject -> cover.optString("url")
            else -> row.optString("pic")
        }.trim()
        if (raw.isEmpty()) return ""
        return raw
            .replace("http://", "https://")
            .replace(Regex("""https://img\d+\.doubanio\.com/"""), "https://img2.doubanio.com/")
            .replace("/s_ratio_poster/", "/l_ratio_poster/")
            .replace("/m_ratio_poster/", "/l_ratio_poster/")
    }

    private fun rating(row: JSONObject): String {
        val direct = row.optString("rate").trim()
        val value = direct.toDoubleOrNull()
            ?: row.optJSONObject("rating")?.optDouble("value", 0.0)
            ?: 0.0
        if (value <= 0.0) return ""
        val text = if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "豆瓣 $text"
    }
}
