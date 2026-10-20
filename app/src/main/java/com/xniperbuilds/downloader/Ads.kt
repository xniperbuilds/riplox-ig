package com.xniperbuilds.downloader

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private const val TAG = "RiploxIG.Ads"

/**
 * AdMob — a bottom banner (home + share sheet), a 300×250 box (tall share sheet), an
 * interstitial at two logical breaks, and an app-open ad (AppOpen.kt).
 *
 * The IDs now live in **build.gradle.kts** (BuildConfig + a manifest placeholder):
 *   release build → the real ids
 *   qa build      → Google's TEST ids (assembleQa, installs as `.qa` beside the Play copy)
 * ⚠️ Always test on your own phone via **assembleQa**. Clicking your own real ads counts as
 *    AdMob invalid traffic and gets accounts banned.
 */
object Ads {
    const val ENABLED = true

    // Supplied by the build type — no more swapping ids by hand
    val BANNER_ID: String = BuildConfig.AD_BANNER_ID
    val INTERSTITIAL_ID: String = BuildConfig.AD_INTERSTITIAL_ID

    /**
     * Rewarded unit for the ad-free window and accent unlocks.
     *
     * ⚠️ Blank until the unit exists in the AdMob console. Everything rewarded checks
     * [rewardedConfigured] first and simply does not offer the option — the app must never
     * show a button that cannot deliver its reward, because AdMob requires publishers to
     * "deliver the promised reward(s) to the user upon completion of the required action(s)".
     */
    val REWARDED_ID: String = BuildConfig.AD_REWARDED_ID

    val rewardedConfigured: Boolean get() = REWARDED_ID.isNotBlank()

    /**
     * The 300×250 box at the top of the share sheet (tall screens). Its own unit so its
     * earnings show apart from the strip; until that unit exists it borrows the banner unit,
     * which serves any banner size.
     */
    val BOX_ID: String = BuildConfig.AD_BOX_ID.ifBlank { BuildConfig.AD_BANNER_ID }

    /** App open unit (AppOpen.kt). Blank = the whole app-open flow is off. */
    val APP_OPEN_ID: String = BuildConfig.AD_APP_OPEN_ID

    val appOpenConfigured: Boolean get() = APP_OPEN_ID.isNotBlank()

    /**
     * Set when the user taps ANY of our ads, cleared by the next app open.
     *
     * Tapping an ad sends the user out of the app; coming back is not "opening the app", and an
     * app-open ad at that moment would be an ad straight after an ad.
     */
    @Volatile
    private var clickedAway = false

    fun noteAdClick() {
        clickedAway = true
    }

    /** Read and clear [clickedAway] — only an actual open event should consume it. */
    fun takeClickedAway(): Boolean = clickedAway.also { clickedAway = false }

    /**
     * Is the user inside their earned ad-free window?
     *
     * The window silences the banner AND the interstitial — a promise of "ad-free" that still
     * shows a full-screen ad costs a rating, not just an impression.
     */
    fun suppressed(ctx: Context): Boolean =
        Streak.adFree(Prefs.adFreeUntil(ctx), System.currentTimeMillis())

    // ---- Rewarded: the ad-free window and accent unlocks (see Streak.kt) ----

    private var rewarded: RewardedAd? = null
    private var rewardedLoading = false

    /**
     * Readiness is Compose STATE, not a plain field.
     *
     * ⚠️ It has to be. The rewarded ad arrives asynchronously, long after the card that offers
     * it has been composed. With a plain `rewarded != null` check nothing ever invalidated that
     * composition, so the button stayed hidden even once an ad was in hand — measured on device,
     * 8 Sep 2026. Reading a MutableState subscribes the caller, so the button appears the moment
     * the ad lands.
     */
    private val rewardedReadyState = mutableStateOf(false)

    /** Is a rewarded ad in hand right now? Drives whether the button is offered at all. */
    fun rewardedReady(): Boolean = rewardedReadyState.value

