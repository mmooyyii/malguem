# ---- Gson 反射模型: 字段名就是 json key, 混淆重命名会让持久化索引跨版本失效 ----
# (数据源配置 to_json/from_json 走 HashMap, 不依赖字段名, 无需 keep)
#
# !! 坑: -keepclassmembers 只防"重命名/移除", 防不住 R8 full mode 的 field value propagation.
# 一个字段如果只被读、从不被写 (写入全靠 Gson 反射), R8 会推断它恒为 null,
# 把读取替换成常量并把字段删掉 —— keep 规则形同虚设, 且编译期毫无警告.
# AppUpdater 的 version.json 模型就这么被吃了, OTA 在 release 包上一直失效;
# 已改成 org.json 手解, 不再有这个模型.
# 下面几个之所以安全, 是因为应用自己也会构造并写入它们 (建索引/存布局), 不是纯只读.
# 新增纯反序列化的模型别再用 -keepclassmembers, 用 -keep class X { <fields>; } 或干脆手解.
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$IndexData { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$IndexEntry { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.LazyEpub$TocItem { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.LazyCbz$CbzIndex { <fields>; }
-keepclassmembers class com.github.mmooyyii.malguem.ComicLayout { <fields>; }

# Gson 泛型 TypeToken 需要保留签名信息.
# 注意 gson 2.10.1 不带 consumer 规则, R8 full mode 会剥掉 TypeToken 匿名子类的泛型签名 —
# 代码里已改为不用 TypeToken, 下面两条是防再有人用的兜底 (v1.7.0 的闪退教训)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

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
