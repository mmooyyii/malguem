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
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayInputStream;
import java.text.DecimalFormat;
import java.util.concurrent.BlockingQueue;
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
        // 左栏向下滚到底 -> 焦点切到右栏 (canScrollVertically 比 contentHeight*scale 的算术判断可靠)
        ComicViewLeft.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY > oldScrollY && !v.canScrollVertically(1)) {
                ComicViewRight.requestFocus();
            }
        });
        // 右栏向上滚到顶 -> 焦点切回左栏
        ComicViewRight.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY < oldScrollY && !v.canScrollVertically(-1)) {
                ComicViewLeft.requestFocus();
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
    protected void onPause() {
        // 在 onPause 保存进度, 覆盖 HOME 键/进程回收等非返回键的退出路径; 开书失败时 epub_book 为 null 则跳过
        if (epub_book != null) {
            var db = Database.getInstance(this).getDatabase();
            db.save_history(resource_id, book_uri, epub_book.total_pages(), epub_book_page, 0);
        }
        super.onPause();
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
        if (epub_book == null) {
            // 书还没打开(或打开失败)时不响应翻页键, 避免 NPE
            return super.dispatchKeyEvent(event);
        }
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
                    // 漫画一页一张大图, 预取窗口放大到 8 页; 已缓存的页在 prepare 里会被跳过
                    prepare_pages(8);
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
            // 复用 loadExecutor, 不再每次开书新建一个从不 shutdown 的线程
            loadExecutor.execute(() -> {
                try {
                    LoadReadHistory();
                    // 有持久化索引时 0 次网络往返完成开书
                    var db = Database.getInstance(ComicActivity.this).getDatabase();
                    epub_book = LazyEpub.open(client.to_json(), book_uri, client, db);
                    handler.post(() -> {
                        if (isDestroyed()) {
                            return; // 活动已销毁时窗口已被系统回收, 再 dismiss 会抛 View not attached
                        }
                        progressDialog.dismiss();
                        notifyPageChanged();
                    });
                } catch (Exception e) {
                    // 打开失败必须关掉不可取消的进度框并退出, 否则界面永久卡在转圈上
                    // (onDestroy 会 shutdownNow 中断打开过程, 也会走到这里, 所以同样要判 isDestroyed)
                    handler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        progressDialog.dismiss();
                        Toast.makeText(ComicActivity.this, "打开 epub 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            });
        }

        private void LoadReadHistory() {
            var db = Database.getInstance(ComicActivity.this).getDatabase();
            epub_book_page = db.get_epub_info(resource_id, book_uri).current_page;
        }
    }
}
