package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Top Shelf content. Android TV's own channel API is empty on the TVs this
 * was written for, so a few apps are filled from public web APIs: Bilibili
 * popular videos (云视听小电视 and the third-party client com.tutu.myblbl)
 * and Douyu live rooms.
 *
 * Settings can also point one dock app at a JSON URL. That feed is cached
 * with the same 15-minute policy. A bad address, unusable JSON, or a timeout
 * leaves the built-in feed for that package in place when one exists.
 */
object Shelf {
    data class Item(val title: String, val meta: String, val image: String)
    data class Source(val name: String, val fetch: () -> List<Item>, val ttlMs: Long = TTL)

    /** One user-entered feed, shown when [pkg] is focused in the dock. */
    data class Custom(val pkg: String, val url: String)

    internal enum class ShelfPick { CUSTOM, BUILTIN, NONE }

    internal const val FOOTBALL_ART = "asset:football"
    internal const val FOOTBALL_ART_2 = "asset:football:2"
    internal const val FOOTBALL_PKG = "com.qiubo.optimaltv.public"
    internal const val DOUBAN_PKG = "com.dongpiandi"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val TTL = 15 * 60_000L
    private const val FOOTBALL_TTL = 60_000L
    private const val DOUBAN_TTL = 30 * 60_000L
    private const val DOUBAN_MOVIES = "https://movie.douban.com/j/search_subjects?type=movie&tag=%E7%83%AD%E9%97%A8&page_limit=20&page_start=0"
    private const val DOUBAN_TV = "https://movie.douban.com/j/search_subjects?type=tv&tag=%E7%83%AD%E9%97%A8&page_limit=20&page_start=0"
    private const val ESPORTEX_FOOTBALL = "https://api.esportex.site/api/streams"
    /** Built-in responses are small; the cap only stops a runaway body. */
    private const val BUILTIN_JSON_BYTES = 2 * 1024 * 1024

    /** Package → which public feed it shares. Both Bilibili clients use `bilibili`. */
    internal val builtInPackages = mapOf(
        "com.xiaodianshi.tv.yst" to "bilibili",
        "com.tutu.myblbl" to "bilibili",
        "com.douyu.xl.douyutv" to "douyu",
        FOOTBALL_PKG to "football",
        DOUBAN_PKG to "douban",
    )

    private data class Cached(val at: Long, val name: String, val items: List<Item>)

    private val sources get() = builtInPackages.mapValues { (_, kind) ->
        when (kind) {
            "bilibili" -> Source(I18n.text(R.string.i18n_shelf_001), ::bilibili)
            "football" -> Source(I18n.text(R.string.i18n_shelf_007), ::football, FOOTBALL_TTL)
            "douban" -> Source(I18n.text(R.string.i18n_shelf_008), ::douban, DOUBAN_TTL)
            else -> Source(I18n.text(R.string.i18n_shelf_002), ::douyu)
        }
    }

    private val cache = HashMap<String, Cached>()
    private val shownName = HashMap<String, String>()
    private val footballTitleCache = HashMap<String, String>()

    /**
     * Custom wins only when the linked package matches and the body was usable.
     * Otherwise a known package keeps its built-in feed.
     */
    internal fun pickShelf(pkg: String, custom: Custom?, customOk: Boolean): ShelfPick {
        // The football tile has a built-in Chinese live feed; an older custom URL
        // must not silently replace it with English data.
        if (pkg == FOOTBALL_PKG || pkg == DOUBAN_PKG) return ShelfPick.BUILTIN
        val linked = custom?.pkg?.trim().orEmpty()
        val url = custom?.url?.trim().orEmpty()
        if (linked == pkg && ShelfLimits.isHttpUrl(url) && customOk) return ShelfPick.CUSTOM
        if (pkg in builtInPackages) return ShelfPick.BUILTIN
        return ShelfPick.NONE
    }

    fun has(pkg: String, custom: Custom? = null) = pickShelf(pkg, custom, customOk = true) != ShelfPick.NONE

    fun sourceName(pkg: String) = synchronized(cache) {
        shownName[pkg] ?: sources[pkg]?.name ?: ""
    }

    /** Items for [pkg], cached for a while; empty if the app has no shelf or the network failed. */
    fun items(pkg: String, custom: Custom? = null): List<Item> {
        if (pickShelf(pkg, custom, customOk = true) == ShelfPick.CUSTOM) {
            val url = custom!!.url.trim()
            val key = "custom\u0000$pkg\u0000$url"
            readCache(key, pkg)?.let { return it }
            val fetched = try {
                fetchCustom(url)
            } catch (e: Exception) {
                Log.w("Shelf", "custom $pkg failed", e)
                null
            }
            if (!fetched.isNullOrEmpty()) {
                val name = I18n.text(R.string.shelf_custom_source)
                writeCache(key, pkg, name, fetched)
                return fetched
            }
        }
        val src = sources[pkg] ?: return emptyList()
        readCache(pkg, pkg, src.ttlMs)?.let { return it }
        val list = try {
            src.fetch()
        } catch (e: Exception) {
            Log.w("Shelf", "$pkg failed", e)
            emptyList()
        }
        if (list.isNotEmpty()) writeCache(pkg, pkg, src.name, list)
        return list
    }

