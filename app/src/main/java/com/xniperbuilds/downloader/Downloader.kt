package com.xniperbuilds.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Share ke text me se pehla http(s) link nikaalo. */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val found = Regex("https?://\\S+").find(text)?.value ?: text.trim()
    // strip punctuation stuck to the end of a link in shared text
    return found.trimEnd('.', ',', ')', ']', '!', '?', ';', '"', '\'')
}

/** IG-LOCK — Riplox IG sirf Instagram links leta hai (Play policy + product scope dono).
 * Sab variants: www./m. instagram.com, instagr.am short links. */
fun isInstagramUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val host = Uri.parse(url.trim()).host?.lowercase() ?: return false
        host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "instagr.am" || host.endsWith(".instagr.am") ||
            host == "ig.me" || host.endsWith(".ig.me")
    } catch (e: Exception) {
        false
    }
}

/** Preview info (thumbnail + title) — notification/card ke liye. */
data class Preview(val title: String, val uploader: String, val thumbnail: String?, val duration: Long)

fun getPreview(context: Context, link: String): Preview {
    // Route 0 first — and it costs nothing, because the download itself is about to want the
    // same JSON and the extractor caches it. Before this, the preview spawned a SECOND yt-dlp
    // process alongside the download purely to fetch a title and a thumbnail, on a post the
    // app was already fetching.
    try {
        InstagramExtractor.fetch(context, link)?.let {
            return Preview(it.title, it.user, it.thumb, 0L)
        }
    } catch (e: Exception) {
        Log.w("RiploxIG", "route0 preview failed — using the engine: ${e.message}")
    }
    val req = YoutubeDLRequest(link)
    req.addOption("--no-playlist")
    req.addOption("--no-warnings")
    req.addOption("--socket-timeout", "30")
    req.applyCommon(context)
    val info = YoutubeDL.getInstance().getInfo(req)
    val dur = try { info.duration.toLong() } catch (e: Exception) { 0L }
    return Preview(info.title ?: "—", info.uploader ?: "—", info.thumbnail, dur)
}

/** Quality choice → yt-dlp format string (for the fallback path).
 * ⚠️ TikTok is VERTICAL (heights 1090/1364/2044), so `height<=1080` never matches — filter
 * on WIDTH instead, with an unfiltered fallback at the end. On the native path the quality
 * comes from `TtInfo.pickVideo()`. */
fun formatFor(quality: String): String = when (quality) {
    "1080" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
    "720" -> "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
    "480" -> "bestvideo[height<=480]+bestaudio/best[height<=480]/best"
    else -> "best"
}

// ============================================================================
// COOKIES / CONNECT ACCOUNT — Instagram increasingly blocks downloads for guests
// (private posts, stories, and sometimes even public reels). The user signs in through an
// in-app WebView (CookieLoginActivity); those cookies are written to a Netscape cookies.txt
// and travel with EVERY yt-dlp request (--cookies).
// (The parent Riplox app's battle-tested cookie code, focused on Instagram.)
// ============================================================================
/** Fixed path of the saved cookies.txt (app-private). */
fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

/** Cookies save hain ya nahi (file mojood + khali nahi). */
fun hasCookies(context: Context): Boolean {
    val f = cookiesFile(context)
    return f.exists() && f.length() > 0
}

/** SAF se chuni hui cookies.txt import (power-user fallback) — Netscape validate. */
fun importCookies(context: Context, uri: Uri): Boolean {
    return try {
        val tmp = File(context.cacheDir, "cookies_import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        val valid = tmp.useLines { lines ->
            lines.take(500).any { l ->
                !l.startsWith("#") && l.isNotBlank() && l.split('\t').size >= 7
            }
        }
        if (!valid) { tmp.delete(); return false }
        tmp.copyTo(cookiesFile(context), overwrite = true)
        tmp.delete()
        hasCookies(context)
    } catch (e: Exception) {
        Log.e("RiploxIG", "cookie import failed", e)
        false
    }
}

/** Saari cookies hata do (disconnect). */
fun clearCookies(context: Context): Boolean {
    val f = cookiesFile(context)
    return if (f.exists()) f.delete() else true
}

/** Ek site ke liye: (cookie-domain -> jin URLs se cookies uthani hain). IG-app = Instagram. */
fun cookieGroupsFor(site: String, customUrl: String? = null): List<Pair<String, List<String>>> = when (site) {
    "instagram" -> listOf(".instagram.com" to listOf("https://www.instagram.com", "https://instagram.com"))
    else -> {
        val host = try { Uri.parse(customUrl).host ?: "" } catch (e: Exception) { "" }
        val root = host.removePrefix("www.").removePrefix("m.")
        val domain = if (root.isNotEmpty()) ".$root" else host
        listOf(domain to listOf(customUrl ?: "https://$host"))
    }
}

