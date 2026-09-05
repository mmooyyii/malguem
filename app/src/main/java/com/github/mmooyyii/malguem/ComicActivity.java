package com.github.mmooyyii.malguem;


import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.SeekBar;
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
    private android.widget.ProgressBar pageLoading;
    Book epub_book;
    int epub_book_page;
    int resource_id;
    boolean rtl; // 从右到左阅读(日漫): 先读右栏再读左栏, 左键前进; 按书存在 epub 表
    boolean single; // 单页模式: 一屏只放一页 (跨页大图/横版漫画用); 按书存在 epub 表

    String book_uri;

    ResourceInterface client;

    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comic);
        pageView = findViewById(R.id.pageNumberTextView);
        pageLoading = findViewById(R.id.pageLoading);
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
        // 先读栏滚到底 -> 焦点切到后读栏; 后读栏滚回顶 -> 焦点切回先读栏 (rtl 时先读栏是右栏; 单页模式没有第二栏)
        // (canScrollVertically 比 contentHeight*scale 的算术判断可靠)
        ComicViewLeft.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (single) {
                return;
            }
            if (!rtl && scrollY > oldScrollY && !v.canScrollVertically(1)) {
                ComicViewRight.requestFocus();
            } else if (rtl && scrollY < oldScrollY && !v.canScrollVertically(-1)) {
                ComicViewRight.requestFocus();
            }
        });
        ComicViewRight.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (single) {
                return;
            }
            if (!rtl && scrollY < oldScrollY && !v.canScrollVertically(-1)) {
                ComicViewLeft.requestFocus();
            } else if (rtl && scrollY > oldScrollY && !v.canScrollVertically(1)) {
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
        if (action == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU)) {
            showReaderMenu();
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // rtl 时页序从右往左, 左键是前进; 单页模式一次走 1 页
            boolean forward = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl;
            int step = single ? 1 : 2;
            if (forward) {
                epub_book_page = Math.max(0, Math.min(epub_book.total_pages() - step, epub_book_page + step));
            } else {
                epub_book_page = Math.max(0, epub_book_page - step);
            }
            notifyPageChanged();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // OK/菜单键呼出的阅读菜单
    private void showReaderMenu() {
        String[] items = {
                getString(R.string.menu_toc),
                getString(R.string.menu_jump),
                getString(R.string.menu_direction, getString(rtl ? R.string.dir_rtl : R.string.dir_ltr)),
                getString(R.string.menu_single, getString(single ? R.string.on : R.string.off)),
                getString(R.string.menu_to_novel),
        };
        new AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    var db = Database.getInstance(this).getDatabase();
                    if (which == 0) {
                        showTocDialog();
                    } else if (which == 1) {
                        showJumpDialog();
                    } else if (which == 2) {
                        rtl = !rtl;
                        db.set_rtl(resource_id, book_uri, rtl);
                        Toast.makeText(this, rtl ? R.string.switched_rtl : R.string.switched_ltr, Toast.LENGTH_SHORT).show();
                        notifyPageChanged();
                    } else if (which == 3) {
                        single = !single;
                        db.set_single_page(resource_id, book_uri, single);
                        notifyPageChanged();
                    } else {
                        switchToNovel();
                    }
                })
                .show();
    }

    // 阅读中切换为小说模式: 记住选择, 在同一页起 NovelActivity 顶替自己.
    // 自己 finish 前 onPause 会先保存进度, 新 Activity 的 onCreate 在其之后, 读到的是最新页码
    private void switchToNovel() {
        var db = Database.getInstance(this).getDatabase();
        db.set_view_type(resource_id, book_uri, 1); // 1 = 小说
        var intent = new Intent(this, NovelActivity.class);
        intent.putExtra("resource_id", resource_id);
        intent.putExtra("book_uri", book_uri);
        intent.putExtra("client", getIntent().getStringExtra("client"));
        startActivity(intent);
        finish();
    }

    private void showTocDialog() {
        var toc = epub_book.toc();
        if (toc.isEmpty()) {
            Toast.makeText(this, R.string.no_toc, Toast.LENGTH_SHORT).show();
            return;
        }
        var titles = new String[toc.size()];
        int current = 0;
        for (int i = 0; i < toc.size(); i++) {
            titles[i] = toc.get(i).title;
            if (toc.get(i).page <= epub_book_page) {
                current = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_toc)
                .setSingleChoiceItems(titles, current, (dialog, which) -> {
                    epub_book_page = toc.get(which).page;
                    notifyPageChanged();
                    dialog.dismiss();
                })
                .show();
    }

    private void showJumpDialog() {
        var view = LayoutInflater.from(this).inflate(R.layout.dialog_seek, null);
        TextView label = view.findViewById(R.id.seek_label);
        SeekBar bar = view.findViewById(R.id.seek_bar);
        final int total = epub_book.total_pages();
        bar.setMax(Math.max(0, total - 1));
        bar.setKeyProgressIncrement(Math.max(1, total / 100));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(getString(R.string.page, progress + 1, total));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        bar.setProgress(epub_book_page);
        label.setText(getString(R.string.page, epub_book_page + 1, total));
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_jump)
                .setView(view)
                .setPositiveButton(R.string.jump, (dialog, which) -> {
                    epub_book_page = bar.getProgress();
                    notifyPageChanged();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void notifyPageChanged() {
        final int total = epub_book.total_pages();
        final int firstPage = Math.max(0, Math.min(epub_book_page, total - 1));
        epub_book_page = firstPage;
        final boolean two = !single;
        final int secondPage = firstPage + 1;
        // 页面图片要从网络拉时会卡一下, 转个圈让人知道在加载 (调用方都在主线程)
        pageLoading.setVisibility(View.VISIBLE);
        // 丢弃还在排队的过时预取区间, 把带宽让给当前页 (预取和当前页共享 WebDAV 连接池,
        // 快速翻页时排队的旧预取会让当前页请求等在后面); 正在执行的那个没法打断, 至多滞后一个
        taskQueue.clear();
        loadExecutor.execute(() -> {
            try {
                epub_book.prepare(firstPage, Math.min(firstPage + (two ? 2 : 1), total));
            } catch (Exception ignore) {
            }
            final String firstHtml = firstPage < total ? epub_book.page(firstPage) : null;
            final String secondHtml = two && secondPage < total ? epub_book.page(secondPage) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                pageLoading.setVisibility(View.GONE);
                if (single) {
                    // 单页模式: 右栏隐藏, 左栏占满整屏
                    ComicViewRight.setVisibility(View.GONE);
                    if (firstHtml != null) {
                        show_new_page(ComicViewLeft, firstHtml);
                    }
                    ComicViewLeft.requestFocus();
                } else {
                    ComicViewRight.setVisibility(View.VISIBLE);
                    // 阅读顺序里的第一页: 常规放左栏, rtl(日漫) 放右栏
                    var firstView = rtl ? ComicViewRight : ComicViewLeft;
                    var secondView = rtl ? ComicViewLeft : ComicViewRight;
                    if (firstHtml != null) {
                        show_new_page(firstView, firstHtml);
                    }
                    if (secondHtml != null) {
                        show_new_page(secondView, secondHtml);
                    } else {
                        // 末尾凑不满一屏两页时, 后读栏清空避免残留上一页
                        secondView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null);
                    }
                    firstView.requestFocus();
                }
                pageView.setText(getString(R.string.page, firstPage + 1, total));
                try {
                    // 漫画一页一张大图, 预取窗口 16 页 (慢链路上带宽闲着也是闲着, 挖深些抗快翻);
                    // 已缓存的页在 prepare 里会被跳过
                    prepare_pages(16);
                } catch (InterruptedException ignore) {
                }
            });
        });
    }

    public void prepare_pages(int n) throws InterruptedException {
        int step = single ? 1 : 2;
        var from = Math.min(epub_book_page + step, epub_book.total_pages());
        var to = Math.min(epub_book_page + step + n, epub_book.total_pages());
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
                    var db = Database.getInstance(ComicActivity.this).getDatabase();
                    // 有持久化索引时 0 次网络往返完成开书
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
                        Toast.makeText(ComicActivity.this,
                                getString(R.string.open_book_failed, Errors.describe(ComicActivity.this, e)),
                                Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            });
        }

        private void LoadReadHistory() {
            var db = Database.getInstance(ComicActivity.this).getDatabase();
            var info = db.get_epub_info(resource_id, book_uri);
            epub_book_page = info.current_page;
            rtl = info.rtl;
            single = info.single_page;
        }
    }
}
