package com.github.mmooyyii.malguem;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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


public class NovelActivity extends AppCompatActivity {

    private WebView novelView;
    private TextView pageView;
    Book epub_book;
    int epub_book_page;
    int resource_id;
    String book_uri;

    ResourceInterface client;

    private AlertDialog progressDialog;

    private final BlockingQueue<Integer> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    // 当前页的加载放到这个线程, 避免在 UI 线程上做网络 IO 造成 ANR
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();

    @SuppressLint("SetJavaScriptEnabled")
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
        webSettings.setJavaScriptEnabled(true);
        novelView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

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
        new OpenEpub(dialogView).executeTask();
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
        var db = Database.getInstance(this).getDatabase();
        db.save_history(resource_id, book_uri, epub_book.total_pages(), epub_book_page, novelView.getScrollY());
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        loadExecutor.shutdownNow();
        // 规范销毁 WebView 以释放其 native 内存 (取代原来独立进程+killProcess 的做法)
        destroyWebView(novelView);
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

    public void prepare_pages(int n) throws InterruptedException {
        for (var i = epub_book_page + 2; i < epub_book_page + 2 + n; i++) {
            if (i >= epub_book.total_pages()) {
                return;
            }
            taskQueue.put(i);
        }
    }

    public void show_new_page(String html, int page_offset) {
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

            @Override
            public void onPageFinished(WebView view, String url) {
                // 内容布局完成后再恢复滚动位置; 在 loadData 之前 scrollTo 会被加载重置, 恢复无效
                view.scrollTo(0, page_offset);
            }
        });
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
            epub_book_page = Math.max(0, Math.min(epub_book.total_pages() - 1, epub_book_page + 1));
        }
        if (page_changed) {
            notifyPageChanged(0);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void notifyPageChanged(int page_offset) {
        final int total = epub_book.total_pages();
        final int page = Math.max(0, Math.min(epub_book_page, total - 1));
        epub_book_page = page;
        loadExecutor.execute(() -> {
            try {
                epub_book.prepare(page, page + 1);
            } catch (Exception ignore) {
            }
            final String html = total > 0 ? epub_book.page(page) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (html != null) {
                    show_new_page(html, page_offset);
                }
                pageView.setText(getString(R.string.page, page + 1, total));
                try {
                    prepare_pages(5);
                } catch (InterruptedException ignore) {
                }
            });
        });
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
                var db = Database.getInstance(NovelActivity.this).getDatabase();
                var info = db.get_epub_info(resource_id, book_uri);
                epub_book_page = info.current_page;
                epub_book = OpenStreamEpubBackground();
                handler.post(() -> {
                    progressDialog.dismiss();
                    notifyPageChanged(info.page_offset);
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

        private Book OpenStreamEpubBackground() {
            try {
                return new LazyEpub(book_uri, client);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
