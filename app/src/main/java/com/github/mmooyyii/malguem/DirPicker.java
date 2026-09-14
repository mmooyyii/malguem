package com.github.mmooyyii.malguem;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

// 遥控器友好的目录选择器: 只有上下键 + OK 就能选出一个目录, 不用让主人在电视上敲路径.
// 起点是"存储列表"(内置存储 + 各个 U 盘/SD 卡), 往下逐级进目录, 选中哪层就用哪层当数据源根.
public class DirPicker {

    public interface OnPicked {
        void onPicked(String path);
    }

    private final Context ctx;
    private final OnPicked callback;
    private final List<File> volumes; // 各存储卷根目录, 同时充当"往上到头"的判据
    private final List<File> entries = new ArrayList<>(); // 与列表项一一对应, null 表示".."
    private final List<String> labels = new ArrayList<>();

    private File current; // null = 停在存储列表这一层
    private AlertDialog dialog;
    private TextView pathView;
    private ArrayAdapter<String> adapter;

    private DirPicker(Context ctx, OnPicked callback) {
        this.ctx = ctx;
        this.callback = callback;
        this.volumes = storageVolumes(ctx);
    }

    // startPath: 编辑数据源时传原路径, 直接从那层开始; 为空则从存储列表开始
    public static void show(Context ctx, String startPath, OnPicked callback) {
        var picker = new DirPicker(ctx, callback);
        if (startPath != null && !startPath.isEmpty()) {
            var f = new File(startPath);
            if (f.isDirectory()) {
                picker.current = f;
            }
        }
        picker.build();
    }

    private void build() {
        var view = LayoutInflater.from(ctx).inflate(R.layout.dialog_dirpicker, null);
        pathView = view.findViewById(R.id.dirPath);
        ListView list = view.findViewById(R.id.dirList);
        adapter = new ArrayAdapter<>(ctx, R.layout.item_dir, R.id.dirName, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            var target = entries.get(position);
            if (target == null) {
                current = parentOf(current);
            } else {
                current = target;
            }
            refresh();
        });
        dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.pick_dir_title)
                .setView(view)
                .setPositiveButton(R.string.pick_dir_ok, (d, which) -> {
                    if (current != null) {
                        callback.onPicked(current.getAbsolutePath());
                    }
                })
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .create();
        dialog.show(); // 按钮 show 之后才存在, refresh 里要按当前层开关"选此目录"
        refresh();
        list.requestFocus();
    }

    private void refresh() {
        entries.clear();
        labels.clear();
        if (current == null) {
            pathView.setText(R.string.pick_dir_volumes);
            for (var v : volumes) {
                entries.add(v);
                labels.add(volumeLabel(v) + "   " + v.getAbsolutePath());
            }
            if (volumes.isEmpty()) {
                entries.add(new File("/storage"));
                labels.add("/storage");
            }
        } else {
            pathView.setText(current.getAbsolutePath());
            entries.add(null);
            labels.add(ctx.getString(R.string.pick_dir_up));
            var children = current.listFiles(File::isDirectory);
            if (children == null) {
                labels.add(ctx.getString(R.string.pick_dir_denied));
                entries.add(null); // 提示行点了当上级用, 免得卡死在读不了的目录里
            } else {
                Arrays.sort(children, Comparator.comparing(f -> f.getName().toLowerCase()));
                for (var c : children) {
                    entries.add(c);
                    labels.add(c.getName());
                }
            }
        }
        adapter.notifyDataSetChanged();
        // 选到哪层就存哪层, 存储列表这层没有"此目录"可选
        if (dialog != null) {
            var ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setEnabled(current != null);
            }
        }
    }

    // 上级: 卷根再往上就回存储列表, 不让主人爬到 / 底下那些读不了的系统目录里
    private File parentOf(File f) {
        if (f == null) {
            return null;
        }
        for (var v : volumes) {
            if (v.getAbsolutePath().equals(f.getAbsolutePath())) {
                return null;
            }
        }
        return f.getParentFile();
    }

    private String volumeLabel(File v) {
        var internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.getAbsolutePath().equals(v.getAbsolutePath())) {
            return ctx.getString(R.string.pick_dir_internal);
        }
        return ctx.getString(R.string.pick_dir_external);
    }

    // 存储卷: 内置存储 + getExternalFilesDirs 里那些外置卷 (回溯掉 /Android/data/<pkg>/files 四级就是卷根).
    // TV 上插的 U 盘/移动硬盘走的就是后者, 系统文件选择器在 TV 上基本不可用, 只能自己列.
    private static List<File> storageVolumes(Context ctx) {
        var out = new ArrayList<File>();
        var internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.isDirectory()) {
            out.add(internal);
        }
        for (var dir : ctx.getExternalFilesDirs(null)) {
            if (dir == null) {
                continue;
            }
            var root = dir;
            for (int i = 0; i < 4 && root != null; i++) {
                root = root.getParentFile();
            }
            if (root == null || !root.isDirectory()) {
                continue;
            }
            boolean dup = false;
            for (var v : out) {
                if (v.getAbsolutePath().equals(root.getAbsolutePath())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(root);
            }
        }
        return out;
    }
}
