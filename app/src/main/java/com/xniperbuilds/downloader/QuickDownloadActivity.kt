package com.xniperbuilds.downloader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * The "Riplox IG" share tile — ZERO popups, zero friction (share from the Instagram app and
 * the download just starts).
 *
 * AIRLOCK pattern (double-door) v2:
 *  Door 1: this invisible activity opens → the app is in the FOREGROUND, so the system
 *          cannot block the download.
 *  Chamber: the link goes onto the queue → we wait only long enough for the worker to take
 *          its own FOREGROUND-SERVICE lock.
 *  Door 2: the moment that lock is CONFIRMED, the activity closes. With no network it
 *          closes after 10s with a "queued" message instead.
 */
class QuickDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        } else null

        if (link.isNullOrBlank() || !link.startsWith("http")) {
            Toast.makeText(this, "No link found in the shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // IG-LOCK — this app only downloads Instagram links
        if (!isInstagramUrl(link)) {
            Toast.makeText(this, "Riplox IG downloads Instagram links only (reel, post, story)", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val workId = DownloadQueue.enqueue(this, link, Prefs.audioMode(this))
        DownloadQueue.awaitStart(this, workId) { started ->
            // If background setup is incomplete an XOS-style phone can freeze the download
            // part-way — but the share flow must stay friction-free, so this is only a hint toast.
            val setupOk = BgGuard.batteryExempt(this) && Prefs.bgSetupDone(this)
            val msg = when {
                started && setupOk -> "⬇ Downloading — see notification. Not starting? Open Riplox IG once."
                started -> "⬇ Started — see notification. If it doesn't start, open Riplox IG once → “Fix background downloads”."
                else -> "⬇ Queued — starts when network allows. If nothing happens, open Riplox IG once."
            }
            Toast.makeText(this, msg, if (started && !setupOk) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
