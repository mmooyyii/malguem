package com.github.mmooyyii.malguem;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class NovelActivity extends AppCompatActivity {

    private WebView novelView;
    private TextView pageView;
    Book epub_book;
    int epub_book_page;
    int resource_id;
    String book_uri;

    ResourceInterface client;

    private AlertDialog progressDialog;

    private BlockingQueue<Integer> taskQueue = new LinkedBlockingQueue<>();
    private ExecutorService executor = Executors.newFixedThreadPool(1);

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
        client = ResourceInterface.from_json(intent.getStringExtra("client"));
        LayoutInflater inflater = LayoutInflater.from(this);
        var dialogView = inflater.inflate(R.layout.progress_bar, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        progressDialog = builder.create();
        new ReadEpub(dialogView).execute();
        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {  // 检查中断状态
                    var n = taskQueue.take();
                    epub_book.prepare(n, n + 1);
                }
            } catch (InterruptedException e) {
                // 线程被中断时自动退出循环
                Thread.currentThread().interrupt();  // 重置中断标志
            }
        });
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

    public void prepare_pages(int n) throws InterruptedException {
        for (var i = epub_book_page + 2; i < epub_book_page + 2 + n; i++) {
            if (i >= epub_book.total_pages()) {
                return;
            }
            taskQueue.put(i);
        }
    }

    public void show_new_page(int page, int page_offset) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            // 执行同步 HTTP 请求（示例使用 HttpURLConnection）
            epub_book.prepare(page, page + 1);
            latch.countDown();
        }).start();
        prepare_pages(5);
        latch.await();
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
                try {
                    var file = epub_book.GetResource(req);
                    var type = epub_book.GetMediaType(req);
                    return new WebResourceResponse(type, "UTF-8", new ByteArrayInputStream(file));
                } catch (Exception e) {
                    return super.shouldInterceptRequest(view, request);
                }
            }
        });
        novelView.scrollTo(0, page_offset);
        novelView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean page_changed = false;
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            page_changed = true;
            epub_book_page = Math.max(0, epub_book_page - 1);
        } else if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            page_changed = true;
            epub_book_page = Math.min(epub_book.total_pages() - 1, epub_book_page + 1);
        }
        if (page_changed) {
            notifyPageChanged(0);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void notifyPageChanged(int page_offset) {
        try {
            show_new_page(epub_book_page, page_offset);
            pageView.setText((epub_book_page + 1) + "/" + epub_book.total_pages());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private class ReadEpub extends AsyncTask<String, Integer, Book> {

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
            progressMessageTextView.setText("正在打开epub");
        }

        @Override
        protected Book doInBackground(String... params) {
            var db = Database.getInstance(NovelActivity.this).getDatabase();
            epub_book_page = db.get_epub_info(resource_id, book_uri).current_page;
            SharedPreferences sharedPref = NovelActivity.this.getSharedPreferences("config", Context.MODE_PRIVATE);
            var is_stream = sharedPref.getBoolean("epub_stream", false);
            if (is_stream) {
                try {
                    var book = new LazyEpub(book_uri, client);
                    return book;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                try {
                    String key = DiskCache.generateKey(book_uri);
                    var diskCache = DiskCache.getInstance(NovelActivity.this).getCache();
                    var snapshot = diskCache.get(key);
                    if (snapshot != null) {
                        try (var inputStream = snapshot.getInputStream(0)) {
                            progressMessageTextView.setText("解析epub中");
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
                    progressMessageTextView.setText("解析epub中");
                    return new Epub(raw);
                } catch (IOException e) {
                    return null;
                }
            }
        }

        @Override
        protected void onPostExecute(Book book) {
            progressDialog.dismiss();
            if (book == null) {
                android.widget.Toast.makeText(NovelActivity.this, "打开epub失败", Toast.LENGTH_SHORT).show();
                return;
            }
            var db = Database.getInstance(NovelActivity.this).getDatabase();
            var info = db.get_epub_info(resource_id, book_uri);
            epub_book = book;
            epub_book_page = info.current_page;
            notifyPageChanged(info.page_offset);
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
