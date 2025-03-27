package com.github.mmooyyii.malguem;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class ComicActivity extends AppCompatActivity {

    private final BlockingQueue<Pair<Integer, Integer>> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    private WebView ComicViewLeft;
    private WebView ComicViewRight;

    private TextView pageView;
    Book epub_book;
    int epub_book_page;
    int resource_id;

    String book_uri;

    ResourceInterface client;

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
        client = ResourceInterface.from_json(intent.getStringExtra("client"));
        LayoutInflater inflater = LayoutInflater.from(this);
        var dialogView = inflater.inflate(R.layout.progress_bar, null);
        // 创建 AlertDialog 并设置自定义布局
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        progressDialog = builder.create();
        new OpenEpub(dialogView).executeTask();

        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {  // 检查中断状态
                    var n = taskQueue.take();
                    epub_book.prepare(n.first, n.second);
                }
            } catch (InterruptedException e) {
                // 线程被中断时自动退出循环
                Thread.currentThread().interrupt();  // 重置中断标志
            }
        });
    }

    @Override
    public void onBackPressed() {
        var db = Database.getInstance(this).getDatabase();
        db.save_history(resource_id, book_uri, epub_book.total_pages(), epub_book_page, 0);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    public void show_new_page(WebView view, int page) throws Exception {
        prepare_pages(5);
        final CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            epub_book.prepare(page, page + 1);
            latch.countDown();
        }).start();
        latch.await();
        var html = epub_book.page(page);
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
                try {
                    var file = epub_book.GetResource(req);
                    var type = epub_book.GetMediaType(req);
                    return new WebResourceResponse(type, "UTF-8", new ByteArrayInputStream(file));
                } catch (Exception e) {
                    return super.shouldInterceptRequest(view, request);
                }
            }
        });
        view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean page_changed = false;
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            epub_book_page = Math.max(0, epub_book_page - 2);
            page_changed = true;
        } else if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            epub_book_page = Math.min(epub_book.total_pages() - 2, epub_book_page + 2);
            page_changed = true;
        }
        if (page_changed) {
            notifyPageChanged();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void notifyPageChanged() {
        try {
            show_new_page(ComicViewLeft, epub_book_page);
            show_new_page(ComicViewRight, epub_book_page + 1);
            pageView.setText(getString(R.string.page, epub_book_page + 1, epub_book.total_pages()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void prepare_pages(int n) throws InterruptedException {
        var from = Math.min(epub_book_page + 2, epub_book.total_pages());
        var to = Math.min(epub_book_page + 2 + n, epub_book.total_pages());
        if (from < to) {
            taskQueue.put(Pair.create(from, to));
        }
    }

    private class OpenEpub {
        private final Executor executor = Executors.newSingleThreadExecutor();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DecimalFormat fmt = new DecimalFormat("0.000"); // 保留进度格式化
        private final TextView progressMessageTextView;

        public OpenEpub(android.view.View dialogView) {
            this.progressMessageTextView = dialogView.findViewById(R.id.message);
        }

        public void executeTask() {
            // 对应 onPreExecute
            handler.post(() -> {
                        progressDialog.show();
                        progressMessageTextView.setText(getString(R.string.opening_epub));
                    }
            );
            executor.execute(() -> {
                LoadReadHistory();
                SharedPreferences sharedPref = ComicActivity.this.getSharedPreferences("config", Context.MODE_PRIVATE);
                var is_stream = sharedPref.getBoolean("epub_stream", false);
                Book book = is_stream ? OpenStreamEpubBackground() : OpenEpubBackground();
                handler.post(() -> {
                    progressDialog.dismiss();
                    if (book == null) {
                        android.widget.Toast.makeText(ComicActivity.this, "打开epub失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    epub_book = book;
                    notifyPageChanged();
                });
            });
        }

        // 进度更新方法
        private void updateProgress(int current, int total) {
            handler.post(() -> {
                var a = fmt.format(current / 1024.0 / 1024.0);
                var b = fmt.format(total / 1024.0 / 1024.0);
                progressMessageTextView.setText(getString(R.string.load_percent, a, b));
            });
        }

        private void LoadReadHistory() {
            var db = Database.getInstance(ComicActivity.this).getDatabase();
            epub_book_page = db.get_epub_info(resource_id, book_uri).current_page;
        }

        private Book OpenStreamEpubBackground() {
            try {
                return new LazyEpub(book_uri, client);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Book OpenEpubBackground() {
            try {
                String key = DiskCache.generateKey(book_uri);
                var diskCache = DiskCache.getInstance(ComicActivity.this).getCache();
                var snapshot = diskCache.get(key);
                if (snapshot != null) {
                    try (var inputStream = snapshot.getInputStream(0)) {
                        progressMessageTextView.setText(getString(R.string.parse_epub));
                        return new Epub(DiskCache.readAll(inputStream));
                    } finally {
                        snapshot.close();
                    }
                }
                var raw = client.open(book_uri, (current_bytes, total_bytes) -> updateProgress((int) current_bytes, (int) total_bytes));
                if (raw == null) {
                    return null;
                }
                var editor = diskCache.edit(key);
                if (editor != null) {
                    OutputStream outputStream = editor.newOutputStream(0);
                    DiskCache.copy(new ByteArrayInputStream(raw), outputStream);
                    editor.commit(); // 提交写入
                }
                progressMessageTextView.setText(getString(R.string.parse_epub));
                return new Epub(raw);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
