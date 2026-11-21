# R8 rules for the release build of Offline AI.
#
# R8 (code shrinking + optimisation + obfuscation) is enabled only for the
# release type in app/build.gradle.kts; debug builds remain unminified so the
# existing manual install/debug workflow is unchanged.
#
# The release mapping file is written by R8 to the (git-ignored) build output:
#   app/build/outputs/mapping/release/mapping.txt
# Keep it for future crash analysis; do not commit it.

# --- Kotlin / general -------------------------------------------------------
# JetBrains annotations are compile-time only for our purposes.
-dontwarn org.jetbrains.annotations.**

# Keep source file and line numbers so the mapping file produces readable
# stack traces for release crashes.
-keepattributes SourceFile,LineNumberTable

# --- JNI / native llama.cpp bridge -----------------------------------------
# JNI resolves these by the exact Java class name and native method name
# (Java_com_soprso_dhurandharai_inference_NativeBridge_...). Keeping only
# classes that actually declare native methods, plus their descriptor types,
# is sufficient and avoids a blanket keep of the whole bridge class.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The runtime class of each listener is queried with GetObjectClass() and its
# callback is found with GetMethodID(..., "onToken"/"onMetrics", ...), so the
# method names must survive obfuscation. Restrict the keep to implementations
# of the two SAM interfaces instead of every class in the app.
-keep class com.soprso.dhurandharai.inference.TokenListener { *; }
-keep class com.soprso.dhurandharai.inference.MetricsListener { *; }
-keepclassmembers class * implements com.soprso.dhurandharai.inference.TokenListener {
    public void onToken(byte[]);
}
-keepclassmembers class * implements com.soprso.dhurandharai.inference.MetricsListener {
    public void onMetrics(long[]);
}
# Kotlin SAM lambdas are materialised as synthetic classes only during D8
# desugaring, so the rules above cannot see them. Keep the exact descriptor as
# a safety net: it only matches methods with these precise signatures.
-keepclassmembers class * {
    public void onToken(byte[]);
    public void onMetrics(long[]);
}

# --- Room (local conversation/model/download database) ---------------------
# Room generates implementation classes that extend the declared database and
# reference @Entity classes directly. Keep those declaration points.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
