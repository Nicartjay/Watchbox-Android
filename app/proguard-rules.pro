# ======================================================================
# Libraries the extensions link BY NAME
# ======================================================================
#
# Keeping only eu.kanade.tachiyomi was not enough. Extension dex files resolve
# these packages from the host too, so R8 renaming them produces
# ClassNotFoundException at instantiation time. Verified with dexdump against
# real extension APKs; the reported failure was:
#
#   Didn't find class "kotlin.jvm.internal.MutablePropertyReference1Impl"
#
# because R8 had rewritten kotlin/jvm/internal/* to single letters.
#
# The extensions reference, in descending order of use: kotlinx.serialization,
# kotlin.jvm, kotlin.coroutines, kotlin.text, jsoup nodes, androidx.preference
# and most of okhttp3. Names are the ABI here, so obfuscation is off for all of
# them. This costs APK size, which is the right trade against sources that
# cannot load.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class rx.** { *; }
-keep class androidx.preference.** { *; }
-dontwarn kotlin.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn rx.**

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

# Injekt resolves types by reflection, not by a type token it is handed.
#
# `addSingleton<T>()` / `injectLazy<T>()` compile to an anonymous subclass of
# FullTypeReference, whose constructor reads
# `getClass().getGenericSuperclass()` and throws
# "TypeReference constructed without actual type information" when that comes
# back as a plain Class instead of a ParameterizedType.
#
# Two things break it, and both need suppressing:
#   * dropping the Signature/EnclosingMethod attributes erases the type argument;
#   * R8 class merging flattens the anonymous subclass into its base, which
#     removes the generic superclass relationship entirely.
#
# This crashed the first 2.0.0 release on launch while the debug build ran fine,
# so it is minification-only and invisible without an instrumented run.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class uy.kohesive.injekt.** { *; }
-keep,allowobfuscation class uy.kohesive.injekt.api.TypeReference
-keep,allowobfuscation class uy.kohesive.injekt.api.FullTypeReference
-keep,allowobfuscation class * extends uy.kohesive.injekt.api.FullTypeReference
-keep,allowobfuscation class * implements uy.kohesive.injekt.api.TypeReference
-optimizations !class/merging/*
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

# ======================================================================
# Casting
# ======================================================================
#
# The Cast SDK instantiates the options provider reflectively from the class name
# in AndroidManifest.xml, so renaming or removing it breaks casting only in
# release builds -- the same failure mode as the Injekt regression.
-keep class space.nicart.watchbox.cast.CastOptionsProvider { *; }
-keep class com.google.android.gms.cast.** { *; }
-dontwarn com.google.android.gms.cast.**

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

# The download service is named in AndroidManifest.xml and started by Media3 from
# that name, so R8 must not rename or remove it. The scheduler's JobService is
# declared the same way and reached only from the manifest, so it needs the same
# treatment - without these, downloads fail only in a release build.
-keep class space.nicart.watchbox.download.MediaDownloadService { *; }
-keep class space.nicart.watchbox.download.FfmpegDownloadService { *; }
-keep class androidx.media3.exoplayer.scheduler.PlatformScheduler$PlatformSchedulerService { *; }

# ------------------------------------------------------------------ FFmpeg
# ffmpeg-kit is reached through JNI from its own native libraries, so R8 must not
# rename or remove the classes those callbacks land on.
-keep class com.arthenica.ffmpegkit.** { *; }

# smartexception is now a real dependency rather than a suppressed warning: ffmpeg-kit
# calls it from its static initialiser, so -dontwarn only moved the failure from build
# time to the first download. Kept because it is reached reflectively.
-keep class com.arthenica.smartexception.** { *; }

# --------------------------------------------------------------------- Coil
-dontwarn coil.**

# Keep enum valueOf/values, used by serialization and by `entries` lookups.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
