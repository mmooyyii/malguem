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

## APK 体积

**死线 3.5MB** (主人定的). 当前约 2.0MB, 余量充足. 改依赖或打包配置后用 `:app:assembleRelease` 量一次
(本地没 keystore 也能出未签名包, 加签名约 +8KB).

- `org/bouncycastle/**` 在 `packaging.excludes` 里整体排除, 省 1.2MB —— **别加回来**.
  jcifs 声明了 bouncycastle 依赖但实际代码路径用不到, R8 已把它的类删得一个不剩 (dex 里引用数为 0),
  可 jar 内的 `.properties` 是资源, R8 管不着. 光 `pqc/crypto/picnic` 三张查找表就 1.21MB,
  是后量子签名算法的表, 和 SMB 加密毫无关系, 且随机数据压不动
- R8 full mode 已在 gradle.properties 关掉, 代价 +79KB, 换的是反射安全 (见下面 R8 那条坑)

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
- 只支持 epub 与 cbz (都是 zip 壳, 共用 `LazyZip` 的流式随机读, `Books` 按后缀分派并给出 isBook/stripExt);
  pdf 支持做过又拆掉了, 别再加回来 (PdfRenderer 只认本地文件, 整本下载与产品定位不符); cbr 是 rar 壳没法流式读, 同理别加
- `LazyZip`: 只按 HTTP Range 拉需要的 zip 条目 (EOCD → 中央目录 → 按需取本地头+数据), 一批条目合成一次 multi-range 请求;
  `LazyEpub` 在其上按 opf 解释包内容, `LazyCbz` 把图片按文件名自然序当页 (page2 在 page10 前), 每页一张图, 用 `cbz-page/页码.ext` 引用避开文件名编码问题
- cbz 只有漫画模式 (没有文字流), 切小说模式的入口在 ComicActivity 菜单与 MainActivity 长按菜单里都禁掉了
- 两种格式的索引共用 epub_index 表: epub 索引是 `{"v":2,...}`, cbz 是 `{"v":1,"kind":"cbz",...}`, 靠 kind 区分
- `WebdavResource` 处理 multipart range 响应; 注意服务器可能合并相邻 range (RFC 7233),
  分段必须按覆盖关系分配 (`assignParts`), 不能按 offset 精确配对
- OPDS 漫画不走整本随机读, 走服务端页流 (OPDS-PSE): feed 的 entry 里给了 `pse:count` 和带 {pageNumber}
  的模板链接时, `Books.open` 返回 `OpdsBook` (完全不经 LazyZip), 按页号取单张图; 封面用 feed 里的
  thumbnail (几 KB, 不必为列表上一张小图开整本). 这类书不需要索引, IndexCrawler 用 `Books.streamed` 跳过,
  不然"重建索引"会为每本拉一次整本.
  **坑**: Komga 的下载端点压根不认 Range —— 实测带 Range 的请求照样回 200 + 完整文件 (单段/multi-range 都试过),
  LazyZip 的按需读在它上面退化成"每读一小段拉一次整本" (一本 62MB 的全彩漫画, 翻每页都是 62MB, 走 PSE 后单页 39KB).
  PSE 页号按规范是 0..N-1, **别写成 index+1**: Komga 上首页会错位成第二页, 翻到末页直接 400.
  文字 epub 服务端不给 PSE link (硬打 pages 端点返回 500), 所以小说模式照走 LazyEpub —— NovelActivity
  专门用 `Books.openText`, 不能让页流顶替文字流 (那边给的是图片, 字号/夜间/重排全废)
- 本地数据源只能用 `DirPicker` 选目录 (存储卷列表 → 逐级进目录), 不给手敲路径的入口: 遥控器打字太痛苦
- 局域网扫描 (`LanScanDialog`) 挂在 WebDAV/SMB/OPDS 三个添加弹窗的"扫描局域网"按钮上, 不是全局设置:
  填一个端口 → 扫本机 /24 网段 254 个 IP → 选中的 IP 回填进地址框 (webdav 默认 5244 补 /dav,
  opds 默认 25600 并按端口补 Komga/Kavita/Calibre-Web 的目录路径, smb 只回填 IP).
  做成配置项被否过一次: 扫描是"不知道服务器 IP"时的一次性辅助, 不是要长期维护的设置
