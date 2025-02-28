package com.github.mmooyyii.malguem;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.os.Bundle;

import android.os.AsyncTask;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;


public class NovelActivity extends AppCompatActivity {

    private WebView novelView;
    private TextView pageView;
    LazyEpub epub_book;
    int page_offset;
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

        // 创建 AlertDialog 并设置自定义布局

        LayoutInflater inflater = LayoutInflater.from(this);
        var dialogView = inflater.inflate(R.layout.progress_bar, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        progressDialog = builder.create();
        new NovelActivity.InitEpub().execute();
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
    }

    public void show_epub_page(WebView view, String html) {
        novelView.scrollTo(0, page_offset);
        page_offset = 0;
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                var req = request.getUrl().getPath();
                if (req == null) {
                    return super.shouldInterceptRequest(view, request);
                }
                if (!req.isEmpty() && req.charAt(0) == '/') {
                    req = req.substring(1);
                }
                if (epub_book.is_file_exist(req)) {
                    try {
                        var r = epub_book.load_file(req);
                        if (req.endsWith("jpg")) {
                            return new WebResourceResponse("image/jpeg", "UTF-8", new ByteArrayInputStream(r));
                        } else if (req.endsWith("css")) {
                            return new WebResourceResponse("text/css", "UTF-8", new ByteArrayInputStream(r));
                        } else {
                            return new WebResourceResponse("application/xhtml+xml", "UTF-8", new ByteArrayInputStream(r));
                        }
                    } catch (Exception e) {
                        return super.shouldInterceptRequest(view, request);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        pageView.setText((epub_book_page + 1) + "/" + epub_book.total_pages());
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
                        new ReadEpub().execute();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (epub_book_page + 1 < epub_book.total_pages()) {
                        epub_book_page += 1;
                        new ReadEpub().execute();
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private class InitEpub extends AsyncTask<String, Void, LazyEpub> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // 显示 ProgressDialog
            progressDialog.show();
        }

        @Override
        protected LazyEpub doInBackground(String... params) {
            try {
                return new LazyEpub(book_uri, client);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(LazyEpub book) {
            progressDialog.dismiss();
            if (book == null) {
                android.widget.Toast.makeText(NovelActivity.this, "打开epub失败", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            var db = Database.getInstance(NovelActivity.this).getDatabase();
            var info = db.get_epub_info(resource_id, book_uri);
            epub_book = book;
            epub_book_page = info.current_page;
            page_offset = info.page_offset;
        }
    }

    private class ReadEpub extends AsyncTask<Void, String, String> {
        @Override
        protected String doInBackground(Void... params) {
            if (epub_book_page < epub_book.total_pages()) {
                return epub_book.page(epub_book_page);
            }
            return "完";
        }

        @Override
        protected void onPostExecute(String html) {
            show_epub_page(novelView, html);
        }
    }
}
