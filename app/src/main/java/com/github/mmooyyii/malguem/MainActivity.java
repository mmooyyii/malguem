package com.github.mmooyyii.malguem;

import android.content.Intent;
import android.os.Bundle;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.os.AsyncTask;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;


public class MainActivity extends AppCompatActivity {

    private FileListAdapter fileListAdapter;

    WebdavResource client;
    int current_resource_id;
    List<String> pwd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setup_file_list();
        pwd = new ArrayList<>();
    }

    public void setup_file_list() {
        ListView fileListView = findViewById(R.id.fileListView);
        fileListAdapter = new FileListAdapter(this);
        fileListView.setAdapter(fileListAdapter);
        fileListView.setOnItemLongClickListener((parent, view, position, id) -> {
            var file = fileListAdapter.getItem(position);
            assert file != null;
            if (file.type == FileType.Resource && position != fileListAdapter.getCount() - 1) {
                showDeleteConfirmationDialog(file.id);
            } else if (file.type == FileType.Epub) {
                var db = Database.getInstance(this).getDatabase();
                db.switch_view_type(file.id, make_uri(file.name));
                new FetchFileListTask().execute();
            }
            return true;
        });

        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            var file = fileListAdapter.getItem(position);
            assert file != null;
            switch (file.type) {
                case Resource: {
                    if (position == fileListAdapter.getCount() - 1) {
                        showLoginDialog();
                    } else {
                        var db = Database.getInstance(this).getDatabase();
                        client = db.get_webdav(file.id);
                        current_resource_id = file.id;
                        if (client == null) {
                            android.widget.Toast.makeText(MainActivity.this, "数据库异常", android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            new FetchFileListTask().execute();
                        }
                    }
                    break;
                }
                case Dir: {
                    pwd.add(file.name);
                    new FetchFileListTask().execute();
                    break;
                }
                case Epub: {
                    var db = Database.getInstance(this).getDatabase();
                    var type = db.get_epub_info(file.id, make_uri(file.name)).view_type;
                    Intent intent;
                    if (type == ViewType.Comic) {
                        intent = new Intent(MainActivity.this, ComicActivity.class);
                    } else {
                        intent = new Intent(MainActivity.this, NovelActivity.class);
                    }
                    intent.putExtra("resource_id", file.id);
                    intent.putExtra("book_uri", make_uri(file.name));
                    intent.putExtra("url", client._url);
                    intent.putExtra("username", client._username);
                    intent.putExtra("passwd", client._password);
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
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 获取用户输入的用户名、密码和 URL
                        String username = etUsername.getText().toString();
                        String password = etPassword.getText().toString();
                        String url = etUrl.getText().toString();
                        var db = Database.getInstance(MainActivity.this).getDatabase();
                        db.add_webdav(url, username, password);
                        Toast.makeText(MainActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                        init_resource_list();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 取消对话框
                        dialog.dismiss();
                    }
                });

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }


    public void init_resource_list() {
        var db = Database.getInstance(this).getDatabase();
        fileListAdapter.clear();
        for (var file : db.resource_list()) {
            fileListAdapter.addAll(file);
        }
        fileListAdapter.addAll(new File(0, "新增webdav", FileType.Resource));
        fileListAdapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        if (pwd.isEmpty()) {
            init_resource_list();
        } else {
            pwd.remove(pwd.size() - 1);
            new FetchFileListTask().execute();
        }
    }

    private class FetchFileListTask extends AsyncTask<Void, Void, List<File>> {
        @Override
        protected List<File> doInBackground(Void... params) {
            try {
                var files = client.ls(current_resource_id, pwd);
                var epubs = new ArrayList<String>();
                for (var file : files) {
                    if (file.type == FileType.Epub) {
                        epubs.add(make_uri(file.name));
                    }
                }
                var db = Database.getInstance(MainActivity.this).getDatabase();
                var map = db.get_view_types(current_resource_id, epubs);
                for (var file : files) {
                    if (file.type == FileType.Epub && map.containsKey(make_uri(file.name))) {
                        file.view_type = map.get(make_uri(file.name));
                    }
                }
                return files;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void onPostExecute(List<File> fileList) {
            if (fileList != null) {
                fileListAdapter.clear();
                for (var file : fileList) {
                    fileListAdapter.addAll(file);
                }
                fileListAdapter.notifyDataSetChanged();
            }
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