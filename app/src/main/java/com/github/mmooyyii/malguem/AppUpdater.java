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

    // 依次尝试的源, 每项都以 releases/latest/download/ 结尾; 镜像失效在这里换域名即可
    private static final String[] SOURCES = {
            "https://github.com/mmooyyii/malguem/releases/latest/download/",
            "https://gh-proxy.com/https://github.com/mmooyyii/malguem/releases/latest/download/",
            "https://ghproxy.net/https://github.com/mmooyyii/malguem/releases/latest/download/",
    };
    // 自动检查按时间节流而不是每进程一次: 电视上 app 用 HOME 退出时进程常驻,
    // "每进程一次"会导致装完后再也不检查 (v1.6.0 -> v1.7.0 自动更新失灵的原因)
    private static final long CHECK_INTERVAL_MS = 6 * 3600_000L;
    private static long lastCheckAt = 0;

    private final AppCompatActivity activity;
    // 连接超时压短: 直连被墙时通常卡在握手, 尽快失败切到下一个源
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> unknownSourceLauncher;
    private File apkFile; // 已下载待安装的 apk
    private int goodSource = 0; // 检查阶段验证过可用的源, 下载从它开始试

    // version.json 的内容: {"tag": "v1.2"}
    private static class Manifest {
        String tag;
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

    // 启动/回到前台时静默检查 (6 小时内不重复), 只有发现新版本才打扰用户
    public void checkOnLaunch() {
        var now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckAt = now;
        new Thread(() -> {
            var manifest = fetchManifest();
            if (manifest == null) {
                return; // 所有源都失败: 静默, 下次启动再试
            }
            var current = currentVersion();
            if (!manifest.tag.equals(current)) {
                main.post(() -> askAndDownload(manifest.tag, current));
            }
        }).start();
    }

    // 帮助对话框里的手动检查: 无论结果如何都给出反馈
    public void checkManually() {
        Toast.makeText(activity, R.string.checking_update, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            var manifest = fetchManifest();
            var current = currentVersion();
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                if (manifest == null) {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show();
                } else if (manifest.tag.equals(current)) {
                    Toast.makeText(activity, activity.getString(R.string.already_latest, current), Toast.LENGTH_SHORT).show();
                } else {
                    askAndDownload(manifest.tag, current);
                }
            });
        }).start();
    }

    // 依次尝试各源拉取 version.json, 成功的源记入 goodSource 供下载复用; 全失败返回 null
    private Manifest fetchManifest() {
        for (var i = 0; i < SOURCES.length; i++) {
            try {
                var request = new Request.Builder().url(SOURCES[i] + "version.json").build();
                try (var response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        continue;
                    }
                    var manifest = new Gson().fromJson(response.body().string(), Manifest.class);
                    if (manifest == null || manifest.tag == null) {
                        continue;
                    }
                    goodSource = i;
                    return manifest;
                }
            } catch (Exception ignore) {
                // 这个源不通 (超时/被墙/返回错误页), 换下一个
            }
        }
        return null;
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
            Exception last = null;
            // 从检查阶段验证过的源开始, 失败换下一个
            for (var step = 0; step < SOURCES.length; step++) {
                var base = SOURCES[(goodSource + step) % SOURCES.length];
                try {
                    downloadFrom(base, tag, dialog);
                    return;
                } catch (Exception e) {
                    last = e;
                }
            }
            final var err = last;
            main.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                Toast.makeText(activity, activity.getString(R.string.download_failed,
                        err == null ? "" : Errors.describe(activity, err)), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    // 从单个源下载到 cache/updates, 校验完整性, 失败抛异常由调用方换源重试
    private void downloadFrom(String base, String tag, AlertDialog dialog) throws Exception {
        var request = new Request.Builder().url(base + "malguem-tv.apk").build();
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("http " + response.code());
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
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        long percent = done * 100 / total;
                        if (percent != lastShown) {
                            lastShown = percent;
                            final var p = percent;
                            main.post(() -> dialog.setMessage(p + "%"));
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
