package com.leslie.tvhome

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI

/** Caps and checks for a user-supplied Top Shelf feed. Pure: no network and no Android APIs. */
internal object ShelfLimits {
    const val MAX_ITEMS = 12
    const val MAX_TITLE = 120
    const val MAX_META = 180
    const val MAX_JSON_BYTES = 512 * 1024
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGE_EDGE = 1920
    const val MAX_URL_CHARS = 2048

    fun isHttpUrl(raw: String): Boolean {
        val s = raw.trim()
        if (s.length !in 8..MAX_URL_CHARS) return false
        if (s.any { it.isWhitespace() || it.code < 32 }) return false
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }

    /** Power-of-two [android.graphics.BitmapFactory.Options.inSampleSize] so the long edge stays near [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_IMAGE_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longest = maxOf(width, height)
        while (sample < longest && longest / (sample.toLong() * 2) >= maxEdge) sample *= 2
        return sample
    }

    fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
        if (maxBytes < 0) throw IOException("response too large")
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (total > maxBytes - n) throw IOException("response too large")
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

/**
 * The custom Top Shelf document. The native format is a JSON array of
 * `{title, meta, image}`. Matchora's public `{"events":[...]}` football feed
 * is also accepted and converted to the same display items.
 */
internal object ShelfJson {
    data class Item(val title: String, val meta: String, val image: String)

    fun parse(text: String): List<Item>? {
        val body = text.removePrefix("\uFEFF").trim()
        if (body.isEmpty()) return null
        return when {
            body.startsWith("[") -> parseArray(body)
            body.startsWith("{") -> parseEventFeed(body)
            else -> null
        }
    }

    private fun parseArray(body: String): List<Item>? {
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            return null
        }
        val out = ArrayList<Item>(ShelfLimits.MAX_ITEMS)
        for (i in 0 until array.length()) {
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val title = field(obj, "title").take(ShelfLimits.MAX_TITLE)
            val image = field(obj, "image")
            if (title.isEmpty() || !ShelfLimits.isHttpUrl(image)) continue
            out.add(Item(title, field(obj, "meta").take(ShelfLimits.MAX_META), image))
        }
        return out
    }

    /** Matchora's live/schedule response: `{ "events": [...] }`. */
    private fun parseEventFeed(body: String): List<Item>? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        if (root.has("subjects") || root.has("subject_collection_items")) {
            val parsed = DoubanFilms.parseOne(body)
            return parsed.map { Item(it.title, it.meta, it.image) }
        }
        val events = root.optJSONArray("events") ?: return null
        val out = ArrayList<Item>(ShelfLimits.MAX_ITEMS)
        for (i in 0 until events.length()) {
            if (out.size >= ShelfLimits.MAX_ITEMS) break
            val event = events.optJSONObject(i) ?: continue
            val sport = field(event, "sport")
            if (sport.isNotEmpty() && !sport.equals("Soccer", ignoreCase = true)) continue

            val home = field(event, "home")
            val away = field(event, "away")
            val title = when {
                home.isNotEmpty() && away.isNotEmpty() -> "$home vs $away"
                home.isNotEmpty() -> home
                else -> away
            }.take(ShelfLimits.MAX_TITLE)
            if (title.isEmpty()) continue

            val meta = listOf(
                field(event, "league"),
                field(event, "score"),
                statusLabel(event),
            ).filter { it.isNotEmpty() }.joinToString(" · ").take(ShelfLimits.MAX_META)

            val image = listOf("image", "thumbnail", "background_image")
                .map { field(event, it) }
                .firstOrNull { ShelfLimits.isHttpUrl(it) }
                ?: if (out.size % 2 == 0) Shelf.FOOTBALL_ART else Shelf.FOOTBALL_ART_2
            out.add(Item(title, meta, image))
        }
        return out
    }

    private fun statusLabel(event: JSONObject): String {
        val minute = field(event, "minute")
        if (minute.isNotEmpty()) return "$minute′"
        return when (field(event, "status").uppercase()) {
            "HT" -> "中场"
            "FT" -> "完场"
            "LIVE" -> "直播中"
            "NS", "NOTSTARTED" -> "未开始"
            "CANCELED" -> "已取消"
            else -> field(event, "status")
        }
    }

    private fun field(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.opt(key)
        if (value !is String && value !is Number) return ""
        return value.toString().trim()
    }
}

