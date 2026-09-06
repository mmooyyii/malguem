# malguem

Android TV 上看 epub 漫画/小说的阅读器, 所有操作可用遥控器完成. 纯 Java, 单 module (app), 无测试.

## 注意
每次改完代码, 自动 commit
我说发版就打 tag + push

## 本地构建

本机默认 JDK 是 Java 8, 构建必须显式指定环境变量:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleDebug
```

- Android SDK 装在 `~/Library/Android/sdk` (cmdline-tools + platform-35 + build-tools;35.0.0)
- 验证方式: 编译通过 + 真机 (没有单测框架)

## 发版与应用内 OTA 更新

发版流程: commit → 打 `v` 开头的 tag (如 `v1.5.0`) → push tag → GitHub Actions 构建签名 APK, 创建 Release,
并自动把 malguem-tv.apk + version.json 发布到 GitHub Pages 与 release 分支 (jsDelivr 回源) —— 全程无手动步骤.
电视端更新源依次尝试: Pages → jsDelivr 三个域 → github → ghproxy 镜像 (大陆无代理时前几个通常有一个能通).
电视上用首页的"检查更新"按钮拉新版, 长按该按钮可设置自定义更新源 (主人要求不做隐式自动检查).

- `versionName` = tag 名, `versionCode` = CI run_number, 由 release.yml 的 env 注入; 本地构建默认 `dev`/1
- Release 资产用**固定文件名**: `malguem-tv.apk` 与 `version.json` (`{"tag":"..."}`);
  客户端走 `releases/latest/download/<固定名>` 这个 GitHub 固定重定向 URL, 不依赖 api.github.com
- 更新逻辑在 `AppUpdater.java`; `SOURCES` 数组是"直连 → ghproxy 镜像"候选源, 依次尝试, 镜像失效在那里换域名
- OTA 覆盖安装要求签名一致: 电视上必须装 CI 签名的包, 本地 debug 包装不上去

## 架构速记

- 数据源: `ResourceInterface` 四个实现 (webdav/smb/local/opds), 配置序列化成 json 存 SQLite (`Database.java`), 按字节区间 `Slice` 随机读;
  OPDS 目录按标题映射进 pwd 模型, Range 读复用 WebdavResource 的 http 客户端
- 只支持 epub (`LazyEpub` 流式解析); pdf 支持做过又拆掉了, 别再加回来 (PdfRenderer 只认本地文件, 整本下载与产品定位不符)
- `LazyEpub`: 流式解析 epub, 只按 HTTP Range 拉取需要的 zip 条目 (EOCD → 中央目录 → 按需取本地头+数据)
- `WebdavResource` 处理 multipart range 响应; 注意服务器可能合并相邻 range (RFC 7233),
  分段必须按覆盖关系分配 (`assignParts`), 不能按 offset 精确配对
- 阅读界面: `NovelActivity` (WebView 翻章) / `ComicActivity` (左右双栏), 文件列表菜单键/长按OK切换两种模式
- 阅读中 OK/菜单键呼出阅读菜单: 目录跳转(`LazyEpub` 解析 ncx/nav, 存进 epub_index, 索引版本 v2) /
  SeekBar 跳页 / 小说字号+夜间(SharedPreferences 全局) / 漫画阅读方向rtl+单页(epub 表按书存) / 互切模式(顶替 Activity)
- 小说章内进度 page_offset 存万分比而非像素 (字号/夜间重排后按比例恢复); MainActivity 用 onResume 刷新列表
- 首页最前排是"最近阅读" (`epub.last_read` 倒序, `RecentEpub` 类型条目自带 namespace, 点开直接续读)
- release 开了 R8: Gson 反射模型(epub 索引/更新清单)和 jcifs 的 keep 规则在 proguard-rules.pro, 新增反射模型记得补 keep
