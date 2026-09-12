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

import com.google.gson.Gson;

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

    // version.json 的内容: {"tag": "v1.2"}
    private static class Manifest {
        String tag;
    }

    // 逐源尝试的结果: manifest 非空表示拿到了版本号; 否则 failures 按源记下断在哪一环.
    // 全灭时把每行原因摆给用户看, 不然只有一句"所有源都不可用", 没法判断是网络/DNS 还是资产没发上去
    private static class CheckResult {
        Manifest manifest;
        final java.util.List<String> failures = new java.util.ArrayList<>();
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
                if (result.manifest == null) {
                    showFailures(R.string.update_check_failed, result.failures);
                } else if (result.manifest.tag.equals(current)) {
                    Toast.makeText(activity, activity.getString(R.string.already_latest, current), Toast.LENGTH_SHORT).show();
                } else {
                    askAndDownload(result.manifest.tag, current);
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
                    var manifest = new Gson().fromJson(response.body().string(), Manifest.class);
                    if (manifest == null || manifest.tag == null) {
                        // 200 但内容不对: 多半是镜像/热点门户返回了自己的页面
                        result.failures.add(hostOf(base) + " — " + activity.getString(R.string.diag_bad_json));
                        continue;
                    }
                    goodSource = i;
                    result.manifest = manifest;
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
            // 从检查阶段验证过的源开始, 失败换下一个; 和检查一样逐源记原因
            var failures = new java.util.ArrayList<String>();
            var list = sources();
            for (var step = 0; step < list.size(); step++) {
                var base = list.get((goodSource + step) % list.size());
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
