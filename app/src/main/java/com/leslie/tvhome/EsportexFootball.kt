package com.leslie.tvhome

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Public EmbedSportex football feed with 16:9 match posters. */
internal object EsportexFootball {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private data class Entry(val item: Shelf.Item, val priority: Int, val status: Int, val kickoff: LocalDateTime)

    fun items(body: String, now: LocalDateTime = LocalDateTime.now()): List<Shelf.Item> {
        val root = try {
            JSONObject(body.removePrefix("﻿").trim())
        } catch (_: Exception) {
            return emptyList()
        }
        val football = root.optJSONArray("football") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (i in 0 until football.length()) {
            val row = football.optJSONObject(i) ?: continue
            val title = row.optString("tag").trim()
            val slug = row.optString("slug").trim()
            val poster = fullPoster(row.optString("poster").trim())
            val kickoff = parse(row.optString("kickoff"))
            val end = parse(row.optString("endTime"))
            if (title.isEmpty() || poster.isEmpty() || kickoff == null) continue
            if (!seen.add(slug.ifEmpty { title })) continue
            val status = when {
                now.isBefore(kickoff) -> 1
                end == null || now.isBefore(end) -> 0
                else -> 2
            }
            if (status == 2) continue
            val competition = competition(row, slug)
            val state = when (status) {
                0 -> "直播中"
                1 -> "未开始 · " + kickoff.format(DateTimeFormatter.ofPattern("HH:mm"))
                else -> "已结束"
            }
            val meta = listOf(competition, state).filter { it.isNotEmpty() }.joinToString(" · ").take(ShelfLimits.MAX_META)
            out += Entry(
                Shelf.Item(title.take(ShelfLimits.MAX_TITLE), meta, poster),
                priority(competition),
                status,
                kickoff,
            )
        }
        return out.sortedWith(compareBy<Entry> { it.priority }.thenBy { it.status }.thenBy { it.kickoff })
            .take(ShelfLimits.MAX_ITEMS)
            .map { it.item }
    }

    private fun fullPoster(raw: String): String {
        if (!ShelfLimits.isHttpUrl(raw)) return ""
        return Regex("""-thumbnail\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE).replace(raw, ".$1")
    }

    private fun parse(raw: String): LocalDateTime? = try {
        if (raw.isBlank()) null else LocalDateTime.parse(raw.trim(), TIME)
    } catch (_: Exception) {
        null
    }

    private fun competition(row: JSONObject, slug: String): String {
        val league = row.optString("league").trim().lowercase()
        val decoded = decodedFrameIds(row).lowercase() + " " + slug.lowercase()
        return when {
            league.contains("premier league") || decoded.contains("soccer-eng-1") -> "英超"
            league.contains("la liga") || decoded.contains("soccer-esp-1") -> "西甲"
            league.contains("serie a") || decoded.contains("soccer-ita-1") -> "意甲"
            league.contains("bundesliga") || decoded.contains("soccer-ger-1") -> "德甲"
            league.contains("ligue 1") || decoded.contains("soccer-fra-1") -> "法甲"
            league.contains("championship") || league.contains("efl championship") || decoded.contains("soccer-eng-2") -> "英冠"
            league.contains("champions league") || decoded.contains("soccer-uefa-champions") || slug.contains("champions-league") -> "欧冠"
            league.contains("europa league") || decoded.contains("soccer-uefa-europa") || slug.contains("europa-league") -> "欧联"
            league.contains("world cup") -> "世界杯"
            league.contains("european championship") || league.contains("euro ") -> "欧洲杯"
            league.contains("copa america") -> "美洲杯"
            league.contains("asian cup") -> "亚洲杯"
            league.contains("liga mx") -> "墨超"
            league.contains("belgian pro league") -> "比甲"
            league.contains("primeira liga") || decoded.contains("soccer-por-1") -> "葡超"
            league.contains("eredivisie") || decoded.contains("soccer-ned-1") -> "荷甲"
            league.isBlank() || league == "football" -> "足球"
            else -> row.optString("league").trim()
        }
    }

    private fun decodedFrameIds(row: JSONObject): String {
        val frames = row.optJSONArray("iframes") ?: return ""
        val values = ArrayList<String>()
        for (i in 0 until frames.length()) {
            val url = frames.optJSONObject(i)?.optString("url").orEmpty()
            val encoded = url.substringAfterLast('#', "")
            if (encoded.isEmpty()) continue
            val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }
                .recoverCatching { Base64.getDecoder().decode(encoded) }
                .getOrNull() ?: continue
            values += bytes.toString(Charsets.UTF_8)
        }
        return values.joinToString(" ")
    }

    private fun priority(competition: String): Int = when (competition) {
        "世界杯", "欧洲杯", "美洲杯", "亚洲杯" -> 0
        "欧冠", "欧联" -> 1
        "英超", "西甲", "意甲", "德甲", "法甲" -> 2
        "英冠", "葡超", "荷甲", "墨超", "比甲" -> 3
        else -> 5
    }
}
