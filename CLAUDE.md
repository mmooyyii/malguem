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

发版流程: commit → 打 `v` 开头的 tag (如 `v1.5.0`) → push tag → GitHub Actions 构建签名 APK 并创建 Release. 电视上的 app 启动时自动检查更新、下载并拉起安装器.

- `versionName` = tag 名, `versionCode` = CI run_number, 由 release.yml 的 env 注入; 本地构建默认 `dev`/1
- Release 资产用**固定文件名**: `malguem-tv.apk` 与 `version.json` (`{"tag":"..."}`);
  客户端走 `releases/latest/download/<固定名>` 这个 GitHub 固定重定向 URL, 不依赖 api.github.com
- 更新逻辑在 `AppUpdater.java`; `SOURCES` 数组是"直连 → ghproxy 镜像"候选源, 依次尝试, 镜像失效在那里换域名
- OTA 覆盖安装要求签名一致: 电视上必须装 CI 签名的包, 本地 debug 包装不上去

## 架构速记

- 数据源: `ResourceInterface` 三个实现 (webdav/smb/local), 配置序列化成 json 存 SQLite (`Database.java`), 按字节区间 `Slice` 随机读
- `LazyEpub`: 流式解析 epub, 只按 HTTP Range 拉取需要的 zip 条目 (EOCD → 中央目录 → 按需取本地头+数据)
- `WebdavResource` 处理 multipart range 响应; 注意服务器可能合并相邻 range (RFC 7233),
  分段必须按覆盖关系分配 (`assignParts`), 不能按 offset 精确配对
- 阅读界面: `NovelActivity` (WebView 翻章) / `ComicActivity` (左右双栏), 文件列表按菜单键切换两种模式
- 阅读中 OK/菜单键呼出阅读菜单: 目录跳转(`LazyEpub` 解析 ncx/nav, 存进 epub_index, 索引版本 v2) /
  SeekBar 跳页 / 小说字号(textZoom, SharedPreferences 全局) / 漫画阅读方向(rtl, epub 表按书存)
- 首页最前排是"最近阅读" (`epub.last_read` 倒序, `RecentEpub` 类型条目自带 namespace, 点开直接续读)