/** The site's login/start URL (the first page the WebView loads). Instagram is opened with a
 * DESKTOP UA: that login form is the simpler one and it sidesteps the mobile page's
 * "open in app" redirect, which renders blank inside a WebView. */
fun cookieSiteUrl(site: String, customUrl: String? = null): String = when (site) {
    "instagram" -> "https://www.instagram.com/accounts/login/"
    else -> customUrl ?: "https://www.instagram.com/accounts/login/"
}

/** Make the WebView use a DESKTOP Chrome UA (this is what makes the Instagram login reliable
 * — the mobile page comes up blank inside a WebView). */
fun useDesktopUa(site: String): Boolean = true

// ---------- REAL login detection (guest cookies ≠ connected) ----------
// Instagram hands out GUEST cookies the moment the WebView opens, and showing "connected ✓"
// off those would be WRONG. Connected means a real LOGIN/session cookie is present
// (sessionid/ds_user_id).
private val LOGIN_COOKIE_NAMES = mapOf(
    "Instagram" to listOf("sessionid", "ds_user_id")
)

private fun siteNameOfDomain(d: String): String = when {
    "instagram" in d -> "Instagram"
    else -> d.removePrefix("www.")
}

/** Sirf wo sites jinki REAL login-cookie file me hai (✓ badge isi se). */
fun connectedSites(context: Context): List<String> {
    if (!hasCookies(context)) return emptyList()
    return try {
        val names = LinkedHashSet<String>()
        cookiesFile(context).readLines().forEach { l ->
            if (l.isBlank() || l.startsWith("#")) return@forEach
            val parts = l.split('\t')
            if (parts.size < 7) return@forEach
            val d = parts[0].removePrefix(".").lowercase()
            val cookieName = parts[5]
            val value = parts[6]
            val site = siteNameOfDomain(d)
            val loginNames = LOGIN_COOKIE_NAMES[site]
            if (loginNames == null) {
                if (d.isNotBlank() && value.isNotBlank()) names.add(site)
            } else if (cookieName in loginNames && value.isNotBlank() && value != "\"\"") {
                names.add(site)
            }
        }
        names.toList()
    } catch (e: Exception) {
        emptyList()
    }
}

/** Is Instagram connected? (A small helper for the badge/guard.) */
fun instagramConnected(context: Context): Boolean = connectedSites(context).contains("Instagram")

/**
 * After a login in the in-app WebView (CookieLoginActivity), write its cookies out to a
 * Netscape cookies.txt (yt-dlp reads that via --cookies). Returns how many were saved.
 */