    /** Downloads (or reads from the disk cache) an item's picture. */
    fun image(ctx: Context, url: String): Bitmap? {
        if (url == FOOTBALL_ART || url == FOOTBALL_ART_2) {
            val art = if (url == FOOTBALL_ART_2) R.drawable.football_shelf_2 else R.drawable.football_shelf
            return BitmapFactory.decodeResource(ctx.resources, art)
                ?.let(::darken)
                ?.also { it.prepareToDraw() }
        }
        if (!ShelfLimits.isHttpUrl(url)) return null
        val dir = File(ctx.cacheDir, "shelf").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        if (!f.exists()) try {
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(get(url, ShelfLimits.MAX_IMAGE_BYTES))
            tmp.renameTo(f)
            // Keep the cache small: the newest 60 pictures.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("Shelf", "image $url failed", e)
            return null
        }
        return decodeCapped(f.path)
            ?.let(::trimBars)
            ?.let(::darken)
            // Start the GPU upload now, off the frame that first shows it.
            ?.also { it.prepareToDraw() }
    }

    private fun fetchCustom(url: String): List<Item>? {
        val text = get(url, ShelfLimits.MAX_JSON_BYTES).toString(Charsets.UTF_8)
        val parsed = ShelfJson.parse(text) ?: return null
        if (parsed.isEmpty()) return null
        return parsed.map { Item(it.title, it.meta, it.image) }
    }

    private fun readCache(key: String, pkg: String, ttlMs: Long = TTL): List<Item>? = synchronized(cache) {
        val hit = cache[key] ?: return null
        if (System.currentTimeMillis() - hit.at >= ttlMs) return null
        shownName[pkg] = hit.name
        hit.items
    }

    private fun writeCache(key: String, pkg: String, name: String, items: List<Item>) {
        synchronized(cache) {
            cache[key] = Cached(System.currentTimeMillis(), name, items)
            shownName[pkg] = name
        }
    }

