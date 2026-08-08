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