fun saveCookiesFromWebView(context: Context, groups: List<Pair<String, List<String>>>): Int {
    val cm = CookieManager.getInstance()
    try { cm.flush() } catch (_: Exception) {}
    val siteDomains = groups.map { it.first }.toSet()
    val kept = if (hasCookies(context)) {
        cookiesFile(context).readLines().filter { line ->
            line.isNotBlank() && !line.startsWith("#") &&
                siteDomains.none { d -> line.startsWith("$d\t") }
        }
    } else emptyList()

    val seen = HashSet<String>()
    val fresh = ArrayList<String>()
    val expiry = (System.currentTimeMillis() / 1000) + 10L * 365 * 24 * 3600 // ~10 saal
    for ((domain, urls) in groups) {
        for (url in urls) {
            val raw = cm.getCookie(url) ?: continue
            for (part in raw.split(";")) {
                val kv = part.trim()
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val name = kv.substring(0, eq).trim()
                val value = kv.substring(eq + 1).trim()
                if (!seen.add("$domain\t$name")) continue
                fresh.add("$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
    }
    if (fresh.isEmpty() && kept.isEmpty()) return 0
    val sb = StringBuilder("# Netscape HTTP Cookie File\n# Riplox IG\n")
    kept.forEach { sb.append(it).append("\n") }
    fresh.forEach { sb.append(it).append("\n") }
    cookiesFile(context).writeText(sb.toString())
    // A fresh login clears the "expired" warning — otherwise the home screen would keep
    // telling the user to re-login after they just did.
    if (fresh.isNotEmpty()) Prefs.setSessionExpired(context, false)
    return fresh.size
}

/** Options applied to every request — just cookies + multi-connection.
 * ⚠️ yt-dlp's extractors use their own site-specific headers/UA, so NEVER override
 * --user-agent/--referer (hard-won lesson: it broke TikTok outright, and the same risk
 * applies to Instagram). */
private fun YoutubeDLRequest.applyCommon(context: Context, useCookies: Boolean = true) {
    if (useCookies && Prefs.cookiesEnabled(context) && hasCookies(context)) {
        addOption("--cookies", cookiesFile(context).absolutePath)
    }
    addOption("--concurrent-fragments", "8")
}

/**
 * Does this failure look like it came from the SESSION? (i.e. the login/cookies were rejected)
 *
 * Instagram sometimes answers a logged-in request with an EMPTY body (a stale session, a
 * soft-limited account, or a glitch on their side) and the extractor throws "Failed to parse
 * JSON" — while the very same post extracts fine WITHOUT a login. In that case we retry once
 * without cookies; otherwise EVERY download by that user keeps dying and they have no way to
 * turn the cookies off.
 */
fun looksLikeSessionFailure(raw: String?): Boolean {
    val m = (raw ?: "").lowercase()
    return listOf(
        "failed to parse json", "jsondecodeerror", "empty media response",
        "login required", "not logged in", "login_required",
        "checkpoint", "challenge_required", "csrf",
        "requested content is not available", "please wait a few minutes",
        "http error 401", "http error 429"
    ).any { it in m }
}

/** yt-dlp ke lambe raw ERROR ko chhota, kaam-ka message banao. */
fun friendlyError(raw: String?): String {
    val m = (raw ?: "").lowercase()
    return when {
        // ⚠️ This used to read "photo support is coming soon". Photos ARE supported now
        // (InstagramExtractor), so reaching this message means the direct route could not open
        // the post either — which is almost always a login/visibility problem, not the media
        // type. Saying "photos aren't supported" here would send the user to fix the one thing
        // that is not broken.
        "no video formats" in m || "no video could be found" in m ->
            "Couldn't open this post. If it's private or age-restricted, connect an Instagram account that can see it (Riplox IG → Connect Instagram) and retry."
        "failed to parse json" in m || "jsondecodeerror" in m ->
            "Instagram didn't return this post's data — it usually means it's rate-limiting right now. Wait a minute and retry."
        "instagram sent an empty media response" in m || "login required" in m ||
            "requested content is not available" in m ->
            "Instagram needs a connected account for this post.\nOpen Riplox IG → Connect Instagram → retry."
        ("private" in m && ("login" in m || "available" in m)) || "this account is private" in m ->
            "Private post — Connect an Instagram account that can see it (in the app)."
        "http error 403" in m || "status code 0" in m ->
            "Instagram blocked this request. Connect Instagram in the app, or retry in a bit."
        "unsupported url" in m -> "This isn't a downloadable Instagram post/reel/story link."
        "file not found" in m ->
            "Couldn't find downloadable media at that link — retry, or open the post in the Instagram app and share again."
        "unable to extract" in m || "not available" in m ->
            "Media not available — it may be deleted, private, or region-locked. Retry or check the link."
        "no space left" in m -> "Phone storage is full."
        ("timed out" in m || "unable to connect" in m || "network is unreachable" in m) ->
            "Network problem — check internet and retry."
        else -> (raw ?: "Unknown error").take(220)
    }
}

/**
 * Instagram-aware friendly error — when a download fails without a connected account, tell
 * the user the actual reason and the fix. Instagram blocks a lot of guest downloads, so this
 * points them at "Connect Instagram".
 */
fun smartError(context: Context, link: String, raw: String?): String {
    val connected = try { connectedSites(context) } catch (e: Exception) { emptyList() }
    return if (isInstagramUrl(link) && !connected.contains("Instagram")) {
        "Instagram blocks downloads without login.\nOpen Riplox IG → Connect Instagram (top of home) → retry. Your password never touches this app."
    } else {
        friendlyError(raw)
    }
}

/** Temp/cache files delete karo — freed bytes return. */
fun clearTempFiles(context: Context): Long {
    val base = context.getExternalFilesDir("temp") ?: return 0L
    var freed = 0L
    base.listFiles()?.forEach { f ->
        freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        f.deleteRecursively()
    }
    return freed
}

/** The SAFE variant of the startup cleanup — only dl_* folders older than 24h (the name
 * carries a timestamp).
 * ⚠️ A full clearTempFiles raced at startup: an ENQUEUED job went RUNNING in the same second
 * the app opened, and the cleanup deleted its brand-new temp folder → the download appeared
 * to run but its file was gone → File not found / stuck. The age check makes that race
 * impossible. */
fun clearStaleTempFiles(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L): Long {
    val base = context.getExternalFilesDir("temp") ?: return 0L
    val cutoff = System.currentTimeMillis() - olderThanMs
    var freed = 0L
    base.listFiles()?.forEach { f ->
        val ts = f.name.removePrefix("dl_").substringBefore('_').toLongOrNull()
        val stale = if (ts != null) ts < cutoff else f.lastModified() < cutoff
        if (stale) {
            freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
    }
    return freed
}

/**
 * The full yt-dlp download + gallery save, with live progress. DownloadWorker runs this
 * inside a foreground WorkManager job (proof against aggressive OEM task killers).
 *
 * Instagram support:
 *  - Reel / video post / IGTV  → video (Movies/RiploxIG)
 *  - Photo post                → image (Pictures/RiploxIG)
 *  - CAROUSEL (multi)          → a yt-dlp playlist → EVERY item saved (video + photos)
 *  - Story                     → that story's media
 *
 * ⚠️ BUG-HARDENING (the fix for "the download never starts/completes"):
 * --embed-thumbnail / --embed-metadata were REMOVED for video — that ffmpeg/AtomicParsley
 * post-processing step hung or failed on Android, leaving a download at 100% and stuck on
 * "finishing". What remains is a plain download plus a merge where one is needed, which
 * completes reliably.
 * ⚠️ NEVER --user-agent/--referer (the extractor uses its own headers).
 */
fun runDownload(
    context: Context,
    link: String,
    audioOnly: Boolean,
    processId: String? = null,   // for cancelling — YoutubeDL.destroyProcessById(processId)
    onBeat: () -> Unit = {},     // fires on every yt-dlp output — the stall-watchdog signal
    onSave: (Int) -> Unit = {},  // gallery-save copy progress (0–100) — "Finishing" phase visible + beats
    onProgress: (Int) -> Unit
): String {
    Log.i("RiploxIG", "runDownload audio=$audioOnly ${link.take(50)}")
    val base = context.getExternalFilesDir("temp") ?: context.filesDir
    val work = File(base, "dl_${System.currentTimeMillis()}_${(0..9999).random()}")
    work.mkdirs()
    try {
        // The request is rebuilt FRESH on every attempt (useCookies = send the login or not).
        fun buildRequest(useCookies: Boolean): YoutubeDLRequest {
        val req = YoutubeDLRequest(link)
        req.addOption("-o", "${work.absolutePath}/%(uploader)s_%(id)s_%(autonumber)s_RiploxIG.%(ext)s")
        if (audioOnly) {
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
            // NOTE: --embed-thumbnail was REMOVED (AtomicParsley/ffmpeg hang risk) — the MP3
            // is still produced, just without cover art. Reliability beats cover art.
        } else {
            // Video quality uses a SOFT selector: the trailing "/b" (best, no filter at all)
            // is what picks up PHOTO posts and carousel images. The old hard
            // -f "bestvideo…" failed images with "No video formats found". A nightly engine
            // exposes images as a selectable format, so they get saved here too.
            val q = Prefs.quality(context)
            if (q == "best") {
                req.addOption("-f", "bv*+ba/b")
            } else {
                req.addOption("-f", "bv*[height<=$q]+ba/b[height<=$q]/b")
            }
            req.addOption("--merge-output-format", "mp4")
            // NOTE: --embed-thumbnail / --embed-metadata were REMOVED (bug-hardening, see above).
        }
        // A carousel or story is a yt-dlp playlist, so NO --no-playlist (we want every item).
        // Safety cap: never pull more than 200 items if someone pastes a profile link.
        req.addOption("--yes-playlist")
        req.addOption("--playlist-end", "200")
        req.addOption("--no-warnings")
        req.addOption("--restrict-filenames")
        req.addOption("--socket-timeout", "30")
        req.applyCommon(context, useCookies)
            return req
        }

        var maxP = 0
        fun execute(useCookies: Boolean) {
            YoutubeDL.getInstance().execute(buildRequest(useCookies), processId) { progress, _, _ ->
                onBeat() // the process is alive — reset the watchdog timer
                val p = progress.toInt()
                if (p in 0..100 && p > maxP) {
                    maxP = p
                    onProgress(maxP)
                }
            }
        }

        // ── ROUTE 0 — Instagram's own API, direct (InstagramExtractor) ─────────────────────
        // Tried first because it is both faster and independent of the engine: one HTTPS call
        // instead of a Python process, and it keeps working on the day yt-dlp's Instagram
        // extractor breaks. It is also the ONLY route that can save photos at all.
        //
        // Its contract is what makes it safe to put in front: it returns 0 on ANY disappointment
        // and leaves `work` empty, so the engine below then runs exactly as it always has.
        // Audio is not its job (MP3 needs ffmpeg), so MP3 mode skips it entirely.
        // (No session? `fetch` returns null immediately without a request — see hasSession.)
        val route0 = if (audioOnly) 0 else try {
            InstagramExtractor.downloadInto(context, link, work, onBeat) { p ->
                if (p > maxP) { maxP = p; onProgress(maxP) }
            }
        } catch (e: Exception) {
            Log.w("RiploxIG", "route0 threw — falling back to the engine", e)
            0
        }
        if (route0 == 0) {
            // Route 0 may have left partial files behind before giving up; clear them or they
            // get collected below as if they were real downloads.
            work.listFiles()?.forEach { it.deleteRecursively() }
            maxP = 0

            // ── ROUTE 1 — the engine ───────────────────────────────────────────────────────
            // Try with the login first (that is what makes private posts and stories work). If
            // the session is rejected, retry ONCE without it — public reels, posts and carousels
            // still come through that way.
            val hadCookies = Prefs.cookiesEnabled(context) && hasCookies(context)
            try {
                execute(hadCookies)
            } catch (e: Exception) {
                if (!hadCookies || !looksLikeSessionFailure(e.message)) throw e
                Log.w("RiploxIG", "session/cookie failure — retrying WITHOUT login", e)
                // Drop the partial files, or the old .part ones collide with the new run.
                work.listFiles()?.forEach { it.deleteRecursively() }
                maxP = 0
                onBeat()
                execute(false)
            }
        }

        // Collect ALL downloaded files (a carousel gives several) — but not partial/temp ones
        val all = work.walkTopDown().filter { it.isFile }.filterNot {
            val n = it.name.lowercase()
            n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".tmp") || n.endsWith(".json")
        }.toList()
        val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts", "3gp", "avi")
        val audioExts = setOf("mp3", "m4a", "opus", "ogg", "wav", "flac", "aac", "weba")
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic")

        if (audioOnly) {
            val audio = all.filter { it.extension.lowercase() in audioExts || it.extension.lowercase() in videoExts }
            if (audio.isEmpty()) throw Exception("File not found")
            var saved = 0
            var firstDisplay = ""
            for (file in audio) {
                val location = saveWithBeats(context, file, true, onBeat, onSave)
                History.add(context, link, file.name, "IG", location, true)
                if (saved == 0) firstDisplay = "Music/RiploxIG/${file.name}"
                saved++
            }
            return if (saved == 1) firstDisplay else "$saved files → Music/RiploxIG/"
        }

        // Default (video + photos): a CAROUSEL can hold both — save BOTH.
        val videos = all.filter { it.extension.lowercase() in videoExts }
        val images = all.filter { it.extension.lowercase() in imageExts }
        if (videos.isEmpty() && images.isEmpty()) throw Exception("File not found")

        var savedVid = 0
        var savedImg = 0
        var firstDisplay = ""
        for (file in videos) {
            val location = saveWithBeats(context, file, false, onBeat, onSave)
            History.add(context, link, file.name, "IG", location, false)
            if (firstDisplay.isEmpty()) firstDisplay = "Movies/RiploxIG/${file.name}"
            savedVid++
        }
        for (img in images) {
            try {
                onBeat()
                val loc = saveImageToPictures(context, img)
                History.add(context, link, img.name, "IG", loc, false)
                if (firstDisplay.isEmpty()) firstDisplay = "Pictures/RiploxIG/${img.name}"
                savedImg++
            } catch (e: Exception) {
                Log.e("RiploxIG", "image save fail", e)
            }
        }
        val total = savedVid + savedImg
        if (total == 0) throw Exception("File not found")
        return when {
            total == 1 -> firstDisplay
            savedVid > 0 && savedImg > 0 -> "$total items → RiploxIG (Movies + Pictures)"
            savedImg > 0 -> "$savedImg photos → Pictures/RiploxIG/"
            else -> "$savedVid videos → Movies/RiploxIG/"
        }
    } finally {
        work.deleteRecursively()
    }
}

/** Gallery/Music save + save-phase "beats" (bade file ki copy watchdog/notif ke liye zinda dikhe). */
private fun saveWithBeats(
    context: Context,
    file: File,
    audioOnly: Boolean,
    onBeat: () -> Unit,
    onSave: (Int) -> Unit
): String {
    val total = file.length().coerceAtLeast(1)
    var lastSaveP = -1
    val onCopy: (Long) -> Unit = { copied ->
        onBeat()
        val sp = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (sp != lastSaveP) {
            lastSaveP = sp
            onSave(sp)
        }
    }
    return savePublic(context, file, audioOnly, onCopy)
}