- 阅读界面: `NovelActivity` (WebView 翻章) / `ComicActivity` (左右双栏), 文件列表菜单键/长按OK切换两种模式;
  两个 Activity 用 `Theme.Malguem.Reader` (windowFullscreen) + `Fullscreen.apply` 藏掉系统栏,
  阅读菜单关掉会让窗口重新获焦, 所以 onWindowFocusChanged 里要再藏一次
- 两个阅读界面的 WebView 都用 `file:///android_asset/` 当 baseUrl (工程里根本没有 assets 目录, 纯历史占位),
  于是页面里的相对引用解析出来的 path 会带上这一段, `shouldInterceptRequest` 收到的是
  `/android_asset/cbz-page/0.jpg` —— 资源请求一律先过 `Books.webPath` 把它剥掉.
  **坑**: epub 长期没事只是走运, 它内部惯用 `../images/x.jpg`, 那个 `..` 恰好把 android_asset 抵消了;
  不带 `..` 的引用 (cbz 每一页、OPDS 页流每一页、和图片同级的 xhtml) 会整本白屏.
  v1.11.0 的 OPDS 页流就栽在这: 页码正常 (总页数来自 pse:count), 图一张都出不来
- 阅读中 OK/菜单键呼出阅读菜单: 目录跳转(`LazyEpub` 解析 ncx/nav, 存进 epub_index, 索引版本 v2) /
  SeekBar 跳页 / 小说字号+夜间(SharedPreferences 全局) / 漫画阅读方向rtl+单页(epub 表按书存) / 互切模式(顶替 Activity)
- 小说章内翻页靠 CSS 多列 + transform 平移 (`PAGER_INIT`/`PAGER_GO`): body 设 columnWidth + height:100vh
  排成多列, 翻页把 body 整体 translateX 一个 step (不用 scrollLeft —— 根元素 overflow:hidden 后有的 WebView 无视它).
  **坑**: 只有根元素能设 overflow:hidden, **body 绝不能设** —— 第二列往后全在 body 的溢出区里,
  一旦 hidden 就被裁掉, 而翻页移进视口的正是这些列, 表现为第一页正常、往后每页全白 (v1.10.0~v1.11.1 都有).
  这类纯 CSS/JS 的毛病别靠推理, 拿 Chrome DevTools 跑一遍就能复现 (WebView 同是 Blink 内核)
- 小说章内进度 page_offset 存万分比而非像素 (字号/夜间重排后按比例恢复); MainActivity 用 onResume 刷新列表
- 首页最前排是"最近阅读" (`epub.last_read` 倒序, `RecentEpub` 类型条目自带 namespace, 点开直接续读)
- release 开了 R8: Gson 反射模型(epub/cbz 索引、漫画布局)和 jcifs 的 keep 规则在 proguard-rules.pro.
  **坑**: `-keepclassmembers` 防不住 full mode 的 field value propagation —— 字段只被读、从不被写(写入全靠 Gson 反射)时,
  R8 判定它恒为 null 并把字段整个删掉, 编译期零警告. OTA 的 version.json 模型就这么被吃掉, release 包里 tag 永远是 null,
  所有更新源都报"内容不对", 而 debug 包一切正常 (只有 release 才跑 R8, 所以本地怎么测都没事).
  在 v1.8.0 的 CI 包上实测确认: dex 里压根没有 tag 字段; 这个模型从 OTA 落地起就没变过, 早期版本大概率同病.
  已改成 `org.json` 手解. 新增纯反序列化模型: 要么 `-keep class X { <fields>; }`, 要么手解, 且必须用 release 包实测
