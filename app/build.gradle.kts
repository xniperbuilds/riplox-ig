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
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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
