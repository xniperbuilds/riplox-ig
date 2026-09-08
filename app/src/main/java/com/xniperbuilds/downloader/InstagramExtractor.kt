package com.xniperbuilds.downloader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

/**
 * ROUTE 0 — Instagram's own web API, called directly, with the user's own login.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────────────────────
 * Until now this app had exactly ONE way to get a file: yt-dlp. That is a single point of
 * failure with no second door — the day Instagram changes something yt-dlp has not caught up
 * with, every download in the app fails at once and there is nothing the user (or we) can do
 * but wait for an engine update. Riplox TT solved the same problem with its own extractor; this
 * is Instagram's version of that.
 *
 * It buys three things at once:
 *  1. **Photos.** yt-dlp cannot download them and by design never will — its Instagram
 *     extractor skips every node that is not a video (`__typename != 'GraphVideo'`) and then
 *     raises "There is no video in this post". This endpoint returns images and video from the
 *     same JSON, so the app's oldest known gap closes as a side effect of the second route.
 *  2. **Speed.** No Python process to spawn, no engine to warm up — one HTTPS call, then the
 *     bytes. Measured against the yt-dlp path this is the difference between a couple of
 *     seconds and tens of seconds.
 *  3. **A real fallback.** If anything here disappoints — a 4xx, a shape we do not recognise,
 *     an empty item list — we return quietly and yt-dlp runs exactly as it always did. Route 0
 *     can only ever ADD successes; it cannot subtract any.
 *
 * ── WHAT IT DELIBERATELY DOES NOT HANDLE ───────────────────────────────────────────────────
 *  · **MP3.** Extracting audio needs ffmpeg, which is the engine's job. Audio mode skips this
 *    route entirely rather than pretending.
 *  · **Stories and highlights.** They live behind a different endpoint with a different shape.
 *    yt-dlp already handles them, so guessing here would add risk and buy nothing.
 *  · **Profile links.** One post per call, on purpose.
 * In all three cases `shortcodeOf` returns null and the caller falls straight through.
 *
 * ── THE PRIVACY LINE, UNCHANGED ────────────────────────────────────────────────────────────
 * This talks to instagram.com and nobody else. There is no server of ours in the path — the
 * request carries the user's own session cookie, from their own phone, to Instagram, exactly as
 * the Instagram website would. Nothing about a download leaves the device.
 */
object InstagramExtractor {

    private const val TAG = "RiploxIG"

    /**
     * Instagram's public web-app id. Not a secret and not ours — it is the same value every
     * instagram.com page sends from a browser, and the API rejects the call without it.
     */
    private const val IG_APP_ID = "936619743392459"

    /** The shortcode alphabet: a post's shortcode is its numeric media id in base 64. */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    // Short on purpose. This route runs FIRST, so every second it spends failing is a second
    // added to the yt-dlp path behind it. Fail fast, fall through, let the engine work.
    private const val API_CONNECT_MS = 10_000
    private const val API_READ_MS = 15_000

    // The media transfer gets a longer read window — a 4K reel on a weak connection is slow,
    // not stuck.
    private const val MEDIA_CONNECT_MS = 15_000
    private const val MEDIA_READ_MS = 30_000

    private const val MAX_ITEMS = 200

    data class Item(val url: String, val isVideo: Boolean, val width: Int, val height: Int)

    data class Post(
        val shortcode: String,
        val user: String,
        val title: String,
        val thumb: String?,
        val items: List<Item>
    )

    // ── One fetch, two consumers ───────────────────────────────────────────────────────────
    // The preview (title + thumbnail for the notification and the share sheet) and the download
    // itself both want the same JSON. Without this they would each make their own call, which
    // is a wasted round trip and — worse — two chances to trip Instagram's rate limiter for one
    // user action.
    private const val CACHE_MS = 120_000L
    private var cachedLink: String? = null
    private var cachedPost: Post? = null
    private var cachedAt = 0L

