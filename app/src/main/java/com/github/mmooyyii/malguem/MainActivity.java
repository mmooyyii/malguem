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
    private boolean skipFirstResume = true;
    private AppUpdater updater;
    // 目录拉取共用一个后台线程, 避免每次 FetchFileListTask 新建一个从不 shutdown 的 executor 泄漏线程
    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setup_file_list();
        pwd = new ArrayList<>();
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
        fileListAdapter.setOnItemAction(new FileListAdapter.OnItemAction() {
            @Override
            public void onClick(ListItem item) {
                onFileClicked(item);
            }

            @Override
            public boolean onLongClick(ListItem item) {
                return menuOnItem(item);
            }
        });
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

                startActivity(intent);
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
                startActivity(intent);
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
        String[] types = {"扫描局域网 (alist/SMB)", "WebDAV", "SMB", "本地硬盘"};
        new AlertDialog.Builder(this)
                .setTitle("选择数据源类型")
                .setItems(types, (dialog, which) -> {
                    if (which == 0) {
                        startLanScan();
                    } else if (which == 1) {
                        showWebdavDialog(null, null, null, null);
                    } else if (which == 2) {
                        showSmbDialog(null, null, null, null, null, null);
                    } else {
                        showLocalDialog(null, null);
                    }
                })
                .show();
    }

    // 扫描本机所在 /24 网段的 alist(5244)/SMB(445) 端口, 免得对着遥控器敲 IP
    private void startLanScan() {
        var ip = LanScanner.localIp();
        if (ip == null) {
            Toast.makeText(this, "没找到局域网 IP, 请检查网络", Toast.LENGTH_SHORT).show();
            return;
        }
        var subnet = ip.substring(0, ip.lastIndexOf('.'));
        var progress = new AlertDialog.Builder(this)
                .setTitle("扫描局域网")
                .setMessage("正在扫描 " + subnet + ".1-254 的 5244(alist) / 445(SMB) 端口…")
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .create();
        progress.show();
        new Thread(() -> {
            var hits = LanScanner.scan(ip, new int[]{LanScanner.PORT_ALIST, LanScanner.PORT_SMB});
            runOnUiThread(() -> {
                if (isDestroyed() || !progress.isShowing()) {
                    return; // 用户已取消或界面已销毁, 丢弃结果
                }
                progress.dismiss();
                showScanResults(hits);
            });
        }, "lan-scanner").start();
    }

    private void showScanResults(List<LanScanner.Hit> hits) {
        if (hits.isEmpty()) {
            Toast.makeText(this, "没有扫到 alist / SMB 服务", Toast.LENGTH_SHORT).show();
            return;
        }
        var labels = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            labels[i] = h.ip + (h.port == LanScanner.PORT_ALIST ? "  ·  alist (WebDAV)" : "  ·  SMB");
        }
        new AlertDialog.Builder(this)
                .setTitle("发现的服务")
                .setItems(labels, (dialog, which) -> {
                    var h = hits.get(which);
                    if (h.port == LanScanner.PORT_ALIST) {
                        // alist 的 WebDAV 挂在 /dav 下
                        showWebdavDialog("http://" + h.ip + ":" + LanScanner.PORT_ALIST + "/dav", null, null, null);
                    } else {
                        showSmbDialog(h.ip, null, null, null, null, null);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // editId 为 null 是新增, 否则是编辑该 id 的数据源 (update 保 id, 进度不丢)
    private void showWebdavDialog(String url, String user, String pass, Integer editId) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_login, null);
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        final EditText etUrl = dialogView.findViewById(R.id.et_url);
        if (url != null) {
            etUrl.setText(url);
        }
        if (user != null) {
            etUsername.setText(user);
        }
        if (pass != null) {
            etPassword.setText(pass);
        }
        new AlertDialog.Builder(this)
                .setTitle(editId == null ? "添加 WebDAV" : "编辑 WebDAV")
                .setView(dialogView)
                .setPositiveButton(editId == null ? "添加" : "保存", (dialog, which) -> {
                    String username = etUsername.getText().toString();
                    String password = etPassword.getText().toString();
                    String u = etUrl.getText().toString();
                    var r = new WebdavResource(u, username, password);
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    if (editId == null) {
                        db.add_resource(u, 1, r.to_json());
                    } else {
                        db.update_resource(editId, u, 1, r.to_json());
                    }
                    Toast.makeText(MainActivity.this, editId == null ? "添加成功" : "已保存", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showSmbDialog(String host, String share, String user, String pass, String domain, Integer editId) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_smb, null);
        final EditText etHost = dialogView.findViewById(R.id.et_host);
        final EditText etShare = dialogView.findViewById(R.id.et_share);
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        if (host != null) {
            etHost.setText(host);
        }
        if (share != null) {
            etShare.setText(share);
        }
        if (user != null) {
            etUsername.setText(user);
        }
        if (pass != null) {
            etPassword.setText(pass);
        }
        // 弹窗里不放 domain 输入, 编辑时原样保留
        final String keepDomain = domain == null ? "" : domain;
        new AlertDialog.Builder(this)
                .setTitle(editId == null ? "添加 SMB" : "编辑 SMB")
                .setView(dialogView)
                .setPositiveButton(editId == null ? "添加" : "保存", (dialog, which) -> {
                    String h = etHost.getText().toString().trim();
                    String s = etShare.getText().toString().trim();
                    String u = etUsername.getText().toString();
                    String p = etPassword.getText().toString();
                    var r = new SmbResource(h, s, u, p, keepDomain);
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    if (editId == null) {
                        db.add_resource("smb://" + h + "/" + s, 2, r.to_json());
                    } else {
                        db.update_resource(editId, "smb://" + h + "/" + s, 2, r.to_json());
                    }
                    Toast.makeText(MainActivity.this, editId == null ? "添加成功" : "已保存", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showLocalDialog(String path, Integer editId) {
        ensureStoragePermission();
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_local, null);
        final EditText etPath = dialogView.findViewById(R.id.et_path);
        if (path != null) {
            etPath.setText(path);
        }
        new AlertDialog.Builder(this)
                .setTitle(editId == null ? "添加本地目录" : "编辑本地目录")
                .setView(dialogView)
                .setPositiveButton(editId == null ? "添加" : "保存", (dialog, which) -> {
                    String p = etPath.getText().toString().trim();
                    var r = new LocalResource(p);
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    if (editId == null) {
                        db.add_resource(p, 3, r.to_json());
                    } else {
                        db.update_resource(editId, p, 3, r.to_json());
                    }
                    Toast.makeText(MainActivity.this, editId == null ? "添加成功" : "已保存", Toast.LENGTH_SHORT).show();
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
        findViewById(R.id.listLoading).setVisibility(View.GONE);
        // 只剩"添加数据源"一个格子时显示空书库引导
        findViewById(R.id.emptyHint).setVisibility(list.size() == 1 ? View.VISIBLE : View.GONE);
    }

    // 从阅读界面回来时刷新列表 (进度徽标/最近阅读). 用 onResume 而不是 ActivityResult:
    // 阅读中切换模式会 finish 旧 reader 再起新 reader, result 链会断, onResume 能覆盖所有返回路径
    @Override
    protected void onResume() {
        super.onResume();
        if (skipFirstResume) {
            skipFirstResume = false; // onCreate 里 init_resource_list 已经初始化过
            return;
        }
        if (at_root_list) {
            init_resource_list();
        } else {
            new FetchFileListTask().executeTask();
        }
    }

    @Override
    protected void onDestroy() {
        fetchExecutor.shutdownNow();
        super.onDestroy();
    }

    // menu 键(或长按OK): 书切换小说/漫画, 数据源编辑/删除, 最近阅读切换/移除; 没聚焦可操作项时显示帮助;
    // config/设置键 = 对聚焦的书删索引
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!menuOnFocusedItem()) {
                showHelpDialog();
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
        return item != null && menuOnItem(item);
    }

    // 菜单键与长按 OK 共用: 数据源=编辑/删除, 书=切换阅读模式, 最近阅读=切换/移除
    private boolean menuOnItem(ListItem item) {
        if (item.type == ListItem.FileType.Resource) {
            showResourceMenu(item);
            return true;
        }
        if (item.type == ListItem.FileType.Epub) {
            var db = Database.getInstance(this).getDatabase();
            db.switch_view_type(item.id, item.uri != null ? item.uri : make_uri(item.name));
            new FetchFileListTask().executeTask();
            return true;
        }
        if (item.type == ListItem.FileType.RecentEpub) {
            showRecentMenu(item);
            return true;
        }
        return false;
    }

    private void showResourceMenu(ListItem item) {
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new String[]{"编辑", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        editResource(item.id);
                    } else {
                        showDeleteConfirmationDialog(item.id);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 编辑数据源: 预填现有配置, 保存时保住 id, 各书的阅读进度不丢
    private void editResource(int id) {
        var db = Database.getInstance(this).getDatabase();
        var r = db.get_resource(id);
        if (r == null) {
            Toast.makeText(this, "数据库异常", Toast.LENGTH_SHORT).show();
            return;
        }
        if (r instanceof WebdavResource) {
            var w = (WebdavResource) r;
            showWebdavDialog(w.url, w.username, w.password, id);
        } else if (r instanceof SmbResource) {
            var s = (SmbResource) r;
            showSmbDialog(s.host, s.share, s.username, s.password, s.domain, id);
        } else if (r instanceof LocalResource) {
            showLocalDialog(((LocalResource) r).root, id);
        }
    }

    private void showRecentMenu(ListItem item) {
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new String[]{"切换 漫画/小说", "从最近阅读移除"}, (dialog, which) -> {
                    var db = Database.getInstance(this).getDatabase();
                    if (which == 0) {
                        db.switch_view_type(item.id, item.uri);
                    } else {
                        db.clear_last_read(item.id, item.uri);
                    }
                    init_resource_list();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 帮助: 版本 + 按键说明 + 缓存概况 (covers 封面缩略图, updates OTA 包, SQLite 里的 epub 索引) + 检查更新
    private void showHelpDialog() {
        File covers = new File(getCacheDir(), "covers");
        File updates = new File(getCacheDir(), "updates");
        long coverBytes = dir_size(covers);
        long updateBytes = dir_size(updates);
        var db = Database.getInstance(this).getDatabase();
        long[] index = db.epub_index_stats();
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        String msg = "版本: " + version
                + "\n\n按键说明:"
                + "\n· 菜单键 / 长按OK — 书: 切换漫画/小说; 数据源: 编辑/删除; 最近阅读: 切换/移除"
                + "\n· 设置键 — 删除聚焦那本书的索引与封面"
                + "\n· 阅读中按OK — 目录 / 跳页 / 字号 / 夜间 / 阅读方向 / 单页"
                + "\n\n缓存:"
                + "\n封面 " + file_count(covers) + " 张, " + format_size(coverBytes)
                + "\n索引 " + index[0] + " 本, " + format_size(index[1])
                + "\n更新包 " + format_size(updateBytes);
        new AlertDialog.Builder(this)
                .setTitle("帮助")
                .setMessage(msg)
                .setPositiveButton("检查更新", (dialog, which) -> updater.checkManually())
                .setNeutralButton("清空缓存", (dialog, which) -> {
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


    // 目录拉取失败时给出重试入口, 不再只丢一个 toast 停在旧列表上
    private void showListError() {
        if (isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("目录加载失败")
                .setMessage("网络或服务器出错了")
                .setPositiveButton("重试", (dialog, which) -> new FetchFileListTask().executeTask())
                .setNegativeButton("回首页", (dialog, which) -> {
                    pwd.clear();
                    init_resource_list();
                })
                .show();
    }

    private class FetchFileListTask {
        private final Handler handler = new Handler(Looper.getMainLooper());

        public void executeTask() {
            // 只会从主线程发起 (点击/按键/对话框回调), 直接操作 view
            findViewById(R.id.listLoading).setVisibility(View.VISIBLE);
            findViewById(R.id.emptyHint).setVisibility(View.GONE);
            fetchExecutor.execute(() -> {
                at_root_list = false;
                List<ListItem> fileList;
                try {
                    fileList = client.ls(current_resource_id, pwd);
                } catch (Exception e) {
                    // 这里在后台线程, UI 操作必须切回主线程
                    handler.post(() -> {
                        findViewById(R.id.listLoading).setVisibility(View.GONE);
                        showListError();
                    });
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
                    findViewById(R.id.listLoading).setVisibility(View.GONE);
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