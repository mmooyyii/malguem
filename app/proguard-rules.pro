# ---- Gson 反射模型: 字段名就是 json key, 混淆重命名会让持久化索引跨版本失效 ----
# (数据源配置 to_json/from_json 走 HashMap, 不依赖字段名, 无需 keep)
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$IndexData { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$IndexEntry { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$TocItem { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.AppUpdater$Manifest { <fields>; }

# Gson 泛型 TypeToken 需要保留签名信息
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---- jcifs-ng: 内部有配置驱动的动态加载, 整体保留最稳 (bouncycastle 走直接类引用, 不用额外 keep) ----
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**

# okhttp/okio 官方建议的忽略项
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 崩溃堆栈保留行号, 方便远程排查
-keepattributes SourceFile, LineNumberTable
