# malguem 代码审查与修复报告

> 生成日期：2026-09-02
> 审查范围：`app/src/main/java/com/github/mmooyyii/malguem/` 全部源码（约 1900 行）
> 审查方式：整仓通读 + 多角度并行查错（ZIP/HTTP-Range 解析、Android 生命周期/线程、DB/NPE、清理）+ 逐条验证

## 1. 项目概况

一个流式 EPUB 阅读器（Android，Java）：

- 通过 WebDAV 的 HTTP **Range 请求**惰性读取 EPUB（ZIP）文件，不下载整本。
- 手写解析 ZIP 中央目录 / 本地文件头，`Inflater` 解压，结果喂给 `WebView` 渲染。
- 两种阅读模式：漫画（左右两个 WebView，一次两页）、小说（单个滚动 WebView）。
- 阅读进度存 SQLite。主要目标形态是 TV（方向键翻页）。

关键文件：

| 文件 | 职责 |
|---|---|
| `LazyEpub.java` | ZIP/EPUB 惰性解析、解压、资源缓存 |
| `WebdavResource.java` | 发 HTTP Range 请求、拆分 multipart/byteranges 响应 |
| `ComicActivity.java` / `NovelActivity.java` | 两种阅读界面 |
| `MainActivity.java` | 资源/文件列表、WebDAV 登录 |
| `Database.java` | SQLite 阅读历史 |

## 2. 本次修复概览

共修复 **15 项**正确性/功能问题，覆盖崩溃、ANR、打不开书、续读失效、内存无上限等。
安全类问题（硬编码账号密码、明文存储）按要求**跳过**。清理类问题（重复代码、手写轮子等）**暂未处理**。

状态图例：✅ 已修复　⏸️ 未处理　⚠️ 行为变更待确认

---

## 3. 已修复的问题

### P0 — 崩溃 / ANR

| # | 状态 | 位置 | 问题 | 修复 |
|---|---|---|---|---|
| 1 | ✅ | `ComicActivity` / `NovelActivity` `notifyPageChanged`→`show_new_page` | 翻页在 **UI 线程**上 `latch.await()` 等网络，慢网必 ANR | 拆成 `loadExecutor` 后台 `prepare()`+取 html，`runOnUiThread` 里只做 WebView 渲染 |
| 2 | ✅ | `MainActivity` `FetchFileListTask` | `ls()` 失败时在**后台线程**弹 Toast → `Can't toast on a thread...` 崩溃 | 用 `handler.post` 切回主线程再弹 |
| 3 | ✅ | `ComicActivity`/`NovelActivity` `onDestroy` + `AndroidManifest.xml` | 原设计：reader 跑在独立进程 `:webview_process`，`onDestroy` 里 `killProcess`+`System.exit` 杀该进程以回收 WebView native 内存。副作用大（旋转即退出、两个进程各开一份 SQLite 写同一文件） | 去掉 `android:process`，改为单进程 + `onDestroy` 里规范 `webView.destroy()` 释放内存；异步渲染回调加 `isDestroyed()` 兜底（**见 §5**） |
| 4 | ✅ | `ComicActivity` 右屏渲染 `page+1` | 单页 epub / 跨模式打开最后一页 → `contents.get(total)` 越界崩溃 | 渲染前判 `page < total`；初始页 clamp 到 `[0,total-1]`；奇数末页清空右屏 |
| 5 | ✅ | `ComicActivity` `dispatchKeyEvent` 右键 | `total-2` 下界，1~2 页书得到负索引 → `get(-1)` 崩溃 | 外套 `Math.max(0, …)` |

### P1 — 功能错误 / 健壮性（会导致打不开或行为错误）

| # | 状态 | 位置 | 问题 | 修复 |
|---|---|---|---|---|
| 6 | ✅ | `WebdavResource.open` + `LazyEpub` | 多 range 请求遇服务器回 200 整文件 / 单个 206 / 合并分段 → map 空或缺项 → 抛异常打不开；`assert` 在 Android 默认关闭无法兜底 | 按 `Content-Type` 判断是否 multipart；200 时本地切片；单段 206 按 `Content-Range` 匹配；缺段用判空跳过 |
| 7 | ✅ | `LazyEpub` `init_epub_dir` | 只取末尾 22 字节定位 EOCD，带 ZIP 注释就打不开 | 找不到时退回取末尾 65557 字节（22+最大注释）重试 |
| 8 | ✅ | `LazyEpub.initContent` | `<itemref>` 缺 `idref` → `getNamedItem(...).getNodeValue()` NPE | 判空跳过 |
| 9 | ✅ | `LazyEpub.initContent` | `idref` 在 manifest 找不到 → `contents` 存入 null → 翻页 `cut(null)` NPE | 只在 path 非 null 时加入 `contents` |
| 10 | ✅ | `LazyEpub.initContent` | 无 `<dc:title>` → `titles.item(0)` 为 null → NPE | 判长度/判空后再取 |
| 11 | ✅ | `LazyEpub.load_file_to_cache` | 空文件 `compressedSize==0` → `size=0` → 生成 `off-(off-1)` 反向 Range | 空文件直接缓存空字节，不发请求 |
| 12 | ✅ | `WebdavResource.Buffer.add` | 扩容 `new byte[...]` **未拷贝旧内容**，行超 99 字节时数据丢失 → 分段解析错乱 | 改用 `Arrays.copyOf` |
| 2b | ✅ | `WebdavResource.SplitMultipleRanges` | 响应 Slice（size=end-start+1）与请求 Slice 的 `equals/hashCode` 不一致 → 查不到 | 按 offset 把分段映射回请求时的 Slice 实例 |

