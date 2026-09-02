package com.github.mmooyyii.malguem;


import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.text.DecimalFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class ComicActivity extends AppCompatActivity {

    private final BlockingQueue<Pair<Integer, Integer>> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    // 当前页的加载放到这个线程, 避免在 UI 线程上做网络 IO 造成 ANR
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();

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
        loadExecutor.shutdownNow();
        // 规范销毁 WebView 以释放其 native 内存 (取代原来独立进程+killProcess 的做法)
        destroyWebView(ComicViewLeft);
        destroyWebView(ComicViewRight);
        super.onDestroy();
    }

    private void destroyWebView(WebView view) {
        if (view == null) {
            return;
        }
        var parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
        view.stopLoading();
        view.loadUrl("about:blank");
        view.removeAllViews();
        view.destroy();
    }

    public void show_new_page(WebView view, String html) {
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
            epub_book_page = Math.max(0, Math.min(epub_book.total_pages() - 2, epub_book_page + 2));
            page_changed = true;
        }
        if (page_changed) {
            notifyPageChanged();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void notifyPageChanged() {
        final int total = epub_book.total_pages();
        final int leftPage = Math.max(0, Math.min(epub_book_page, total - 1));
        epub_book_page = leftPage;
        final int rightPage = leftPage + 1;
        loadExecutor.execute(() -> {
            try {
                epub_book.prepare(leftPage, Math.min(rightPage + 1, total));
            } catch (Exception ignore) {
            }
            final String leftHtml = leftPage < total ? epub_book.page(leftPage) : null;
            final String rightHtml = rightPage < total ? epub_book.page(rightPage) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (leftHtml != null) {
                    show_new_page(ComicViewLeft, leftHtml);
                }
                if (rightHtml != null) {
                    show_new_page(ComicViewRight, rightHtml);
                } else {
                    // 奇数页时最后一屏右侧无内容, 清空避免残留上一页
                    ComicViewRight.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null);
                }
                pageView.setText(getString(R.string.page, leftPage + 1, total));
                try {
                    prepare_pages(5);
                } catch (InterruptedException ignore) {
                }
            });
        });
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
                epub_book = OpenStreamEpubBackground();
                handler.post(() -> {
                    progressDialog.dismiss();
                    notifyPageChanged();
                });
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
    }
}
