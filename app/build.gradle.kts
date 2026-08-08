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

val releaseStoreFile = secret("WATCHBOX_RELEASE_STORE_FILE").takeIf { it.isNotBlank() }
val releaseStorePassword = secret("WATCHBOX_RELEASE_STORE_PASSWORD").takeIf { it.isNotBlank() }
val releaseKeyAlias = secret("WATCHBOX_RELEASE_KEY_ALIAS").takeIf { it.isNotBlank() }
val releaseKeyPassword = secret("WATCHBOX_RELEASE_KEY_PASSWORD").takeIf { it.isNotBlank() }
val hasReleaseSigning =
    releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

// Backend + API keys. Overridable from CI env or local.properties.
val apiBaseUrl = secret("WATCHBOX_API_BASE_URL", "https://watchbox.nicart.space")
val tmdbApiKey = secret("TMDB_API_KEY", "d8cd4489c203c5e8c8efb70aa8e33565")

// Version. `appVersionName` is the source of truth; CI may override both so a
// tag push (v1.2.3) produces a matching APK without editing this file.
val appVersionName = secret("WATCHBOX_VERSION_NAME", "1.0.0")
val appVersionCode = secret("WATCHBOX_VERSION_CODE", "1").toIntOrNull() ?: 1

android {
    namespace = "space.nicart.watchbox"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "space.nicart.watchbox"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
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
    implementation(libs.androidx.palette)

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
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
