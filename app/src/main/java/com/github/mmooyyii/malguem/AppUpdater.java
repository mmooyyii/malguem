package com.github.mmooyyii.malguem;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

// 应用内 OTA 更新: 启动时检查新版本, 确认后下载 apk 拉起系统安装器, 免去手动往电视传包.
// 检查与下载都走 GitHub 的固定重定向 URL releases/latest/download/<固定文件名>,
// 不依赖 api.github.com (多数 ghproxy 反代不放行它), 因此整条链路可以套镜像;
// 源按顺序尝试, 直连不通自动落到镜像. CI 每次发版会上传固定名字的
// version.json (含 tag) 与 malguem-tv.apk, 见 release.yml.
public class AppUpdater {

    // 依次尝试的源, CI 发版时把同一份资产同时发到 GitHub Pages 和 release 分支(jsDelivr 回源):
    // 大陆无代理时 github 直连和 ghproxy 镜像经常全灭, Pages 与 jsDelivr 的 gcore/testingcf 域通常有一个能通.
    // 另支持自定义源 (长按首页"检查更新"设置, http://user:pass@host/path/ 内联凭据), 排在所有内置源之前
    private static final String[] SOURCES = {
            "https://mmooyyii.github.io/malguem/",
            "https://gcore.jsdelivr.net/gh/mmooyyii/malguem@release/",
            "https://testingcf.jsdelivr.net/gh/mmooyyii/malguem@release/",
            "https://fastly.jsdelivr.net/gh/mmooyyii/malguem@release/",
            "https://github.com/mmooyyii/malguem/releases/latest/download/",
            "https://gh-proxy.com/https://github.com/mmooyyii/malguem/releases/latest/download/",
            "https://ghproxy.net/https://github.com/mmooyyii/malguem/releases/latest/download/",
    };

    // 下载前赛马时每个源试拉的字节数: 太小会被握手/RTT 主导, 看不出真实带宽; 太大白费流量
    private static final int PROBE_BYTES = 256 * 1024;
    // 赛马总时长上限: 全都慢的时候别一直耗着, 超时就按老办法逐个试
    private static final int PROBE_TIMEOUT_SEC = 8;

    private final AppCompatActivity activity;
    // 连接超时压短: 直连被墙时通常卡在握手, 尽快失败切到下一个源
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> unknownSourceLauncher;
    private File apkFile; // 已下载待安装的 apk
    // 检查阶段验证过可用的源, 下载从它开始试; 检查与下载在不同线程, 要 volatile 保证可见
    private volatile int goodSource = 0;

    // 逐源尝试的结果: tag 非空表示拿到了版本号; 否则 failures 按源记下断在哪一环.
    // 全灭时把每行原因摆给用户看, 不然只有一句"所有源都不可用", 没法判断是网络/DNS 还是资产没发上去
    private static class CheckResult {
        String tag;
        final java.util.List<String> failures = new java.util.ArrayList<>();
    }

    // version.json 就一个字段 {"tag":"v1.2"}, 手解掉, 不碰 Gson 反射.
    // 血的教训: 之前用 Gson 映射到一个只有 String tag 的模型, 而这个字段只被读、从不被写
    // (写入是反序列化干的), R8 full mode 的 field value propagation 就判定它恒为 null,
    // 把读取换成常量并把字段整个删了 —— -keepclassmembers 拦不住这类优化.
    // 结果 release 包里 tag 永远是 null, 每个源都报"内容不是 version.json", OTA 从未真正可用过.
    private static String parseTag(String body) {
        try {
            var tag = new org.json.JSONObject(body).optString("tag", "");
            return tag.isEmpty() ? null : tag;
        } catch (Exception e) {
            return null; // 压根不是 json (门户劫持/错误页)
        }
    }

