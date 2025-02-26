package com.github.mmooyyii.malguem;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

import android.view.LayoutInflater;


public class ComicActivity extends AppCompatActivity {

    private WebView ComicViewLeft;
    private WebView ComicViewRight;
    Epub epub_book;
    int epub_book_page;
    int resource_id;

    String book_uri;

    WebdavResource client;

    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comic);
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
        new ComicActivity.ReadEpub().execute();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }


    @Override
    protected void onDestroy() {
        var db = Database.getInstance(this).getDatabase();
        db.save_history(resource_id, book_uri, epub_book_page);
        super.onDestroy();
    }

    public void show_epub_page(WebView view, int page) throws Exception {
        String html;
        var data = epub_book.page(page);
        html = new String(data, "UTF-8");
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
        view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        view.setVisibility(android.view.View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && epub_book_page > 0) {
                epub_book_page -= 2;
                try {
                    show_epub_page(ComicViewLeft, epub_book_page);
                    show_epub_page(ComicViewRight, epub_book_page + 1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && epub_book_page + 2 < epub_book.total_pages())) {
                epub_book_page += 2;
                try {
                    show_epub_page(ComicViewLeft, epub_book_page);
                    show_epub_page(ComicViewRight, epub_book_page + 1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private class ReadEpub extends AsyncTask<String, Void, Epub> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // 显示 ProgressDialog
            progressDialog.show();
        }


        @Override
        protected Epub doInBackground(String... params) {
            try {
                var raw = client.open(book_uri);
                return new Epub(raw);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Epub book) {
            progressDialog.dismiss();
            if (book == null) {
                android.widget.Toast.makeText(ComicActivity.this, "打开epub失败", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            var db = Database.getInstance(ComicActivity.this).getDatabase();
            epub_book = book;
            epub_book_page = db.get_epub_info(resource_id, book_uri).first;
            try {
                show_epub_page(ComicViewLeft, epub_book_page);
                show_epub_page(ComicViewRight, epub_book_page + 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
