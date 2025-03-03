package com.github.mmooyyii.malguem;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;

import android.view.LayoutInflater;
import android.widget.TextView;


public class ComicActivity extends AppCompatActivity {

    private WebView ComicViewLeft;
    private WebView ComicViewRight;

    private TextView pageView;
    LazyEpub epub_book;
    int epub_book_page;
    int resource_id;

    String book_uri;

    WebdavResource client;

    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comic);
        pageView = findViewById(R.id.pageNumberTextView);
        ComicViewLeft = findViewById(R.id.comicLeft);
        ComicViewRight = findViewById(R.id.comicRight);
        var webSettings = ComicViewLeft.getSettings();
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setUseWideViewPort(true);

        webSettings = ComicViewRight.getSettings();
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setUseWideViewPort(true);
        // 监听左边 WebView 的滚动事件
        ComicViewLeft.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY > oldScrollY) { // 向下滚动
                var d1 = (double) ComicViewLeft.getContentHeight() * ComicViewLeft.getScale();
                var d2 = ComicViewLeft.getHeight() + scrollY;
                if (Math.abs(d1 - d2) <= 1) {
                    ComicViewRight.requestFocus();
                }
            }
        });
        // 监听右边 WebView 的滚动事件
        ComicViewRight.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY < oldScrollY) { // 向上滚动
                if (scrollY == 0) {
                    ComicViewLeft.requestFocus();
                }
            }
        });
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

        // 创建 AlertDialog 并设置自定义布局
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        progressDialog = builder.create();
        new ComicActivity.InitEpub().execute();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }


    @Override
    protected void onDestroy() {
        var db = Database.getInstance(this).getDatabase();
        db.save_history(resource_id, book_uri, epub_book_page, 0);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && epub_book_page > 0) {
                epub_book_page -= 2;
                try {
                    new ReadEpubLeft().execute();
                    new ReadEpubRight().execute();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && epub_book_page + 2 < epub_book.total_pages())) {
                epub_book_page += 2;
                try {
                    new ReadEpubLeft().execute();
                    new ReadEpubRight().execute();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public void show_epub_page(WebView view, String html) {
        view.scrollTo(0, 0);
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
                android.widget.Toast.makeText(ComicActivity.this, "打开epub失败", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            var db = Database.getInstance(ComicActivity.this).getDatabase();
            epub_book = book;
            epub_book_page = db.get_epub_info(resource_id, book_uri).current_page;
            try {
                new ReadEpubLeft().execute();
                new ReadEpubRight().execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class ReadEpubLeft extends AsyncTask<Void, String, String> {
        @Override
        protected String doInBackground(Void... params) {
            if (epub_book_page < epub_book.total_pages()) {
                return epub_book.page(epub_book_page);
            }
            return "完";
        }

        @Override
        protected void onPostExecute(String html) {
            show_epub_page(ComicViewLeft, html);
        }
    }

    private class ReadEpubRight extends AsyncTask<Void, String, String> {
        @Override
        protected String doInBackground(Void... params) {
            if (epub_book_page + 1 < epub_book.total_pages()) {
                return epub_book.page(epub_book_page + 1);
            }
            return "完";
        }

        @Override
        protected void onPostExecute(String html) {
            show_epub_page(ComicViewRight, html);
        }
    }
}
