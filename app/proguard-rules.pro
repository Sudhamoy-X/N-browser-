# Add project specific ProGuard rules here.
# Preserve Room, DataStore & Domain models
-keep class com.example.data.model.** { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.domain.** { *; }

# Preserve WebView @JavascriptInterface bridge methods (NyxoraConnectWebViewBridge)
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.ui.components.NyxoraConnectWebViewBridge { *; }

# Keep OkHttp / TLS platform warnings quiet
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

