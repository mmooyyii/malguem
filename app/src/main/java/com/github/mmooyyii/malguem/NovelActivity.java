package com.github.mmooyyii.malguem;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.concurrent.CountDownLatch;


public class NovelActivity extends AppCompatActivity {

    private WebView novelView;
    private TextView pageView;
    Epub epub_book;
    int epub_book_page;
    int resource_id;
    String book_uri;

    WebdavResource client;

    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_novel);
        pageView = findViewById(R.id.pageNumberTextView);
        novelView = findViewById(R.id.webView);
        var webSettings = novelView.getSettings();
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setUseWideViewPort(false);
        var intent = getIntent();
        resource_id = intent.getIntExtra("resource_id", 1);
        book_uri = intent.getStringExtra("book_uri");
        client = new WebdavResource(
                getIntent().getStringExtra("url"),
                getIntent().getStringExtra("username"),
                getIntent().getStringExtra("passwd")
        );
        LayoutInflater inflater = LayoutInflater.from(this);
        var dialogView = inflater.inflate(R.layout.progress_bar, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        progressDialog = builder.create();
        new ReadEpub(dialogView).execute();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        var db = Database.getInstance(this).getDatabase();
        db.save_history(resource_id, book_uri, epub_book_page, novelView.getScrollY());
        super.onDestroy();

        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    public void show_epub_page(int page, int page_offset) {
        var html = epub_book.page(page);
        novelView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                var req = request.getUrl().getPath();
                if (req == null) {
                    return super.shouldInterceptRequest(view, request);
                }
                if (!req.isEmpty() && req.charAt(0) == '/') {
                    req = req.substring(1);
                }
                if (epub_book.resource_map().containsKey(req)) {
                    var r = epub_book.resource_map().get(req);
                    assert r != null;
                    try {
                        return new WebResourceResponse(r.getMediaType().getName(), "UTF-8", r.getInputStream());
                    } catch (IOException e) {
                        return super.shouldInterceptRequest(view, request);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        novelView.scrollTo(0, page_offset);
        novelView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        pageView.setText((page + 1) + "/" + epub_book.total_pages());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (epub_book_page > 0) {
                        epub_book_page -= 1;
                        show_epub_page(epub_book_page, 0);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (epub_book_page + 1 < epub_book.total_pages()) {
                        epub_book_page += 1;
                        show_epub_page(epub_book_page, 0);
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private class ReadEpub extends AsyncTask<String, Integer, Epub> {

        private final TextView progressMessageTextView;

        DecimalFormat fmt;

        public ReadEpub(android.view.View dialogView) {
            this.progressMessageTextView = dialogView.findViewById(R.id.message);
            this.fmt = new DecimalFormat("0.000");
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // 显示 ProgressDialog
            progressDialog.show();
        }

        @Override
        protected Epub doInBackground(String... params) {
            try {
                String key = DiskCache.generateKey(book_uri);
                var diskCache = DiskCache.getInstance(NovelActivity.this).getCache();
                var snapshot = diskCache.get(key);
                if (snapshot != null) {
                    try (var inputStream = snapshot.getInputStream(0)) {
                        return new Epub(DiskCache.readAll(inputStream));
                    } finally {
                        snapshot.close();
                    }
                }
                var raw = client.open(book_uri, (current_bytes, total_bytes) -> publishProgress((int) current_bytes, (int) total_bytes));
                if (raw == null) {
                    return null;
                }
                var editor = diskCache.edit(key);
                if (editor != null) {
                    OutputStream outputStream = editor.newOutputStream(0);
                    DiskCache.copy(new ByteArrayInputStream(raw), outputStream);
                    editor.commit(); // 提交写入
                }
                return new Epub(raw);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Epub book) {
            progressDialog.dismiss();
            if (book == null) {
                android.widget.Toast.makeText(NovelActivity.this, "打开epub失败", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            var db = Database.getInstance(NovelActivity.this).getDatabase();
            var info = db.get_epub_info(resource_id, book_uri);
            epub_book = book;
            epub_book_page = info.current_page;
            show_epub_page(epub_book_page, info.page_offset);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            double current_bytes = values[0];
            double total_bytes = values[1];
            var a = fmt.format(current_bytes / 1024 / 1024);
            var b = fmt.format(total_bytes / 1024 / 1024);
            progressMessageTextView.setText("已完成 " + a + " MB / " + b + "MB");
        }
    }
}
