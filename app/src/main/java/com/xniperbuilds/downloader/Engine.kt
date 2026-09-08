package com.xniperbuilds.downloader

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The yt-dlp engine: one place that updates it, and one place that remembers what happened.
 *
 * WHY THIS MATTERS MORE ON INSTAGRAM THAN ANYWHERE ELSE: unlike the TikTok app, Riplox IG has
 * no hand-written extractor to fall back on — **every** download goes through yt-dlp. A stale
 * engine is therefore not a degraded experience here, it is a dead app: the bundled 0.18.1
 * build answers "No video formats found" on a fresh install, which is exactly what the
 * original "download start nahi hoti" complaint turned out to be.
 *
 * The four holes this closes, all of them real:
 *  1. There was no manual update anywhere in the app. Not for a non-technical user, and not
 *     for a technical one either — the only updates were invisible ones.
 *  2. The daily check ran in MainActivity only, so a user who always downloads from the
 *     Instagram share sheet never opened MainActivity and never updated the engine at all.
 *  3. The result was swallowed by `catch {}`. An update that failed every single time looked
 *     exactly like one that succeeded.
 *  4. The worker's self-heal only fired on a fixed list of error strings, so a wording change
 *     on yt-dlp's side silently disabled it.
 */
object Engine {

    /** The outcome of an update attempt, in words a non-technical user can act on. */
    enum class Outcome { UPDATED, ALREADY_LATEST, FAILED }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** The engine version string, or null when it cannot be read. */
    fun version(context: Context): String? = try {
        YoutubeDL.getInstance().version(context)
    } catch (e: Exception) {
        Log.w("RiploxIG", "engine version unavailable: ${e.message}")
        null
    }

    /**
     * Update on the NIGHTLY channel and RECORD the result.
     *
     * NIGHTLY on purpose: Instagram keeps moving, fixes land in nightly first, and the stable
     * channel ran weeks behind — which showed up as "No video formats" on a fresh install.
     */
    suspend fun update(context: Context): Outcome = withContext(Dispatchers.IO) {
        val outcome = try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            // Matched on the NAME rather than the enum constant: the library has renamed these
            // before, and an update that works must not be reported as a failure because a
            // constant moved.
            if (status?.name?.contains("ALREADY", ignoreCase = true) == true) {
                Outcome.ALREADY_LATEST
            } else {
                Outcome.UPDATED
            }
        } catch (e: Exception) {
            Log.w("RiploxIG", "engine update failed: ${e.message}")
            Outcome.FAILED
        }
        Prefs.setEngineResult(context, outcome.name, version(context).orEmpty())
        if (outcome != Outcome.FAILED) Prefs.setLastUpdateDay(context, today())
        Log.i("RiploxIG", "engine update → $outcome (${Prefs.engineVersion(context)})")
        outcome
    }

    /**
     * The once-a-day check, called from EVERY entry point — the home screen and the share
     * sheet both. Never runs while a download is active (an engine swap mid-download is how
     * you break a running job).
     *
     * The day marker is only written on success, so a failed attempt is retried at the next
     * opportunity rather than being treated as "done for today".
     */
    suspend fun dailyIfDue(context: Context) {
        if (Prefs.lastUpdateDay(context) == today()) return
        val busy = withContext(Dispatchers.IO) { DownloadQueue.hasActive(context) }
        if (busy) return
        update(context)
    }

    /**
     * Is this failure the kind a fresh engine could fix?
     *
     * Deliberately broader than the old fixed string list: anything about extraction, formats
     * or an out-of-date engine counts. A missed self-heal costs a user a failed download; an
     * unnecessary one costs a few seconds. The asymmetry decides it.
     *
     * ⚠️ "no video formats" is on this list even though it is ALSO what a photo-only post
     * returns. That is deliberate and safe: the worker only consults this on the first attempt,
     * and its own `definitelyPhoto` check stops a photo post from retrying after that.
     */
    fun looksStale(raw: String?): Boolean {
        val s = raw?.lowercase() ?: return false
        return listOf(
            "no video formats", "unable to extract", "not available", "requested format",
            "no formats", "confirm you are on the latest", "unsupported url", "extractor",
            "failed to parse", "unable to download webpage", "out of date", "update"
        ).any { it in s }
    }

    /** A line the About sheet can show as-is. */
    fun statusLine(context: Context): String {
        val v = Prefs.engineVersion(context).ifBlank { version(context).orEmpty() }
        val day = Prefs.lastUpdateDay(context)
        val when_ = if (day.length == 8) "${day.substring(6, 8)}/${day.substring(4, 6)}/${day.substring(0, 4)}" else "never"
        val outcome = when (Prefs.engineOutcome(context)) {
            Outcome.UPDATED.name -> "updated"
            Outcome.ALREADY_LATEST.name -> "already latest"
            Outcome.FAILED.name -> "last check failed"
            else -> "not checked yet"
        }
        return if (v.isBlank()) "Engine: unknown — $outcome" else "Engine $v — $outcome, $when_"
    }
}
