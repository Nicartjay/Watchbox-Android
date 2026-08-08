# ======================================================================
# Extension ABI — MUST NOT BE MINIFIED
# ======================================================================
#
# Extension APKs are compiled compileOnly against the Aniyomi source API and
# bundle none of it: at runtime their classes link against these, resolved from
# this app via a parent-last classloader. Nothing here is referenced statically
# by our own code, so without these rules R8 correctly concludes the whole tree
# is unused and deletes it. That was verified: the first release build shipped
# 4,861 classes and zero `eu.kanade.tachiyomi` ones, which would have failed at
# runtime the moment any source was used, while the debug build worked.
#
# Renaming is equally fatal — class, member and even Kotlin file-facade names
# (e.g. RequestsKt, OkHttpExtensionsKt) are part of the ABI.
#
# tools/verify-extension-abi.py checks the compiled classes; the release check in
# the CI workflow re-checks the minified dex.
-keep class eu.kanade.tachiyomi.** { *; }
-keepclassmembers class eu.kanade.tachiyomi.** { *; }
-keepnames class eu.kanade.tachiyomi.** { *; }

# Extension entry points are instantiated by reflection via a no-arg constructor.
-keepclasseswithmembers class * extends eu.kanade.tachiyomi.animesource.online.AnimeHttpSource {
    <init>();
}
-keepclasseswithmembers class * implements eu.kanade.tachiyomi.animesource.AnimeSource {
    <init>();
}
-keepclasseswithmembers class * implements eu.kanade.tachiyomi.animesource.AnimeSourceFactory {
    <init>();
}

# Injekt resolves NetworkHelper and Application by type token, so generic
# signatures must survive.
-keep class uy.kohesive.injekt.** { *; }
-dontwarn uy.kohesive.injekt.**

# RxJava 1 is only reached through the deprecated half of the source API, i.e.
# from extension bytecode rather than ours.
-keep class rx.** { *; }
-dontwarn rx.**

# Extensions parse HTML with jsoup and reflect over its node types.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# androidx.preference is reachable only from ConfigurableAnimeSource.
-keep class androidx.preference.** { *; }

# ---------------------------------------------------------------- Kotlin
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ------------------------------------------------- kotlinx.serialization
# Serializers are generated as companion/synthetic members; R8's built-in rules
# cover most of it, but the @Serializable classes themselves must survive so
# their generated serializers can be reflected.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class space.nicart.watchbox.**$$serializer { *; }
-keepclassmembers class space.nicart.watchbox.** {
    *** Companion;
}
-keepclasseswithmembers class space.nicart.watchbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Typed navigation routes are resolved reflectively by Navigation-Compose.
-keep class space.nicart.watchbox.ui.navigation.Routes** { *; }

# -------------------------------------------------------------- Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# ------------------------------------------------------------------- Media3
-dontwarn androidx.media3.**

# --------------------------------------------------------------------- Coil
-dontwarn coil.**

# Keep enum valueOf/values, used by serialization and by `entries` lookups.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
