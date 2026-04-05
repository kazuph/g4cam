# LiteRT-LM - keep all classes (JNI usage)
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Gson (used by LiteRT-LM)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# CameraX
-keep class androidx.camera.** { *; }