    private fun decodeCapped(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ShelfLimits.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /**
     * Bakes the scrim that keeps the title readable into the picture itself:
     * a full-screen gradient layer on top would cost the TV's GPU three more
     * full-screen passes on every frame of a cross-fade.
     */
    private fun darken(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
        ShelfScrim().apply { setBounds(0, 0, out.width, out.height) }.draw(android.graphics.Canvas(out))
        return out
    }

    /** Live-stream covers are often letterboxed; cut the black bars so the picture fills the screen. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(y: Int): Boolean {
            var max = 0
            for (k in 0 until 32) {
                val c = b.getPixel(k * (b.width - 1) / 31, y)
                max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            return max < 28
        }
        var top = 0
        while (top < b.height / 4 && dark(top)) top++
        var bottom = b.height
        while (bottom > b.height * 3 / 4 && dark(bottom - 1)) bottom--
        if (top < 4 && b.height - bottom < 4) return b
        return Bitmap.createBitmap(b, 0, top, b.width, bottom - top)
    }

    private fun bilibili(): List<Item> {
        val list = JSONObject(get("https://api.bilibili.com/x/web-interface/popular?ps=12&pn=1", BUILTIN_JSON_BYTES).decodeToString())
            .getJSONObject("data").getJSONArray("list")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { v ->
            Item(
                v.optString("title"),
                I18n.text(R.string.i18n_shelf_003, v.getJSONObject("owner").optString("name"), count(v.getJSONObject("stat").optLong("view"))),
                v.optString("pic").replace("http://", "https://") + "@1280w_720h_1c.jpg",
            )
        }
    }

    private fun football(): List<Item> {
        val body = get(ESPORTEX_FOOTBALL, BUILTIN_JSON_BYTES).decodeToString()
        return translateFootballTitles(EsportexFootball.items(body))
    }

    /** MyMemory supports a small batch; cache the result for this process so refreshes do not re-request. */
    private fun translateFootballTitles(items: List<Item>): List<Item> {
        val missing = items.map { it.title }
            .filter { it.any { ch -> ch in '\u4e00'..'\u9fff' }.not() }
            .distinct()
        synchronized(footballTitleCache) {
            val todo = missing.filter { it !in footballTitleCache }
            if (todo.isNotEmpty()) {
                val query = todo.joinToString("\n")
                try {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=en%7Czh-CN"
                    val json = JSONObject(get(url, 512 * 1024).decodeToString())
                    val translated = json.getJSONObject("responseData").optString("translatedText")
                    val lines = translated.split('\n')
                    if (lines.size == todo.size) {
                        todo.zip(lines).forEach { (source, target) ->
                            val text = cleanTranslation(target)
                            if (text.isNotEmpty()) footballTitleCache[source] = text
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Shelf", "football title translation failed", e)
                }
            }
            return items.map { item ->
                val title = footballTitleCache[item.title] ?: item.title
                item.copy(title = title.take(ShelfLimits.MAX_TITLE))
            }
        }
    }

    private fun cleanTranslation(raw: String): String {
        return raw.replace("&amp;", "&")
            .replace(Regex("""\s*vs\s*""", RegexOption.IGNORE_CASE), " VS ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun douban(): List<Item> {
        val movies = DoubanFilms.entries(get(DOUBAN_MOVIES, BUILTIN_JSON_BYTES).decodeToString(), "电影")
        val tv = DoubanFilms.entries(get(DOUBAN_TV, BUILTIN_JSON_BYTES).decodeToString(), "剧集")
        val chosen = ArrayList<DoubanFilms.Entry>()
        for (i in 0 until maxOf(movies.size, tv.size)) {
            if (chosen.size >= 16) break
            movies.getOrNull(i)?.let { chosen += it }
            if (chosen.size >= 16) break
            tv.getOrNull(i)?.let { chosen += it }
        }
        val pool = Executors.newFixedThreadPool(4)
        return try {
            val futures = chosen.map { entry ->
                pool.submit(Callable { entry to doubanLandscape(entry) })
            }
            futures.mapNotNull { future ->
                runCatching { future.get(10, TimeUnit.SECONDS) }.getOrNull()
                    ?.let { (entry, image) -> image?.let { Item(entry.title, entry.meta, it) } }
            }.take(ShelfLimits.MAX_ITEMS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Douban trailer covers are 16:9; fall back to its landscape production stills. */
    private fun doubanLandscape(entry: DoubanFilms.Entry): String? {
        val type = if (entry.kind == "剧集") "tv" else "movie"
        val base = "https://m.douban.com/rexxar/api/v2/$type/${entry.id}"
        val detail = JSONObject(get(base, BUILTIN_JSON_BYTES).decodeToString())
        val trailers = detail.optJSONArray("trailers")
        if (trailers != null) {
            for (i in 0 until trailers.length()) {
                val url = trailers.optJSONObject(i)?.optString("cover_url").orEmpty().trim()
                if (ShelfLimits.isHttpUrl(url)) return doubanImage(url)
            }
        }
        val photos = JSONObject(get("$base/photos?start=0&count=6", BUILTIN_JSON_BYTES).decodeToString())
            .optJSONArray("photos") ?: return null
        for (i in 0 until photos.length()) {
            val image = photos.optJSONObject(i)?.optJSONObject("image") ?: continue
            val large = image.optJSONObject("large") ?: continue
            val width = large.optInt("width")
            val height = large.optInt("height")
            val url = large.optString("url").trim()
            if (width > 0 && height > 0 && width >= height * 1.2 && ShelfLimits.isHttpUrl(url)) {
                return doubanImage(url)
            }
        }
        return null
    }

    private fun doubanImage(raw: String): String = raw
        .replace("http://", "https://")
        .replace(Regex("""https://img\d+\.doubanio\.com/"""), "https://img2.doubanio.com/")

    private fun douyu(): List<Item> {
        val list = JSONObject(get("https://www.douyu.com/japi/weblist/apinc/allpage/6/1", BUILTIN_JSON_BYTES).decodeToString())
            .getJSONObject("data").getJSONArray("rl")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("rs16").isNotEmpty() }
            .take(12)
            .map { r ->
                Item(
                    r.optString("rn"),
                    I18n.text(R.string.i18n_shelf_004, r.optString("nn"), r.optString("c2name"), count(r.optLong("ol"))),
                    // "/dy4" is the 1280×720 JPEG rendition of the AVIF cover.
                    r.optString("rs16").substringBeforeLast('/') + "/dy4",
                )
            }
    }

    private fun count(n: Long): String = if (I18n.isChinese()) {
        when {
            n >= 100_000_000 -> I18n.text(R.string.i18n_shelf_005, n / 100_000_000.0)
            n >= 10_000 -> I18n.text(R.string.i18n_shelf_006, n / 10_000.0)
            else -> n.toString()
        }
    } else {
        when {
            n >= 1_000_000 -> I18n.text(R.string.i18n_shelf_005, n / 1_000_000.0)
            n >= 1_000 -> I18n.text(R.string.i18n_shelf_006, n / 1_000.0)
            else -> n.toString()
        }
    }

    private fun get(url: String, maxBytes: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        // B站's API answers with an empty body when sent a Referer from outside its pages.
        if ("douyu" in url) c.setRequestProperty("Referer", "https://www.douyu.com/")
        if ("7m.com.cn" in url) c.setRequestProperty("Referer", "https://www.7m.com.cn/")
        if ("douban.com" in url || "doubanio.com" in url) {
            c.setRequestProperty("Referer", "https://movie.douban.com/")
        }
        if ("ppvservices.st" in url) c.setRequestProperty("Referer", "https://api.esportex.site/")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val advertised = c.contentLengthLong
            if (advertised > maxBytes) throw java.io.IOException("response too large")
            return c.inputStream.use { ShelfLimits.readAtMost(it, maxBytes) }
        } finally {
            c.disconnect()
        }
    }
}


