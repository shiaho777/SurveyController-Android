# ===== R8 激进优化 =====
# 允许 R8 修改类/方法访问权限以跨边界内联。
-allowaccessmodification

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# JSpecify（仅编译期空安全注解，运行时无业务依赖；被 CameraX / ZXing / Accompanist 等间接引用）
-dontwarn org.jspecify.annotations.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Jsoup
-keep class org.jsoup.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
