# Rules for the release build's code shrinker (R8).
# If the release build crashes but the debug build doesn't, a rule here is missing.

# kotlinx.serialization: keep the generated serializers for our server JSON classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class dev.sushnt.journal.**$$serializer { *; }
-keepclassmembers class dev.sushnt.journal.** {
    *** Companion;
}
-keepclasseswithmembers class dev.sushnt.journal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLite (bundled native library, called through JNI).
-keep class androidx.sqlite.driver.bundled.** { *; }

# Background sync worker (created by WorkManager by name).
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# Sign in with Google (Credential Manager loads the Play Services provider by name).
-keep class androidx.credentials.playservices.** { *; }

# Optional pieces Ktor/OkHttp mention but this app doesn't use.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**

