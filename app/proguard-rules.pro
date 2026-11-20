# Keep rules for Offline AI.

# Compose / Kotlin
-dontwarn org.jetbrains.annotations.**

# JNI entry points. The native library resolves these by exact package/class/method name.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.soprso.dhurandharai.inference.NativeBridge { *; }
-keep interface com.soprso.dhurandharai.inference.TokenListener { *; }
-keep interface com.soprso.dhurandharai.inference.MetricsListener { *; }
# JNI looks these callback methods up by literal name and signature.
-keepclassmembers class * {
    public void onToken(byte[]);
    public void onMetrics(long[]);
}
