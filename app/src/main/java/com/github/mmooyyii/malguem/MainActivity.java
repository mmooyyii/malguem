package com.github.mmooyyii.malguem;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {

    private FileListAdapter fileListAdapter;

    ResourceInterface client;
    int current_resource_id;
    List<String> pwd;

    boolean at_root_list = false;

    long kill_app_countdown = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setup_file_list();
        pwd = new ArrayList<>();
        try {
            DiskCache.getInstance(MainActivity.this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setup_file_list() {
        ListView fileListView = findViewById(R.id.fileListView);
        fileListAdapter = new FileListAdapter(this);
        fileListView.setAdapter(fileListAdapter);
        fileListView.setOnItemLongClickListener((parent, view, position, id) -> {
            var file = fileListAdapter.getItem(position);
            assert file != null;
            if (file.type == ListItem.FileType.Resource) {
                showDeleteConfirmationDialog(file.id);
            } else if (file.type == ListItem.FileType.Epub) {
                var db = Database.getInstance(this).getDatabase();
                db.switch_view_type(file.id, make_uri(file.name));
                new FetchFileListTask().executeTask();
            }
            return true;
        });

        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            var file = fileListAdapter.getItem(position);
            assert file != null;
            switch (file.type) {
                case UseEpubStream: {
                    SharedPreferences sharedPref = this.getSharedPreferences("config", Context.MODE_PRIVATE);
                    var is_stream = sharedPref.getBoolean("epub_stream", false);
                    SharedPreferences.Editor editor = sharedPref.edit();
                    editor.putBoolean("epub_stream", !is_stream);
                    editor.apply();
                    if (is_stream) {
                        file.name = "使用流式epub(实验)";
                    } else {
                        file.name = "使用预加载epub(稳定)";
                    }
                    fileListAdapter.notifyDataSetChanged();
                    break;
                }
                case ClearCache: {
                    try {
                        DiskCache.getInstance(MainActivity.this).getCache().delete();
                        Toast.makeText(MainActivity.this, "清除成功", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(MainActivity.this, "清除失败", Toast.LENGTH_SHORT).show();
                    }
                    break;
                }
                case SetCache: {
                    showSetCacheDialog();
                    break;
                }
                case AddWebDav: {
                    showLoginDialog();
                    break;
                }
                case Resource: {
                    var db = Database.getInstance(this).getDatabase();
                    client = db.get_webdav(file.id);
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
                    var type = db.get_epub_info(file.id, make_uri(file.name)).view_type;
                    Intent intent;
                    if (type == ListItem.ViewType.Comic) {
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
            }
        });
        init_resource_list();
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
            db.delete_webdav(id);
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

    @SuppressLint("SetTextI18n")
    private void showLoginDialog() {
        // 获取布局填充器
        LayoutInflater inflater = getLayoutInflater();
        // 加载自定义布局
        View dialogView = inflater.inflate(R.layout.dialog_login, null);

        // 初始化输入框
        final EditText etUsername = dialogView.findViewById(R.id.et_username);
        final EditText etPassword = dialogView.findViewById(R.id.et_password);
        final EditText etUrl = dialogView.findViewById(R.id.et_url);

        etUrl.setText("http://192.168.31.241:5244/dav/kuake");
        etUsername.setText("admin");
        etPassword.setText("a123456");
        // 创建 AlertDialog.Builder 对象
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加webdav")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    // 获取用户输入的用户名、密码和 URL
                    String username = etUsername.getText().toString();
                    String password = etPassword.getText().toString();
                    String url = etUrl.getText().toString();
                    var db = Database.getInstance(MainActivity.this).getDatabase();
                    db.add_webdav(url, username, password);
                    Toast.makeText(MainActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                    init_resource_list();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    // 取消对话框
                    dialog.dismiss();
                });

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void showSetCacheDialog() {
        // 获取布局填充器
        LayoutInflater inflater = getLayoutInflater();
        // 加载自定义布局
        View dialogView = inflater.inflate(R.layout.dialog_setcache, null);
        // 初始化输入框
        final EditText cacheSize = dialogView.findViewById(R.id.cacheSize);
        SharedPreferences sharedPref = this.getSharedPreferences("config", Context.MODE_PRIVATE);
        int maxSize = sharedPref.getInt("max_local_cache_size", 5 * 1024);
        var enable = sharedPref.getBoolean("enable_cache", true);
        if (enable) {
            cacheSize.setText(String.valueOf(maxSize));
        } else {
            cacheSize.setText("0");
        }
        // 创建 AlertDialog.Builder 对象
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置缓存大小")
                .setView(dialogView)
                .setPositiveButton("确认", (dialog, which) -> {
                    try {
                        SharedPreferences.Editor editor = sharedPref.edit();
                        String size = cacheSize.getText().toString();
                        var val = Integer.parseInt(size);
                        editor.putInt("max_local_cache_size", val);
                        editor.apply(); // 异步提交（推荐）
                        DiskCache.getInstance(MainActivity.this).setMaxSize(val);
                        Toast.makeText(MainActivity.this, "设置成功", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(MainActivity.this, "设置失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    // 取消对话框
                    dialog.dismiss();
                });

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    public void init_resource_list() {
        at_root_list = true;
        var db = Database.getInstance(this).getDatabase();
        fileListAdapter.clear();
        for (var file : db.resource_list()) {
            fileListAdapter.addAll(file);
        }
        fileListAdapter.addAll(new ListItem(0, "新增webdav", ListItem.FileType.AddWebDav));
        fileListAdapter.addAll(new ListItem(0, "设置缓存", ListItem.FileType.SetCache));
        fileListAdapter.addAll(new ListItem(0, "清除缓存", ListItem.FileType.ClearCache));
        SharedPreferences sharedPref = this.getSharedPreferences("config", Context.MODE_PRIVATE);
        var is_stream = sharedPref.getBoolean("epub_stream", false);
        if (is_stream) {
            fileListAdapter.addAll(new ListItem(0, "使用预加载epub(稳定)", ListItem.FileType.UseEpubStream));
        } else {
            fileListAdapter.addAll(new ListItem(0, "使用流式epub(实验)", ListItem.FileType.UseEpubStream));
        }
        fileListAdapter.notifyDataSetChanged();
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
        private final Executor executor = Executors.newSingleThreadExecutor();
        private final Handler handler = new Handler(Looper.getMainLooper());

        public void executeTask() {
            executor.execute(() -> {
                at_root_list = false;
                List<ListItem> fileList;
                try {
                    fileList = client.ls(current_resource_id, pwd);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "http请求失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                var epubs = new ArrayList<String>();
                for (var file : fileList) {
                    if (file.type == ListItem.FileType.Epub) {
                        epubs.add(make_uri(file.name));
                    }
                }
                var db = Database.getInstance(MainActivity.this).getDatabase();
                var map = db.get_view_types(current_resource_id, epubs);
                for (var file : fileList) {
                    if (file.type == ListItem.FileType.Epub && map.containsKey(make_uri(file.name))) {
                        file.view_type = map.get(make_uri(file.name));
                    }
                }
                final var finalFileList = fileList;
                handler.post(() -> {
                    fileListAdapter.clear();
                    for (var file : finalFileList) {
                        fileListAdapter.addAll(file);
                    }
                    fileListAdapter.notifyDataSetChanged();
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