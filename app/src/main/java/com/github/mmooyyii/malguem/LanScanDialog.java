package com.github.mmooyyii.malguem;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.Toast;

// 添加数据源时的"扫描局域网": 填一个端口, 扫本机所在 /24 网段的 254 个 IP, 选中的 IP 回填进地址框.
// 挂在各个添加弹窗里而不是做成全局设置 —— 扫描是"我不知道服务器 IP"时的辅助, 不是要长期维护的配置
public class LanScanDialog {

    public interface OnPicked {
        void onPicked(String ip, int port);
    }

    public static void show(Activity act, int defaultPort, OnPicked cb) {
        var view = LayoutInflater.from(act).inflate(R.layout.dialog_scan, null);
        EditText etPort = view.findViewById(R.id.et_scan_port);
        etPort.setText(String.valueOf(defaultPort));
        new AlertDialog.Builder(act)
                .setTitle(R.string.scan_lan_title)
                .setView(view)
                .setPositiveButton(R.string.scan_start, (d, which) -> {
                    int port;
                    try {
                        port = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (Exception e) {
                        port = 0;
                    }
                    if (port < 1 || port > 65535) {
                        Toast.makeText(act, R.string.scan_port_bad, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    start(act, port, cb);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void start(Activity act, int port, OnPicked cb) {
        var ip = LanScanner.localIp();
        if (ip == null) {
            Toast.makeText(act, R.string.no_lan_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        var subnet = ip.substring(0, ip.lastIndexOf('.'));
        var progress = new AlertDialog.Builder(act)
                .setTitle(R.string.scan_lan_title)
                .setMessage(act.getString(R.string.scanning, subnet, String.valueOf(port)))
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .create();
        progress.show();
        new Thread(() -> {
            var hits = LanScanner.scan(ip, port);
            act.runOnUiThread(() -> {
                if (act.isDestroyed() || !progress.isShowing()) {
                    return; // 已取消或界面已销毁, 丢弃结果
                }
                progress.dismiss();
                if (hits.isEmpty()) {
                    Toast.makeText(act, R.string.scan_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(act)
                        .setTitle(R.string.scan_found)
                        .setItems(hits.toArray(new String[0]),
                                (d, which) -> cb.onPicked(hits.get(which), port))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }, "lan-scanner").start();
    }
}
