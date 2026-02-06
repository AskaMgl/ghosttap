# ProGuard rules for GhostTap
# Keep model classes for serialization
-keep class com.ghosttap.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
