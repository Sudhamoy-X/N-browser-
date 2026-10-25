# Add project specific ProGuard rules here.
# Preserve Room & Firestore data models
-keep class com.example.data.model.** { *; }
-keep class com.example.data.local.** { *; }

# Keep OkHttp / TLS platform warnings quiet
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
