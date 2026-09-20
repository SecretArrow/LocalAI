# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.localai.runtime.**$$serializer { *; }
-keepclassmembers class com.localai.runtime.** { *** Companion; }
-keepclasseswithmembers class com.localai.runtime.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor / Coroutines
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }

# JNI bridge
-keepclasseswithmembernames class com.localai.runtime.core.** { native <methods>; }

# Ktor references JMX classes for its IntelliJ debug detector; they do not
# exist on Android and are never used at runtime.
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**

# Coroutines debug agent is JVM-only.
-dontwarn kotlinx.coroutines.debug.**
