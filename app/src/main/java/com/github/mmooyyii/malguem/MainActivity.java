package com.github.mmooyyii.malguem;

import android.annotation.SuppressLint;
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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.util.ArrayList;
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
                    new FetchFileListTask().executeTask();
                });
        updater = new AppUpdater(this);
        updater.checkOnLaunch();
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
            public void onClick(ListItem file) {
                onFileClicked(file);
            }

            @Override
            public boolean onLongClick(ListItem file) {
                return onFileLongClicked(file);
            }
        });
        init_resource_list();
    }

    private boolean onFileLongClicked(ListItem file) {
        if (file.type == ListItem.FileType.Resource) {
            showDeleteConfirmationDialog(file.id);
        } else if (file.type == ListItem.FileType.Epub) {
            var db = Database.getInstance(this).getDatabase();
            db.switch_view_type(file.id, make_uri(file.name));
            new FetchFileListTask().executeTask();
        }
        return true;
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

    @SuppressLint("SetTextI18n")
    private void showWebdavDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_login, null);
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        final EditText etUrl = dialogView.findViewById(R.id.et_url);
        etUrl.setText("http://192.168.31.241:5244/dav/kuake");
        etUsername.setText("admin");
        etPassword.setText("a123456");
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
        var list = new ArrayList<ListItem>(db.resource_list());
        list.add(new ListItem(0, "新增webdav", ListItem.FileType.AddWebDav));
        fileListAdapter.setClient(null);
        fileListAdapter.setItems(list);
    }

    @Override
    protected void onDestroy() {
        fetchExecutor.shutdownNow();
        super.onDestroy();
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