    // 内容不对时附上响应开头, 一眼能看出是 HTML 门户页还是别的什么
    private static String preview(String body) {
        var s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // 必须在 Activity onCreate 期间构造 (registerForActivityResult 的限制)
    public AppUpdater(AppCompatActivity activity) {
        this.activity = activity;
        // 从"安装未知应用"授权页返回后, 若已授权则继续安装刚下载好的 apk
        unknownSourceLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (apkFile != null && activity.getPackageManager().canRequestPackageInstalls()) {
                        launchInstaller();
                    }
                });
    }

    // 手动检查 (首页按钮/帮助对话框): 无论结果如何都给出反馈; 不做任何隐式自动检查
    public void checkManually() {
        Toast.makeText(activity, R.string.checking_update, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            var result = fetchManifest();
            var current = currentVersion();
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                if (result.tag == null) {
                    showFailures(R.string.update_check_failed, result.failures);
                } else if (result.tag.equals(current)) {
                    Toast.makeText(activity, activity.getString(R.string.already_latest, current), Toast.LENGTH_SHORT).show();
                } else {
                    askAndDownload(result.tag, current);
                }
            });
        }).start();
    }

    // 全部源失败时逐行列出 "主机名 — 原因", 电视上直接能看出是哪一环断的
    private void showFailures(int titleRes, java.util.List<String> failures) {
        var sb = new StringBuilder();
        for (var f : failures) {
            sb.append(f).append('\n');
        }
        sb.append('\n').append(activity.getString(R.string.diag_hint));
        new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.close, (d, w) -> d.dismiss())
                .show();
    }

    // 诊断行里只放主机名, 电视屏幕摆不下完整 URL (gh-proxy 那种更是长得离谱)
    private static String hostOf(String base) {
        var hu = okhttp3.HttpUrl.parse(base);
        return hu == null ? base : hu.host();
    }

    // 自定义源在前 + 内置源; 自定义源存 SharedPreferences, 空则只有内置
    private java.util.List<String> sources() {
        var list = new java.util.ArrayList<String>();
        var custom = activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getString("update_source", "").trim();
        if (!custom.isEmpty()) {
            list.add(custom.endsWith("/") ? custom : custom + "/");
        }
        java.util.Collections.addAll(list, SOURCES);
        return list;
    }

    // 支持 URL 内联凭据 (http://user:pass@host/...): okhttp 不会自动发 Basic 头, 这里拆出来自己加
    private Request buildGet(String url) {
        var b = new Request.Builder();
        var hu = okhttp3.HttpUrl.parse(url);
        if (hu != null && !hu.username().isEmpty()) {
            b.addHeader("Authorization", okhttp3.Credentials.basic(hu.username(), hu.password()));
            b.url(hu.newBuilder().username("").password("").build());
        } else {
            b.url(url);
        }
        return b.build();
    }

    // 依次尝试各源拉取 version.json, 成功的源记入 goodSource 供下载复用;
    // 失败的源逐个记下原因 —— 以前这里把异常全吞了, 出问题只能靠猜
    private CheckResult fetchManifest() {
        var result = new CheckResult();
        var list = sources();
        for (var i = 0; i < list.size(); i++) {
            var base = list.get(i);
            try {
                var request = buildGet(base + "version.json");
                try (var response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        // 抛出来让 Errors.diagnose 统一翻译成 "HTTP 404" 这类可读原因
                        throw new HttpStatusException(response.code(), "version.json");
                    }
                    var body = response.body().string();
                    var tag = parseTag(body);
                    if (tag == null) {
                        // 200 但内容不对: 多半是镜像/热点门户返回了自己的页面
                        result.failures.add(hostOf(base) + " — "
                                + activity.getString(R.string.diag_bad_json) + " [" + preview(body) + "]");
                        continue;
                    }
                    goodSource = i;
                    result.tag = tag;
                    return result;
                }
            } catch (Exception e) {
                result.failures.add(hostOf(base) + " — " + Errors.diagnose(activity, e));
            }
        }
        return result;
    }

    private String currentVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void askAndDownload(String latest, String current) {
        if (activity.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.new_version, latest))
                .setMessage(activity.getString(R.string.update_ask, current))
                .setPositiveButton(R.string.update_now, (d, w) -> download(latest))
                .setNegativeButton(R.string.update_later, (d, w) -> d.dismiss())
                .show();
    }

    private void download(String tag) {
        var dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.downloading, tag))
                .setMessage("0%")
                .setCancelable(false)
                .create();
        dialog.show();
        new Thread(() -> {
            // 先赛马挑一个真正快的源; 挑不出来(全都没跑通)再退回原来的办法:
            // 从检查阶段验证过的源开始逐个试. 两种情况都是失败就换下一个, 并逐源记原因
            var failures = new java.util.ArrayList<String>();
            var list = sources();
            main.post(() -> dialog.setMessage(activity.getString(R.string.picking_source)));
            var fastest = pickFastest(list);
            var order = new java.util.ArrayList<String>();
            if (fastest == null) {
                for (var step = 0; step < list.size(); step++) {
                    order.add(list.get((goodSource + step) % list.size()));
                }
            } else {
                order.add(fastest);
                for (var base : list) {
                    if (!base.equals(fastest)) {
                        order.add(base); // 赢家万一中途掉链子, 后面还有得换
                    }
                }
            }
            for (var base : order) {
                try {
                    downloadFrom(base, tag, dialog);
                    return;
                } catch (Exception e) {
                    failures.add(hostOf(base) + " — " + Errors.diagnose(activity, e));
                }
            }
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                showFailures(R.string.download_failed_title, failures);
            });
        }).start();
    }

    // 下载前赛马: 并发让每个源各拉 apk 开头一小段, 谁先拉完就用谁.
    // 原来是"第一个连得上的源用到底", 可 version.json 才几十字节, 再慢的源也秒回, 到了几 MB 的
    // apk 上才原形毕露. 顺带在这里就淘汰掉返回 HTML 错误页的镜像, 不用等整包下完被 looksLikeApk 判死.
    // 全都没跑通时返回 null, 由调用方退回原来的逐个尝试
    private String pickFastest(java.util.List<String> list) {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(list.size(), 8));
        var race = new java.util.concurrent.ExecutorCompletionService<String>(pool);
        for (var base : list) {
            race.submit(() -> probe(base));
        }
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SEC);
            for (var i = 0; i < list.size(); i++) {
                var wait = deadline - System.nanoTime();
                if (wait <= 0) {
                    break;
                }
                var done = race.poll(wait, TimeUnit.NANOSECONDS);
                if (done == null) {
                    break; // 到点了, 剩下的不等
                }
                try {
                    return done.get(); // 最先把这一小段拉完的就是最快的
                } catch (Exception ignore) {
                    // 这个源没跑通, 接着等下一个完成
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow(); // 已经选出赢家, 其余探测连接一并放弃
        }
        return null;
    }

    // 拉 apk 开头 PROBE_BYTES 字节并确认是 zip 头, 通过则返回这个源本身
    private String probe(String base) throws Exception {
        var request = buildGet(base + "malguem-tv.apk").newBuilder()
                .header("Range", "bytes=0-" + (PROBE_BYTES - 1))
                .build();
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new HttpStatusException(response.code(), "malguem-tv.apk");
            }
            var head = new byte[4];
            var filled = 0;
            var got = 0;
            var buf = new byte[16 * 1024];
            int n;
            try (var in = response.body().byteStream()) {
                // 不认 Range 的源会直接给整个 apk, 读够这一小段就断开, 别把整包拖下来
                while (got < PROBE_BYTES && (n = in.read(buf)) != -1) {
                    for (var i = 0; i < n && filled < head.length; i++) {
                        head[filled++] = buf[i];
                    }
                    got += n;
                }
            }
            // apk 是 zip, 以 PK\x03\x04 开头; 错误页/门户劫持在这里就出局
            if (filled < head.length || head[0] != 0x50 || head[1] != 0x4B
                    || head[2] != 0x03 || head[3] != 0x04) {
                throw new IllegalStateException("内容不是 apk (镜像可能返回了错误页)");
            }
            return base;
        }
    }

    // 从单个源下载到 cache/updates, 校验完整性, 失败抛异常由调用方换源重试
    private void downloadFrom(String base, String tag, AlertDialog dialog) throws Exception {
        var request = buildGet(base + "malguem-tv.apk");
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new HttpStatusException(response.code(), "malguem-tv.apk");
            }
            var dir = new File(activity.getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            var out = new File(dir, "malguem-" + tag + ".apk");
            long total = response.body().contentLength();
            long done = 0;
            try (var in = response.body().byteStream(); var fos = new FileOutputStream(out)) {
                var buf = new byte[64 * 1024];
                long lastShown = -1;
                int n;
                // 必须判 != -1: read 返回 0 是合法的, 用 > 0 会把下载悄悄截断成半个包
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        long percent = done * 100 / total;
                        if (percent != lastShown) {
                            lastShown = percent;
                            final var p = percent;
                            main.post(() -> dialog.setMessage(p + "%"));
                        }
                    } else {
                        // 没有 Content-Length (chunked / 透明解压) 时退化成显示已下载量, 否则一直卡在 0%
                        long mb = done / (1024 * 1024);
                        if (mb != lastShown) {
                            lastShown = mb;
                            final var m = mb;
                            main.post(() -> dialog.setMessage(m + " MB"));
                        }
                    }
                }
            }
            if (total > 0 && done != total) {
                throw new IllegalStateException("下载不完整 " + done + "/" + total);
            }
            if (!looksLikeApk(out)) {
                throw new IllegalStateException("内容不是 apk (镜像可能返回了错误页)");
            }
            verifyVersion(out, tag);
            apkFile = out;
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                install();
            });
        }
    }

    // 下完还要确认这个包真是 version.json 说的那一版, 只验"是不是 zip"不够.
    // jsDelivr 这类 CDN 按文件各自缓存, @release 又是分支引用 (分支缓存 12 小时), 出现过
    // 18 字节的 version.json 已经刷新成新版、2MB 的 apk 还停在上一版的情况: 客户端于是
    // "检测到新版本 -> 下回来一个旧包 -> 装完版本没变", 用户看到的就是"升级失败".
    // 版本对不上就抛异常, 由调用方换下一个源 —— 和其他失败一样的处理
    private void verifyVersion(File apk, String tag) throws Exception {
        var info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null) {
            throw new IllegalStateException("apk 解析不了 (下载可能损坏)");
        }
        // versionName 由 CI 按 tag 名注入, 两边应当逐字相同
        if (!tag.equals(info.versionName)) {
            throw new IllegalStateException("版本对不上: 要 " + tag + ", 这个源给的是 " + info.versionName);
        }
    }

    // apk 是 zip, 以 PK\x03\x04 开头; 防止把镜像站的 HTML 错误页当安装包装进去
    private static boolean looksLikeApk(File f) {
        try (var in = new FileInputStream(f)) {
            var head = new byte[4];
            return in.read(head) == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        } catch (Exception e) {
            return false;
        }
    }

    private void install() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            // 首次更新需要用户授权"安装未知应用", 授权页返回后由 unknownSourceLauncher 回调继续
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.install_perm_title)
                    .setMessage(R.string.install_perm_msg)
                    .setPositiveButton(R.string.grant, (d, w) -> unknownSourceLauncher.launch(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                    .show();
            return;
        }
        launchInstaller();
    }

    private void launchInstaller() {
        var uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apkFile);
        var intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }
}
