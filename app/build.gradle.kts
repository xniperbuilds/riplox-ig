import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xniperbuilds.downloader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // IG fork: its own app identity (installs side by side with Riplox / Riplox TT).
        // The code package stays com.xniperbuilds.downloader — only the applicationId differs.
        applicationId = "com.xniperbuilds.riploxig"
        minSdk = 26
        targetSdk = 36
        // v1.0.0 = first IG release (RiploxTT fork): Instagram videos + photos +
        // carousels + stories, connect-account (cookies), ads, AIRLOCK v3 reliability.
        // v1.0.1 = session-failure fallback (Instagram sometimes answers a logged-in request
        // with an empty body → it now retries once without the login) + a "Use my login" switch.
        // v1.1.0 = the share tile stops being invisible. A user who only ever shares from
        // Instagram now gets a sheet with a thumbnail, live progress, Cancel, and a real error
        // with Retry / Update & retry — and, because that sheet is a visible screen, the first
        // ad impressions that flow has ever produced. Plus: one Engine (the share-only user's
        // yt-dlp was never refreshed), a manual "Update engine now", and a Google consent form
        // for EEA/UK/Swiss users so their traffic can serve personalised ads at all.
        // v1.2.0 = the interstitial actually gets SHOWN, and a reason to come back.
        //   · AdMob measured 18 matched interstitials and ZERO impressions (0% every single day) across 1-7 Sep 2026. Every one of
        //     those was fetched on app open or share open, while a show needs a 2nd completed
        //     download plus a 60s gap — so it expired unseen. Fetching now happens exactly one
        //     completion before a show slot (AdGate.kt), with a 50-minute staleness guard.
        //   · Daily check-in streak: 2 hours ad-free per rewarded view (banner AND interstitial
        //     both go quiet), a free ad-free day at a 7-day streak, and accent colours unlocked
        //     at 3/7/14/30 days. Nothing that was free has been fenced off.
        //   · Play In-App Updates: the "a new version is out" notice IG never had.
        // v1.2.1 = downloading without a connected Instagram account works again.
        //   · The engine reported a version it was not running. youtubedl-android decides
        //     "already up to date" by comparing its own SharedPreferences marker with GitHub's
        //     tag and never looks at the yt-dlp on disk — so once that marker was written
        //     without the files landing, every check answered "nothing to do" forever and the
        //     app stayed on the bundled 2025.11.12 while reporting 2026.08.30. That old
        //     extractor has no logged-out code path at all, so every download without a
        //     connected account failed. The version is now read by RUNNING yt-dlp, and a marker
        //     caught lying is cleared so the update actually happens.
        //   · Two ways the engine could never repair itself, both closed: the share sheet only
        //     refreshed it after a download SUCCEEDED (a failing app could never reach that),
        //     and a download parked on the retry backoff ladder counted as "busy".
        //   · Errors tell the truth: "connect an account" is now shown only when the failure
        //     actually says so, instead of for every failure on an Instagram link.
        versionCode = 5
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob IDs come from the build type — Google's TEST ids on a QA build, the real ones
        // on release. They used to be swapped BY HAND in three places, and clicking your own
        // real ads gets an AdMob ban, so that mistake is no longer possible.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-8029174313177489~7010524226"
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-8029174313177489/4384360886\"")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-8029174313177489/9226603961\"")
        // ⚠️ Rewarded unit for the ad-free window / accent unlocks. BLANK until the unit is
        // created in the AdMob console; while blank the app hides every rewarded option
        // rather than offering a reward it cannot deliver.
        buildConfigField("String", "AD_REWARDED_ID", "\"ca-app-pub-8029174313177489/3795631504\"")

        ndk {
            // Launch: arm64 + 32-bit (so it still installs on older phones)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing — credentials live in local.properties (gitignored; they never reach
    // the repo). Contributors without the key fall back to debug signing, so the build
    // still runs for them.
    val localProps = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
    val riploxKs: String? = localProps.getProperty("RIPLOXIG_KS")
    signingConfigs {
        if (riploxKs != null) {
            create("release") {
                storeFile = file(riploxKs)
                storePassword = localProps.getProperty("RIPLOXIG_KS_PASS")
                keyAlias = localProps.getProperty("RIPLOXIG_KEY_ALIAS")
                keyPassword = localProps.getProperty("RIPLOXIG_KEY_PASS")
                // Older/OEM installers (MIUI and friends) reject a v2-only APK, so keep
                // v1 (JAR) signing on as well and sideloading works on every phone.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = if (riploxKs != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        // QA build — identical to release, but with a DIFFERENT applicationId and Google's
        // TEST ad ids.
        // ⚠️ Why: the copy installed from Play (closed testing) is signed with the Play App
        // Signing key, so a local APK sharing its applicationId fails with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. The `.qa` suffix lets the QA build sit alongside
        // the Play one (nothing has to be uninstalled, so existing downloads and history stay
        // safe). Test ad ids mean there is no way to click your own real ads (AdMob invalid
        // traffic) — this is the ONLY build that may be installed on your own phone.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            // Google's public test rewarded unit
            buildConfigField("String", "AD_REWARDED_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The engine (Python/yt-dlp) needs real files on disk — legacy packaging on.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // yt-dlp engine (youtubedl-android — same rules as parent Riplox; no aria2c in the TT app)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Thumbnail (notification) + video-frame thumbnails in the Recent list
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Background download queue — survives app close / reboot + auto-retry
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // AdMob — bottom banner + interstitial (Ads.kt)
    implementation("com.google.android.gms:play-services-ads:24.4.0")

    // Google User Messaging Platform — the consent form (Consent.kt).
    // ⚠️ Not optional for revenue: since 16 Jan 2024 AdMob requires a Google-certified CMP to
    // serve PERSONALISED ads in the EEA, the UK and Switzerland. Without one that traffic is
    // eligible only for limited/non-personalised ads, which is where the eCPM collapses. It
    // also does nothing at all outside those regions, so it costs other users nothing.
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    // Play In-App Updates — the "a new version is out" notice (AppUpdates.kt).
    // ⚠️ Only works on an app installed from Play. Sideload/GitHub builds run no update check
    // at all: a Play-installed app may not fetch or install an APK itself (Device & Network
    // Abuse policy), so there is deliberately no home-grown updater.
    implementation("com.google.android.play:app-update:2.1.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Proper icons for settings/categories (instead of emoji)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
