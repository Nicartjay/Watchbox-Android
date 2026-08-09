import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    (providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: default)

// ---------------------------------------------------------------- signing
//
// This is a personal app, so the release keystore is committed to the repo and
// its passwords are published below on purpose.
//
// The reason is upgrade compatibility, not secrecy: Android refuses to install
// an update whose signature differs from the installed copy. Falling back to the
// debug keystore does not work here, because every CI runner is a fresh VM that
// generates its own debug key — two consecutive release builds would be signed
// with different keys and neither could update the other.
//
// A committed key means anyone can build an APK that Android treats as an update
// to this one. That is an accepted trade-off for a personal build. Anything
// distributed more widely should move these into CI secrets instead; the env
// overrides below already support that without touching this file.
val releaseStoreFile = secret("WATCHBOX_RELEASE_STORE_FILE", "../release.jks")
val releaseStorePassword = secret("WATCHBOX_RELEASE_STORE_PASSWORD", "watchbox")
val releaseKeyAlias = secret("WATCHBOX_RELEASE_KEY_ALIAS", "watchbox")
val releaseKeyPassword = secret("WATCHBOX_RELEASE_KEY_PASSWORD", "watchbox")
val releaseKeystore = file(releaseStoreFile)
val hasReleaseSigning = releaseKeystore.exists()

// Default extension repository. Overridable from CI env or local.properties,
// and editable at runtime in Settings.
val defaultRepoUrl = secret(
    "WATCHBOX_REPO_URL",
    "https://raw.githubusercontent.com/yuzono/anime-repo/repo",
)

// TMDB is used purely for artwork (backdrops, title logos, episode stills);
// it is never a content source. The default is the widely-published demo key.
val tmdbApiKey = secret("TMDB_API_KEY", "d8cd4489c203c5e8c8efb70aa8e33565")

// Version. `appVersionName` is the source of truth; CI may override both so a
// tag push (v1.2.3) produces a matching APK without editing this file.
val appVersionName = secret("WATCHBOX_VERSION_NAME", "3.3.0")
val appVersionCode = secret("WATCHBOX_VERSION_CODE", "1").toIntOrNull() ?: 1

android {
    namespace = "space.nicart.watchbox"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "space.nicart.watchbox"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "DEFAULT_REPO_URL", "\"$defaultRepoUrl\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        // The update APK is always the release package. Debug builds carry a
        // ".debug" suffix, so comparing a download against the *running* package
        // name would reject every legitimate update.
        buildConfigField("String", "RELEASE_APPLICATION_ID", "\"space.nicart.watchbox\"")
        // Lets the app pick its own APK out of a release that carries both, and
        // lets the UI branch without probing the device at runtime.
        buildConfigField("String", "FORM_FACTOR", "\"mobile\"")
    }

    /**
     * Two form factors, two APKs.
     *
     * A single combined APK is possible - one activity carrying both LAUNCHER and
     * LEANBACK_LAUNCHER - but it forces every phone install to carry the TV UI and
     * the leanback manifest entries, and it makes the TV build impossible to
     * install alongside the phone build for testing. Separate flavors keep each
     * download to what that device actually runs.
     *
     * The two share every line of non-UI code; only the manifest, the launcher
     * category and the UI entry point differ.
     */
    flavorDimensions += "formFactor"

    productFlavors {
        create("mobile") {
            dimension = "formFactor"
            // No suffix: this is the established package name, and changing it
            // would orphan every existing install.
            isDefault = true
        }

        create("tv") {
            dimension = "formFactor"
            buildConfigField("String", "FORM_FACTOR", "\"tv\"")
            buildConfigField("String", "RELEASE_APPLICATION_ID", "\"space.nicart.watchbox.tv\"")
            // A distinct package so both can be installed at once, and so the
            // Play-style "already installed" check cannot confuse the two.
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseSigning) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            // Universal-only for simplicity: one APK that installs anywhere.
            isEnable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                // Media3 marks its unstable surface with @UnstableApi, which is an
                // annotation rather than an opt-in marker, so it needs -Xannotation
                // suppression instead of -opt-in.
                "-Xsuppress-warning=NOTHING_TO_INLINE",
            )
        }
    }

    sourceSets.getByName("main") {
        kotlin.srcDir("src/main/kotlin")
    }

    sourceSets.getByName("test") {
        kotlin.srcDir("src/test/kotlin")
    }

    // The project keeps sources under `<set>/kotlin` rather than the default
    // `<set>/java`, so each flavor set needs the same wiring or its files are
    // silently ignored - which compiles, then fails at runtime on the missing
    // entry point.
    sourceSets.getByName("mobile") {
        kotlin.srcDir("src/mobile/kotlin")
    }

    sourceSets.getByName("tv") {
        kotlin.srcDir("src/tv/kotlin")
    }

    // TV-only tests. They cannot live in the shared `test` set: it is compiled for both
    // flavors, so the mobile task would try to build them against classes that only
    // exist in the tv source set.
    sourceSets.getByName("testTv") {
        kotlin.srcDir("src/testTv/kotlin")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    lint {
        // Fail CI on lint errors; the project currently reports zero.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf(
            // Dependency-freshness notices are handled deliberately, not per-build.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Unit tests for the pure logic that is expensive to verify on a device:
    // HLS manifest rewriting and version comparison.
    testImplementation(libs.kotlin.test)

    implementation(libs.kotlinx.serialization.json)
    // These four are not our choices: they are the "common" dependency bundle
    // every extension in the repo is compiled against, so the host has to
    // provide them or sources fail with NoClassDefFoundError on first use.
    // Cineby needed json-okio; several sources use protobuf APIs, and a few
    // evaluate JavaScript through QuickJS.
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json.okio)
    implementation(libs.quickjs)
    implementation(libs.kotlinx.coroutines.android)

    // ---- Aniyomi extension runtime -------------------------------------
    // Extension APKs are built compileOnly against the source API, so the
    // host app is the library provider. These versions are part of the ABI
    // the extensions link against, not free choices.
    implementation(libs.rxjava)
    implementation(libs.injekt.core)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.brotli)
    implementation(libs.androidx.preference)

    // ---- Casting ------------------------------------------------------
    // Chromecast discovery, the session lifecycle and the standard cast button.
    // Reimplementing protobuf-over-TLS by hand would be several hundred lines
    // for a worse device picker; the trade is a Google Play dependency.
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
}