    // A failure is remembered too, briefly — see the note in fetch().
    private const val FAIL_CACHE_MS = 60_000L
    private var failedKey: String? = null
    private var failedAt = 0L

    @Synchronized
    private fun markFailed(k: String) {
        failedKey = k; failedAt = System.currentTimeMillis()
    }

    // ⚠️ The key carries the QUALITY setting, not just the link. The parsed Post already has a
    // video variant chosen for it, so a cache keyed on the link alone would hand a download
    // started right after a quality change the OLD resolution — silently, and only sometimes.
    private fun key(context: Context, link: String) = "${Prefs.quality(context)}|$link"

    @Synchronized
    private fun fromCache(k: String): Post? {
        val p = cachedPost ?: return null
        if (cachedLink != k) return null
        if (System.currentTimeMillis() - cachedAt > CACHE_MS) return null
        return p
    }

    @Synchronized
    private fun putCache(k: String, post: Post) {
        cachedLink = k; cachedPost = post; cachedAt = System.currentTimeMillis()
    }

    // ── URL → shortcode → media id ─────────────────────────────────────────────────────────

    private val POST_RE = Regex("""/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")

    /**
     * The shortcode for a single post/reel, or null for anything this route will not touch
     * (stories, highlights, profiles, or a URL shape we do not recognise). Null is not an
     * error — it is how the caller is told to use the engine instead.
     */
    fun shortcodeOf(link: String): String? = POST_RE.find(link)?.groupValues?.get(1)

    /**
     * A shortcode is a base-64 number. BigInteger because it does not fit in a Long.
     *
     * ⚠️ `internal` so the unit test can reach it, and it is tested for a reason: a wrong id
     * here does not crash or log anything useful — it 404s, this route returns null, and the
     * app quietly falls back to the engine forever. A silent permanent regression is exactly
     * the kind a test has to catch, because using the app would never reveal it.
     */
    internal fun mediaId(shortcode: String): String? {
        if (shortcode.isEmpty()) return null
        var n = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (ch in shortcode) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            n = n.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        return n.toString()
    }

    // ── Cookies ────────────────────────────────────────────────────────────────────────────

    /**
     * Read the Instagram cookies back out of the Netscape file the in-app login wrote.
     *
     * Reusing that one file matters: the login flow, yt-dlp and this route then share a single
     * source of truth, so "Re-login" fixes all three at once and there is no second copy of the
     * session to go stale on its own.
     */
    private fun cookies(context: Context): Map<String, String> {
        if (!Prefs.cookiesEnabled(context) || !hasCookies(context)) return emptyMap()
        return try {
            val out = HashMap<String, String>()
            cookiesFile(context).forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val f = line.split("\t")
                if (f.size >= 7 && f[0].contains("instagram.com", ignoreCase = true)) {
                    out[f[5]] = f[6]
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "cookie read failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Do we hold a session this route can actually use?
     *
     * ⚠️ THE PRECONDITION THAT WAS MISSING, and it cost real time on every single download.
     * This endpoint is not public: with no `sessionid` Instagram answers with a 302 to its
     * login page — measured on a real phone. So without one, Route 0 cannot succeed; it can
     * only spend a round trip discovering that, and then hand over to the engine anyway.
     *
     * That is the whole of "the new version starts in 5s where the old took 3s" for anyone who
     * is not logged in — including a user who turned the login switch OFF because their session
     * had expired, which is exactly what happened here. Checking first costs nothing and skips
     * the entire route for them.
     */
    fun hasSession(context: Context): Boolean = cookies(context).containsKey("sessionid")

    // ── The API call ───────────────────────────────────────────────────────────────────────

    /**
     * Fetch one post. Returns null on ANY disappointment — that is the contract, and it is what
     * makes this route safe to put in front of the engine.
     *
     * ⚠️ Runs on the caller's thread and must never be called from the main one.
     */
    @Synchronized
    fun fetch(context: Context, link: String): Post? {
        val cacheKey = key(context, link)
        fromCache(cacheKey)?.let { return it }
        // ⚠️ NEGATIVE cache, and @Synchronized above it. Both exist for the same measured
        // problem: the preview and the download each called this at the same moment, so ONE
        // share produced TWO identical requests to Instagram (seen in logcat as the same
        // failure logged twice, from two different threads). That is a wasted round trip on
        // every download, double the chance of being rate-limited — and when the call fails it
        // was the whole of the "new version takes 5s where the old took 3s" complaint, paid
        // twice.
        // Synchronized makes the second caller wait for the first and then read its result;
        // the negative entry makes a failure as cheap to discover as a success.
        if (failedKey == cacheKey && System.currentTimeMillis() - failedAt < FAIL_CACHE_MS) return null
        val shortcode = shortcodeOf(link) ?: return null
        val id = mediaId(shortcode) ?: return null

        val jar = cookies(context)
        // The guard lives HERE, in the one place both callers pass through, rather than at each
        // call site. Without a session this route cannot succeed — see hasSession.
        if (!jar.containsKey("sessionid")) {
            Log.i(TAG, "route0 skipped — no Instagram session; engine's turn")
            return null
        }
        val conn = try {
            (URL("https://www.instagram.com/api/v1/media/$id/info/").openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            Log.w(TAG, "route0 connect failed: ${e.message}"); return null
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = API_CONNECT_MS
            conn.readTimeout = API_READ_MS
            // ⚠️ Redirects are NOT followed. Instagram answers an unauthenticated or blocked
            // API call by redirecting to its login page, and following that lands us on an HTML
            // document served with status 200 — which then failed as
            // "Value <!DOCTYPE ... cannot be converted to JSONObject", a parse error that says
            // nothing about the real problem. Seeing the 30x itself is the diagnosis.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", DESKTOP_UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("X-IG-App-ID", IG_APP_ID)
            conn.setRequestProperty("X-ASBD-ID", "129477")
            conn.setRequestProperty("X-IG-WWW-Claim", "0")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Referer", "https://www.instagram.com/")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
            jar["csrftoken"]?.let { conn.setRequestProperty("X-CSRFToken", it) }
            if (jar.isNotEmpty()) {
                conn.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            // ⚠️ NAMES ONLY — never the values. A cookie value here IS the login; it must never
            // reach a log, a bug report or a screen. The names alone answer the only question
            // that matters when Instagram sends us to its login page: did we actually have a
            // session to send?
            Log.i(TAG, "route0 cookies: ${jar.size} [${jar.keys.sorted().joinToString(",")}] sessionid=${jar.containsKey("sessionid")}")

            val code = conn.responseCode
            // A redirect to the login page is the one unambiguous "your session is dead" signal
            // this app has. yt-dlp's errors have to be string-matched and guessed at; this does
            // not. Record it so the home screen can say so instead of the user having to work
            // it out and switch the login off themselves.
            if (code in 300..399 && conn.getHeaderField("Location").orEmpty().contains("/accounts/login")) {
                Prefs.setSessionExpired(context, true)
            }
            if (code != 200) {
                // 30x = redirected to the login page (no usable session) · 401/403 = this post
                // needs a login we do not have · 429 = rate limited · 404 = wrong id. Every one
                // of them is the engine's turn, not a reason to shout at the user.
                Log.i(TAG, "route0 HTTP $code (${conn.getHeaderField("Location") ?: "no redirect"}) — engine's turn")
                markFailed(cacheKey)
                return null
            }
            val type = conn.contentType.orEmpty()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) { markFailed(cacheKey); return null }
            // ⚠️ A 200 carrying HTML is Instagram's app shell, not an answer — it means the
            // session was not accepted for the API. Name it, rather than letting JSONObject
            // throw a parse error that reads like a bug in our own code.
            if (!type.contains("json", true) && body.trimStart().startsWith("<")) {
                Log.i(TAG, "route0 got HTML not JSON (type=$type, ${body.length}b) — session not accepted; engine's turn")
                markFailed(cacheKey)
                return null
            }
            // The session just worked, so clear any stale "expired" flag.
            Prefs.setSessionExpired(context, false)
            parse(context, shortcode, JSONObject(body))?.also { putCache(cacheKey, it) }
                ?: run { markFailed(cacheKey); null }
        } catch (e: Exception) {
            Log.w(TAG, "route0 fetch failed: ${e.message}")
            markFailed(cacheKey)
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parse(context: Context, shortcode: String, root: JSONObject): Post? {
        val items = root.optJSONArray("items") ?: return null
        val first = items.optJSONObject(0) ?: return null

        val user = first.optJSONObject("user")?.optString("username").orEmpty().ifBlank { "instagram" }
        val caption = first.optJSONObject("caption")?.optString("text").orEmpty()
        val title = caption.lineSequence().firstOrNull()?.trim().orEmpty()
            .take(80).ifBlank { "$user's post" }

        // A carousel keeps its children in carousel_media; a single post is its own child.
        val nodes = ArrayList<JSONObject>()
        val carousel = first.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until minOf(carousel.length(), MAX_ITEMS)) {
                carousel.optJSONObject(i)?.let { nodes.add(it) }
            }
        } else {
            nodes.add(first)
        }

        val media = nodes.mapNotNull { node ->
            // Presence of video_versions is what decides it — NOT media_type. A carousel child
            // reports the type of the ORIGINAL upload, so a video posted as part of a mixed
            // carousel can still say "1" (image) while carrying a perfectly good video url.
            val videos = node.optJSONArray("video_versions")
            if (videos != null && videos.length() > 0) applyQuality(context, videos)
            else bestImage(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        }
        if (media.isEmpty()) return null

        val thumb = bestImage(first.optJSONObject("image_versions2")?.optJSONArray("candidates"))?.url
        return Post(shortcode, user, title, thumb, media)
    }

    /** Highest resolution at or below the user's quality cap, else the best there is. */
    private fun bestVideo(arr: JSONArray): Item? {
        val all = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url").orEmpty()
            if (u.isBlank()) null else Item(u, true, o.optInt("width"), o.optInt("height"))
        }
        return all.maxByOrNull { it.height.toLong() * 100000 + it.width }
    }

    private fun bestImage(arr: JSONArray?): Item? {
        if (arr == null) return null
        val all = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url").orEmpty()
            if (u.isBlank()) null else Item(u, false, o.optInt("width"), o.optInt("height"))
        }
        return all.maxByOrNull { it.width.toLong() * 100000 + it.height }
    }

    /** Cap a video item to the user's quality preference, when the list offers a choice. */
    private fun applyQuality(context: Context, arr: JSONArray): Item? {
        val q = Prefs.quality(context)
        val cap = q.toIntOrNull() ?: return bestVideo(arr)
        val all = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url").orEmpty()
            if (u.isBlank()) null else Item(u, true, o.optInt("width"), o.optInt("height"))
        }
        // Instagram is vertical: the LONG side is what "1080p" means to a user here.
        return all.filter { maxOf(it.width, it.height) <= cap }.maxByOrNull { it.height }
            ?: all.minByOrNull { maxOf(it.width, it.height) }
            ?: bestVideo(arr)
    }

    // ── The download ───────────────────────────────────────────────────────────────────────

    /**
     * Pull every item of a post into `work`, using the same filename shape the engine produces
     * so the caller's collect-and-save step does not need to know which route ran.
     *
     * Returns the number of files written — 0 means "use the engine", and the caller must treat
     * it exactly like a route that was never tried.
     *
     * ⚠️ ALL-OR-NOTHING on purpose. If item 4 of a 6-item carousel cannot be fetched, everything
     * written so far is deleted and we return 0. A half-saved carousel that reports success is
     * worse than a clean fall-through to the engine, because the user has no way to tell what
     * is missing.
     */
    fun downloadInto(
        context: Context,
        link: String,
        work: File,
        onBeat: () -> Unit,
        onProgress: (Int) -> Unit
    ): Int {
        val post = fetch(context, link) ?: return 0
        if (post.items.isEmpty()) return 0

        val safeUser = post.user.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(30)
        val written = ArrayList<File>()
        val total = post.items.size

        try {
            post.items.forEachIndexed { index, item ->
                val out = File(work, "${safeUser}_${post.shortcode}_${index + 1}_RiploxIG.${extOf(item)}")
                val ok = transfer(item.url, out, onBeat) { frac ->
                    onProgress((((index + frac) / total) * 100).toInt().coerceIn(0, 100))
                }
                if (!ok) throw Exception("item ${index + 1}/$total failed")
                written.add(out)
            }
            onProgress(100)
            Log.i(TAG, "route0 saved ${written.size} item(s) for ${post.shortcode}")
            return written.size
        } catch (e: Exception) {
            Log.w(TAG, "route0 download incomplete (${e.message}) — handing over to the engine")
            written.forEach { try { it.delete() } catch (_: Exception) {} }
            return 0
        }
    }

    /**
     * The real extension, taken from the URL rather than assumed.
     *
     * ⚠️ Instagram serves plenty of images as `.webp`, and a webp written to disk as `.jpg` is
     * how you get a file the gallery refuses to open — saved "successfully", unopenable. The
     * caller's save step also picks its MIME type from this extension, so guessing here is
     * wrong twice over.
     */
    private fun extOf(item: Item): String {
        val path = try { URL(item.url).path.orEmpty() } catch (e: Exception) { "" }
        val e = path.substringAfterLast('.', "").lowercase()
        return when {
            e in setOf("mp4", "mov", "webm", "m4v") -> e
            e in setOf("jpg", "jpeg", "png", "webp", "heic") -> e
            item.isVideo -> "mp4"
            else -> "jpg"
        }
    }

    /**
     * One file, with resume.
     *
     * ⚠️ Three attempts, and a retry RESUMES from what is already on disk via a Range header
     * rather than starting over. Losing signal at 90% of a large reel and being sent back to
     * zero is the failure users describe as "it never finishes" — on a phone that is a normal
     * event, not an exceptional one.
     */
    private fun transfer(
        url: String,
        out: File,
        onBeat: () -> Unit,
        onFraction: (Double) -> Unit
    ): Boolean {
        repeat(3) { attempt ->
            val have = if (out.exists()) out.length() else 0L
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = MEDIA_CONNECT_MS
                    readTimeout = MEDIA_READ_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", DESKTOP_UA)
                    setRequestProperty("Referer", "https://www.instagram.com/")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                // 206 = the resume was honoured. 200 on a resume attempt means the server sent
                // the whole file again, so what is on disk must go or the file ends up with the
                // first chunk written twice.
                val resuming = code == 206 && have > 0
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                if (!resuming && have > 0) out.delete()

                val declared = conn.getHeaderFieldLong("Content-Length", -1L)
                val expected = if (declared > 0) declared + (if (resuming) have else 0L) else -1L

                var done = if (resuming) have else 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(out, resuming).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var lastBeat = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            done += n
                            // A beat every 64 KB would spam the watchdog; every ~512 KB keeps it
                            // awake without the noise.
                            if (done - lastBeat > 512 * 1024) {
                                lastBeat = done
                                onBeat()
                                if (expected > 0) onFraction((done.toDouble() / expected).coerceIn(0.0, 1.0))
                            }
                        }
                        fos.flush()
                    }
                }
                // A truncated body that ends without an exception is the quiet failure that
                // produces an unplayable file and a "Saved!" message. Check the length.
                if (expected > 0 && out.length() < expected) throw Exception("short read ${out.length()}/$expected")
                if (out.length() <= 0L) throw Exception("empty file")
                onFraction(1.0)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "route0 transfer attempt ${attempt + 1} failed: ${e.message}")
                onBeat()
                if (attempt == 2) return false
                try { Thread.sleep(1200L * (attempt + 1)) } catch (_: InterruptedException) { return false }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return false
    }
}