### P2 — 功能瑕疵 / 隐患

| # | 状态 | 位置 | 问题 | 修复 |
|---|---|---|---|---|
| 13 | ✅ | `NovelActivity.show_new_page` | `scrollTo` 在 `loadDataWithBaseURL` 之前 → 续读滚动位置被重置，恢复无效 | 移到 `WebViewClient.onPageFinished` 里恢复 |
| 14 | ✅ | `LazyEpub.initContent` | `resource_type` 用原始 href 作 key，`GetMediaType` 用 `cut()` 后查 → MIME 为 null | 存入时也用 `cut(href)`，两侧一致 |
| 15 | ✅ | `LazyEpub` `resource` 缓存 | 只增不删，读图多的漫画长时间读 → OOM | 换成 64MB 上限的 LRU（`LinkedHashMap` accessOrder + 同步存取，超预算淘汰最旧） |

---

## 4. 未处理项（清理 / 设计，暂留）

以下**不影响正确性**，本次未动，按需再处理：

- **重复代码**：`ComicActivity` 与 `NovelActivity` ~90% 重复（WebView 配置、taskQueue 抽干循环、`OpenEpub` 内部类、`shouldInterceptRequest` 等），已在漂移，建议抽公共基类。
- **手写轮子**：手写 multipart/byteranges 解析、手写 ZIP 中央目录/本地头解析（无 ZIP64）、`ls` 用正则 `<D:href>` 解析 XML + `url_decode` 的字符串占位 hack。可用 okhttp / `java.util.zip` / XML 解析替代。
- **死代码**：`DecimalFormat fmt`（Comic/Novel）、`NovelActivity.updateProgress` 从未调用。
- **数据丢失**：`Database.onUpgrade` 直接 `drop table`，版本升级即丢全部阅读历史。
- **副作用**：`Database.get_view_types` 会修改调用方传入的 list（目前 latent，`epubs` 未被复用）。
- **小浪费**：`MainActivity.make_uri` 每个条目算 3 次；prefetch 每次翻页 `new WebViewClient`。

## 5. 关于 WebView 内存与独立进程（问题 #3 更正）

> 更正：初版报告说 `killProcess` “杀掉整个 app” 是**错的**——因为 manifest 里 reader 声明了 `android:process=":webview_process"`，跑在独立进程，`killProcess` 杀的是那个 webview 进程，MainActivity（主进程）不受影响。这其实是作者**故意用“独立进程 + 用完杀进程”来彻底回收 WebView native 内存**的方案。

本次按“去掉独立进程”的目标重构为标准做法：

- `AndroidManifest.xml`：移除两个 Activity 的 `android:process=":webview_process"`，改回单进程。
- `onDestroy`：新增 `destroyWebView()`——从视图树移除 → `stopLoading` → 加载 `about:blank` → `removeAllViews` → `destroy()`，规范释放 WebView native 内存，不再依赖杀进程。
- 异步加载回调加 `isDestroyed()` 兜底，避免销毁后仍操作 WebView。
- 附带收益：不再有第二个进程各自打开一份 SQLite 写同一文件，消除潜在的跨进程 DB 锁竞争。

`MainActivity` 根目录“连按两次返回退出”的逻辑**未改动**。

> 说明：极少数机型上 Chromium renderer 的 native 内存 `destroy()` 后不一定 100% 立即归还 OS；独立进程+杀进程是最彻底但最 hack 的方案。若实测单进程内存仍不满意，可退回原方案（恢复 `android:process` + `onDestroy` 里 `killProcess`）。当前 `largeHeap="true"` 已开启。

## 6. 构建 / 验证状态

- ✅ 5 个改动文件均通过 **IDE（JetBrains）错误级 inspection**，类路径完整解析、无 error。
- ⚠️ **本机未跑完整 Gradle 构建**：默认 JDK 为 8（AGP 8.8 需要 JDK 11+），且未配置 Android SDK（无 `local.properties` / `ANDROID_HOME`）。
- 👉 建议在 Android Studio 或 CI 上执行一次完整构建 + 真机/模拟器冒烟测试确认。

## 7. 改动文件清单

```
app/src/main/java/com/github/mmooyyii/malguem/LazyEpub.java
app/src/main/java/com/github/mmooyyii/malguem/WebdavResource.java
app/src/main/java/com/github/mmooyyii/malguem/ComicActivity.java
app/src/main/java/com/github/mmooyyii/malguem/NovelActivity.java
app/src/main/java/com/github/mmooyyii/malguem/MainActivity.java
app/src/main/AndroidManifest.xml
```