    /**
     * Fetch a rewarded ad, if one is not already held or in flight. Unlike the interstitial
     * this IS loaded ahead of time: a rewarded ad is opt-in, so one we fetch and never show
     * costs nothing but a request, and a user who taps must not wait on the network.
     */
    fun loadRewarded(ctx: Context) {
        if (!ENABLED || !rewardedConfigured) return
        if (!Consent.canRequestAds(ctx)) return
        if (rewarded != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(
            ctx, REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    rewardedReadyState.value = true
                    rewardedLoading = false
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    rewarded = null
                    rewardedReadyState.value = false
                    rewardedLoading = false
                }
            }
        )
    }

    /**
     * Show the rewarded ad. [onReward] runs ONLY if the user actually earned it — the grant
     * hangs off `OnUserEarnedRewardListener`, never off dismissal, so closing early earns
     * nothing. [onFinished] always runs so the caller can refresh its UI.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onFinished: () -> Unit = {}) {
        val ad = rewarded
        if (ad == null) { onFinished(); return }
        rewarded = null
        rewardedReadyState.value = false
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (earned) onReward()
                onFinished()
                loadRewarded(activity)
            }

            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                onFinished()
                loadRewarded(activity)
            }
        }
        ad.show(activity) { earned = true }
    }


    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var completedCount = 0

    /**
     * Elapsed-realtime of the last full-screen ad of ANY kind — interstitial or app open.
     * Shared on purpose: the 60s floor has to hold between the two formats, not just within one.
     */
    var lastFullScreenAt = AdGate.NEVER
        private set

    /** When the cached [interstitial] was loaded, so a stale one can be dropped before showing. */
    private var loadedAt = AdGate.NEVER

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    fun markFullScreenShown() {
        lastFullScreenAt = now()
    }

    /**
     * Fetch an interstitial **only if one is actually going to be shown next.**
     *
     * ⚠️ Measured in AdMob for Riplox IG, 1–7 Sep 2026: **18 interstitials matched, 0 shown —
     * a 0% show rate on every single day of the week**, and $0.00 earned. The cause was that a
     * preload fired on every app open and every share open, while a show needs an even
     * [AdGate.SHOW_EVERY_N] slot and [AdGate.MIN_GAP_MS] since the last one. Sessions that never reached a
     * second completed download fetched an ad that could not be shown, and it expired.
     *
     * The rule that replaces it: **do not ask for an ad you do not intend to show.**
     * [AdGate.shouldPreload] fetches exactly one completion ahead of a show slot.
     */
    fun maybePreload(ctx: Context) {
        if (!ENABLED) return
        // Inside the earned ad-free window there is nothing to fetch — see [suppressed].
        if (suppressed(ctx)) return
        // ⚠️ Consent first. In the EEA/UK/Switzerland a request made before the form has been
        // answered is a request that cannot serve a personalised ad — see Consent.kt.
        // Everywhere else canRequestAds() is true from the very first call, so this costs
        // those users nothing.
        if (!Consent.canRequestAds(ctx)) return
        if (!AdGate.shouldPreload(completedCount, interstitial != null, loading)) return
        loading = true
        InterstitialAd.load(
            ctx, INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loadedAt = now()
                    loading = false
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    clearAd()
                    loading = false
                }
            }
        )
    }

    private fun clearAd() {
        interstitial = null
        loadedAt = AdGate.NEVER
    }

    /**
     * Take the cached ad if it is still worth showing for this completion, else `null`.
     *
     * A stale ad is dropped here rather than handed to `show()`: AdMob expires a cached
     * interstitial after about an hour, and showing an expired one fails through
     * `onAdFailedToShowFullScreenContent` — burning the slot without an impression.
     */
    private fun takeShowableAd(): InterstitialAd? {
        val ad = interstitial ?: return null
        if (AdGate.isStale(loadedAt, now())) {
            clearAd()
            return null
        }
        val ok = AdGate.shouldShow(
            completedCount = completedCount,
            hasAd = true,
            loadedAt = loadedAt,
            lastShownAt = lastFullScreenAt,
            now = now(),
        )
        if (!ok) return null
        // Hand the ad over and forget it here — the caller owns it from this point.
        clearAd()
        return ad
    }

    /**
     * A download finished — show an interstitial on every 2nd one (provided the app is in
     * the foreground and an ad is ready). Otherwise skip quietly and preload for next time.
     */
    fun onDownloadComplete(activity: Activity) {
        if (!ENABLED || suppressed(activity)) return
        completedCount++
        val ad = takeShowableAd()
        if (ad == null) {
            maybePreload(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {}
            override fun onAdFailedToShowFullScreenContent(e: AdError) {}
            override fun onAdClicked() { noteAdClick() }
        }
        markFullScreenShown()
        ad.show(activity)
    }

    /**
     * The share sheet's "Open app" — the ONE moment in the share flow where a full-screen ad is
     * legitimate, because the user chose to navigate *into* the app, which is a break between
     * pages of app content.
     *
     * Everything else in that flow is explicitly disallowed and must stay that way:
     *   · the sheet appearing            = app load          ("Do not place interstitial ads on
     *                                                          app load and when exiting apps")
     *   · the download finishing         = the user did not act (an ad "after every user action"
     *                                                          is called out by name)
     *   · opening the saved file         = leaving the app   (exit)
     *
     * `completedCount` is deliberately SHARED with onDownloadComplete, so the every-2 limit
     * holds across both entry points instead of each keeping its own count.
     *
     * Navigation is never delayed for an ad: with nothing ready this calls `then` immediately.
     */
    fun onEnterAppFromShare(activity: Activity, then: () -> Unit) {
        if (!ENABLED || suppressed(activity)) { then(); return }
        completedCount++
        val ad = takeShowableAd()
        if (ad == null) {
            maybePreload(activity)
            then()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { then() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { then() }
            override fun onAdClicked() { noteAdClick() }
        }
        markFullScreenShown()
        ad.show(activity)
    }
}

/**
 * The bottom banner strip (home + share sheet). Renders nothing when ads are off.
 *
 * ⚠️ WHY THIS IS NOT A THREE-LINE COMPOSABLE ANY MORE — measured on a real phone, Play build,
 * `adb logcat`:
 *
 *     I/Ads: Ad failed to load : 3          // 3 = NO_FILL
 *
 * The old version called `loadAd()` exactly once, inside the AndroidView factory, with no
 * listener attached. So the FIRST no-fill left the slot **blank until the composable was
 * destroyed and rebuilt** — in practice, until the app was reopened. That is the whole of the
 * "banner shows on one download, then never again until much later" report, and it was never
 * an AdMob problem: Google said "nothing right now", and nothing ever asked again.
 *
 * A brand-new AdMob app genuinely gets a lot of no-fill while it ramps up. That part is not
 * ours to fix. Asking again is.
 *
 * Four things this now does that it did not:
 *  1. **Retries on failure**, backing off 5s → 10s → 20s → 40s → 60s and holding there. The
 *     ceiling matches AdMob's own default auto-refresh rate, so a filled banner and a retrying
 *     one make the same number of requests — this is not extra load, and it is not abusive.
 *  2. **Adaptive size** instead of a fixed 320×50. The fixed size draws from the smallest
 *     demand pool there is, so it no-fills more often AND earns less per impression. Adaptive
 *     anchored is what Google recommends for exactly this slot.
 *  3. **Destroys the AdView** when the slot goes away. Every share used to leak one.
 *  4. **Pauses with the lifecycle.** A banner left refreshing behind a backgrounded app burns
 *     impressions nobody sees, which drags the whole unit's viewability — and therefore its
 *     eCPM — down.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val adSize = remember(widthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }
    AdSlot(adSize, Ads.BANNER_ID, modifier.fillMaxWidth().height(adSize.height.dp))
}

/**
 * The 300×250 box at the top of the tall share sheet (v1.3.0).
 *
 * Its size is reserved from the very first frame and never changes — the slot shows
 * [placeholder] until an ad lands, and keeps it if none ever does. That is deliberate: a slot
 * that grew when its ad arrived would push the panel's top edge up into exactly the place a
 * user taps to dismiss the sheet, and an ad that jumps under a finger is the textbook
 * accidental click.
 *
 * ⚠️ Never clip or round this AdView. The AdChoices badge sits in the creative's corner, and a
 * clipped one is a policy problem, not a style choice.
 */
@Composable
fun BoxAd(modifier: Modifier = Modifier, placeholder: Color = Color.Transparent) {
    AdSlot(AdSize.MEDIUM_RECTANGLE, Ads.BOX_ID, modifier.size(300.dp, 250.dp).background(placeholder))
}

/** One ad slot: retry ladder, lifecycle, cleanup. Shared by [BannerAd] and [BoxAd]. */
@Composable
private fun AdSlot(adSize: AdSize, unitId: String, modifier: Modifier) {
    if (!Ads.ENABLED) return
    val context = LocalContext.current
    // The earned ad-free window silences this slot completely — no AdView is even created.
    if (Ads.suppressed(context)) return
    val lifecycleOwner = LocalLifecycleOwner.current

    // ONE AdView for the life of this slot. Recreating it on recomposition would fire a fresh
    // ad request every time the screen changed, which is how you get throttled.
    val adView = remember {
        AdView(context).apply {
            setAdSize(adSize)
            adUnitId = unitId
        }
    }
    // For the load-time line in logcat — the only way to see how long a sheet waits for its ad.
    val bornAt = remember { SystemClock.elapsedRealtime() }

    // `attempt` drives the backoff; `tick` is what actually re-triggers a load. They are
    // separate because a successful load resets `attempt` to 0, and that alone must NOT count
    // as a reason to request another ad.
    var attempt by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(adView) {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.i(TAG, "${adSize.width}x${adSize.height} loaded +${SystemClock.elapsedRealtime() - bornAt}ms (after $attempt failed)")
                attempt = 0
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.i(TAG, "${adSize.width}x${adSize.height} failed code=${error.code} +${SystemClock.elapsedRealtime() - bornAt}ms")
                // Code 3 is NO_FILL and is by far the common one. Everything else (network,
                // internal error) is retried the same way — none of them are permanent, and a
                // banner that gives up is indistinguishable to the user from a broken app.
                attempt += 1
                tick += 1
            }
            // Tapping an ad leaves the app; coming back must not be greeted by an app-open ad.
            override fun onAdClicked() { Ads.noteAdClick() }
        }
        onDispose {
            adView.adListener = object : AdListener() {}
            adView.destroy()
        }
    }

    LaunchedEffect(tick) {
        if (attempt > 0) {
            // 5 → 10 → 20 → 40 → 60s, then 60s for good. (This capped at 40s before v1.3.0 —
            // the step stopped one short of the 60s the comment above promises.)
            val step = (attempt - 1).coerceAtMost(4)
            kotlinx.coroutines.delay(minOf(60_000L, 5_000L shl step))
        }
        adView.loadAd(AdRequest.Builder().build())
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { adView })
    }
}
