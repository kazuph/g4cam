# ML Kit GenAI (AICore primary)
-keep class com.google.mlkit.genai.** { *; }
-keepclassmembers class com.google.mlkit.genai.** { *; }

# LiteRT-LM (fallback)
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Gson (used by LiteRT-LM)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp (model download)
-dontwarn okhttp3.**
-dontwarn okio.**

# CameraX
-keep class androidx.camera.** { *; }
