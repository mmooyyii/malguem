package com.github.mmooyyii.malguem;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {

    private FileListAdapter fileListAdapter;

    ResourceInterface client;
    int current_resource_id;
    List<String> pwd;
    boolean at_root_list = false;
    long kill_app_countdown = 0;
    private ActivityResultLauncher<Intent> launcher;
    private AppUpdater updater;
    // 目录拉取共用一个后台线程, 避免每次 FetchFileListTask 新建一个从不 shutdown 的 executor 泄漏线程
    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setup_file_list();
        pwd = new ArrayList<>();
        launcher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 从首页"最近阅读"直接开书时没有进入任何目录, 返回后刷新首页而不是去 ls (此时 client 可能为 null)
                    if (at_root_list) {
                        init_resource_list();
                    } else {
                        new FetchFileListTask().executeTask();
                    }
                });
        updater = new AppUpdater(this);
        updater.checkOnLaunch();
        // 延迟启动后台索引爬取, 避开首屏封面加载抢网络
        new Handler(Looper.getMainLooper()).postDelayed(() -> IndexCrawler.start(this), 8000);
    }

    public void setup_file_list() {
        RecyclerView fileListView = findViewById(R.id.fileListView);
        fileListView.setLayoutManager(new GridLayoutManager(this, 5));
        fileListView.setItemAnimator(null);
        fileListView.setHasFixedSize(true);
        fileListAdapter = new FileListAdapter(this);
        fileListView.setAdapter(fileListAdapter);
        fileListAdapter.setOnItemAction(this::onFileClicked);
        init_resource_list();
    }

    private void onFileClicked(ListItem file) {
        switch (file.type) {
            case AddWebDav: {
                showAddChooser();
                break;
            }
            case Resource: {
                var db = Database.getInstance(this).getDatabase();
                client = db.get_resource(file.id);
                current_resource_id = file.id;
                if (client == null) {
                    android.widget.Toast.makeText(MainActivity.this, "数据库异常", Toast.LENGTH_SHORT).show();
                } else {
                    new FetchFileListTask().executeTask();
                }
                break;
            }
            case Dir: {
                pwd.add(file.name);
                new FetchFileListTask().executeTask();
                break;
            }
            case Epub: {
                var db = Database.getInstance(this).getDatabase();
                var info = db.get_epub_info(file.id, make_uri(file.name));
                Intent intent;
                if (info.view_type == ListItem.ViewType.Comic) {
                    intent = new Intent(MainActivity.this, ComicActivity.class);
                } else {
                    intent = new Intent(MainActivity.this, NovelActivity.class);
                }
                intent.putExtra("resource_id", file.id);
                intent.putExtra("book_uri", make_uri(file.name));
                intent.putExtra("client", client.to_json());

                launcher.launch(intent);
                break;
            }
            case RecentEpub: {
                // 首页"最近阅读"直接开书, 数据源配置从库里取, 不经过目录浏览
                var db = Database.getInstance(this).getDatabase();
                var c = db.get_resource(file.id);
                if (c == null) {
                    Toast.makeText(MainActivity.this, "数据源已被删除", Toast.LENGTH_SHORT).show();
                    break;
                }
                Intent intent;
                if (file.view_type == ListItem.ViewType.Comic) {
                    intent = new Intent(MainActivity.this, ComicActivity.class);
                } else {
                    intent = new Intent(MainActivity.this, NovelActivity.class);
                }
                intent.putExtra("resource_id", file.id);
                intent.putExtra("book_uri", file.uri);
                intent.putExtra("client", c.to_json());
                launcher.launch(intent);
                break;
            }
        }
    }

    private void showDeleteConfirmationDialog(int id) {
        // 创建 AlertDialog.Builder 对象
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // 设置对话框标题
        builder.setTitle("确认删除");

        // 设置确认按钮及其点击事件
        builder.setPositiveButton("删除", (dialog, which) -> {
            // 处理删除操作，这里简单地显示一个 Toast 消息
            var db = Database.getInstance(MainActivity.this).getDatabase();
            db.delete_resource(id);
            Toast.makeText(MainActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss(); // 关闭对话框
            init_resource_list();
        });
        // 设置取消按钮及其点击事件
        builder.setNegativeButton("取消", (dialog, which) -> {
            // 取消操作，关闭对话框
            dialog.dismiss();
        });

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // 新增数据源: 先选类型, 再进对应配置弹窗
    private void showAddChooser() {
        String[] types = {"WebDAV", "SMB", "本地硬盘"};
        new AlertDialog.Builder(this)
                .setTitle("选择数据源类型")
                .setItems(types, (dialog, which) -> {
                    if (which == 0) {
                        showWebdavDialog();
                    } else if (which == 1) {
                        showSmbDialog();
                    } else {
                        showLocalDialog();
                    }
                })
                .show();
    }

    private void showWebdavDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_login, null);
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        final EditText etUrl = dialogView.findViewById(R.id.et_url);
        new AlertDialog.Builder(this)
                .setTitle("添加 WebDAV")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String username = etUsername.getText().toString();
                    String password = etPassword.getText().toString();
                    String url = etUrl.getText().toString();
                    var r = new WebdavResource(url, username, password);
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    db.add_resource(url, 1, r.to_json());
                    Toast.makeText(MainActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showSmbDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_smb, null);
        final EditText etHost = dialogView.findViewById(R.id.et_host);
        final EditText etShare = dialogView.findViewById(R.id.et_share);
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        new AlertDialog.Builder(this)
                .setTitle("添加 SMB")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String host = etHost.getText().toString().trim();
                    String share = etShare.getText().toString().trim();
                    String user = etUsername.getText().toString();
                    String pass = etPassword.getText().toString();
                    var r = new SmbResource(host, share, user, pass, "");
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    db.add_resource("smb://" + host + "/" + share, 2, r.to_json());
                    Toast.makeText(MainActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showLocalDialog() {
        ensureStoragePermission();
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_local, null);
        final EditText etPath = dialogView.findViewById(R.id.et_path);
        new AlertDialog.Builder(this)
                .setTitle("添加本地目录")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String path = etPath.getText().toString().trim();
                    var r = new LocalResource(path);
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    db.add_resource(path, 3, r.to_json());
                    Toast.makeText(MainActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    // 本地硬盘读取需要存储权限: R+ 走"所有文件访问", 以下走运行时 READ_EXTERNAL_STORAGE
    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }
    }


    public void init_resource_list() {
        at_root_list = true;
        var db = Database.getInstance(this).getDatabase();
        // 最近阅读放最前面 (一行 5 个), 点开即续读
        var list = new ArrayList<>(db.recent_books(5));
        list.addAll(db.resource_list());
        list.add(new ListItem(0, "添加数据源", ListItem.FileType.AddWebDav));
        fileListAdapter.setClient(null);
        fileListAdapter.setItems(list);
    }

    @Override
    protected void onDestroy() {
        fetchExecutor.shutdownNow();
        super.onDestroy();
    }

    // menu 键 = 原长按逻辑 (书切换小说/漫画, 数据源删除), 没聚焦可操作项时回落到缓存对话框;
    // config/设置键 = 对聚焦的书删索引
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!menuOnFocusedItem()) {
                showCacheDialog();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showBookConfigDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 取 RecyclerView 当前聚焦的条目, 没有则返回 null
    private ListItem focusedItem() {
        RecyclerView list = findViewById(R.id.fileListView);
        var focused = list.getFocusedChild();
        if (focused == null) {
            return null;
        }
        var holder = list.findContainingViewHolder(focused);
        if (holder == null) {
            return null;
        }
        int pos = holder.getAdapterPosition();
        if (pos < 0) {
            return null;
        }
        return fileListAdapter.getItem(pos);
    }

    private boolean menuOnFocusedItem() {
        var item = focusedItem();
        if (item == null) {
            return false;
        }
        if (item.type == ListItem.FileType.Resource) {
            showDeleteConfirmationDialog(item.id);
            return true;
        }
        if (item.type == ListItem.FileType.Epub) {
            var db = Database.getInstance(this).getDatabase();
            db.switch_view_type(item.id, item.uri != null ? item.uri : make_uri(item.name));
            new FetchFileListTask().executeTask();
            return true;
        }
        if (item.type == ListItem.FileType.RecentEpub) {
            var db = Database.getInstance(this).getDatabase();
            db.switch_view_type(item.id, item.uri);
            init_resource_list();
            return true;
        }
        return false;
    }

    // 磁盘缓存: covers 封面缩略图, updates OTA 下载的 apk, SQLite 里的 epub 索引; 另有 CoverLoader 内存 LRU
    private void showCacheDialog() {
        File covers = new File(getCacheDir(), "covers");
        File updates = new File(getCacheDir(), "updates");
        long coverBytes = dir_size(covers);
        long updateBytes = dir_size(updates);
        var db = Database.getInstance(this).getDatabase();
        long[] index = db.epub_index_stats();
        String msg = "封面: " + file_count(covers) + " 张, " + format_size(coverBytes)
                + "\n索引: " + index[0] + " 本, " + format_size(index[1])
                + "\n更新包: " + format_size(updateBytes);
        new AlertDialog.Builder(this)
                .setTitle("缓存")
                .setMessage(msg)
                .setPositiveButton("清空", (dialog, which) -> {
                    CoverLoader.get(this).clearMemory();
                    delete_children(covers);
                    delete_children(updates);
                    db.clear_epub_index();
                    Toast.makeText(this, "已清理 " + format_size(coverBytes + updateBytes + index[1]), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", (dialog, which) -> dialog.dismiss())
                .show();
    }

    // config 键: 删除当前聚焦那本书的索引与封面缓存 (换源/文件被替换后手动重建用)
    private void showBookConfigDialog() {
        var item = focusedItem();
        if (item == null) {
            return;
        }
        final String ns;
        final String uri;
        if (item.type == ListItem.FileType.Epub && client != null) {
            ns = client.to_json();
            uri = item.uri != null ? item.uri : make_uri(item.name);
        } else if (item.type == ListItem.FileType.RecentEpub) {
            ns = item.ns;
            uri = item.uri;
        } else {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new String[]{"删除本书索引与封面缓存"}, (dialog, which) -> {
                    var db = Database.getInstance(this).getDatabase();
                    db.delete_epub_index(ns, uri);
                    CoverLoader.get(this).removeCover(ns, uri);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    if (at_root_list) {
                        init_resource_list();
                    } else {
                        new FetchFileListTask().executeTask();
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private static File[] list_files(File dir) {
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }

    private static long dir_size(File dir) {
        long total = 0;
        for (var f : list_files(dir)) {
            total += f.isDirectory() ? dir_size(f) : f.length();
        }
        return total;
    }

    private static int file_count(File dir) {
        int n = 0;
        for (var f : list_files(dir)) {
            n += f.isDirectory() ? file_count(f) : 1;
        }
        return n;
    }

    private static void delete_children(File dir) {
        for (var f : list_files(dir)) {
            if (f.isDirectory()) {
                delete_children(f);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String format_size(long bytes) {
        if (bytes >= 1 << 20) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1 << 10) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    @Override
    public void onBackPressed() {
        if (at_root_list) {
            var now = Instant.now().toEpochMilli();
            if (now < kill_app_countdown) {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            } else {
                kill_app_countdown = now + 1000;
                android.widget.Toast.makeText(MainActivity.this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
            }
        } else if (pwd.isEmpty()) {
            init_resource_list();
        } else {
            pwd.remove(pwd.size() - 1);
            new FetchFileListTask().executeTask();
        }
    }


    private class FetchFileListTask {
        private final Handler handler = new Handler(Looper.getMainLooper());

        public void executeTask() {
            fetchExecutor.execute(() -> {
                at_root_list = false;
                List<ListItem> fileList;
                try {
                    fileList = client.ls(current_resource_id, pwd);
                } catch (Exception e) {
                    // 这里在后台线程, Toast 必须切回主线程, 否则会抛 "Can't toast on a thread that has not called Looper.prepare()"
                    handler.post(() -> Toast.makeText(MainActivity.this, "http请求失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                var epubs = new ArrayList<String>();
                for (var file : fileList) {
                    if (file.type == ListItem.FileType.Epub) {
                        file.uri = make_uri(file.name);
                        epubs.add(file.uri);
                    }
                }
                var db = Database.getInstance(MainActivity.this).getDatabase();
                var map = db.get_view_types(current_resource_id, epubs);
                for (var file : fileList) {
                    if (file.type == ListItem.FileType.Epub && map.containsKey(file.uri)) {
                        var info = map.get(file.uri);
                        assert info != null;
                        file.view_type = info.view_type;
                        file.total_page = info.total_page;
                        file.read_to_page = info.read_to_page;
                    }
                }
                final var finalFileList = fileList;
                handler.post(() -> {
                    fileListAdapter.setClient(client);
                    fileListAdapter.setItems(finalFileList);
                });
            });
        }
    }

    private String make_uri(String filename) {
        StringBuilder cur = new StringBuilder();
        for (var p : pwd) {
            cur.append("/").append(p);
        }
        cur.append("/").append(filename);
        return cur.toString();
    }
}