package com.github.mmooyyii.malguem;

import android.annotation.SuppressLint;
import android.content.Intent;
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


public class NovelActivity extends AppCompatActivity {

    private WebView novelView;
    private TextView pageView;
    Book epub_book;
    int epub_book_page;
    int resource_id;
    String book_uri;
    boolean dark; // 夜间模式, 全局设置

    // 注入的夜间样式: 全部 !important, 不管 epub 自带 css 在前在后都能盖住; 图片稍调暗
    private static final String DARK_CSS = "<style>"
            + "html,body{background:#121212 !important;color:#c9c9c9 !important}"
            + "body *{background-color:transparent !important;color:#c9c9c9 !important;border-color:#3a3a3a !important}"
            + "a{color:#8ab4f8 !important}"
            + "img,svg,video{opacity:0.85}"
            + "</style>";

    ResourceInterface client;

    private AlertDialog progressDialog;

    private final BlockingQueue<Pair<Integer, Integer>> taskQueue = new LinkedBlockingQueue<>();
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
        webSettings.setTextZoom(getSharedPreferences("settings", MODE_PRIVATE).getInt("novel_text_zoom", 100));
        dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("novel_dark", false);
        applyBackground();
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
            // 章内位置存万分比而不是像素: 字号/夜间模式会改变排版高度, 像素偏移在重排后就指错地方了
            db.save_history(resource_id, book_uri, epub_book.total_pages(), epub_book_page, currentRatio());
        }
        super.onPause();
    }

    // 当前滚动位置的万分比 (0-10000)
    private int currentRatio() {
        @SuppressWarnings("deprecation")
        int max = Math.max(1, (int) (novelView.getContentHeight() * novelView.getScale()) - novelView.getHeight());
        return (int) (10000L * Math.max(0, Math.min(max, novelView.getScrollY())) / max);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        loadExecutor.shutdownNow();
        // 规范销毁 WebView 以释放其 native 内存 (取代原来独立进程+killProcess 的做法)
        destroyWebView(novelView);
        if (epub_book != null) {
            epub_book.close(); // 释放 PdfRenderer 等底层资源
        }
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

    // 预取后续 n 章: 整个区间一次 prepare, 章节正文和图片各合成一次批量请求, 而不是一章一章串行取
    public void prepare_pages(int n) throws InterruptedException {
        var from = Math.min(epub_book_page + 1, epub_book.total_pages());
        var to = Math.min(epub_book_page + 1 + n, epub_book.total_pages());
        if (from < to) {
            taskQueue.put(Pair.create(from, to));
        }
    }

    // WebView 自身背景也变深色, 避免翻页/加载间隙白屏闪一下
    private void applyBackground() {
        novelView.setBackgroundColor(dark ? 0xFF121212 : 0xFFFFFFFF);
    }

    // 夜间模式把样式插到 </head> 前 (没有 head 就直接拼在最前面)
    private String decorate(String html) {
        if (!dark) {
            return html;
        }
        int i = html.toLowerCase().indexOf("</head>");
        if (i >= 0) {
            return html.substring(0, i) + DARK_CSS + html.substring(i);
        }
        return DARK_CSS + html;
    }

    public void show_new_page(String html, int page_offset) {
        html = decorate(html);
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
                // 内容布局完成后再恢复滚动位置; 在 loadData 之前 scrollTo 会被加载重置, 恢复无效.
                // page_offset 是万分比, 按当前排版高度换算成像素
                @SuppressWarnings("deprecation")
                int max = Math.max(0, (int) (view.getContentHeight() * view.getScale()) - view.getHeight());
                view.scrollTo(0, (int) ((long) page_offset * max / 10000));
            }
        });
        novelView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
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
        // 上下键改为整屏翻页 (滚一屏留 10% 重叠); 忽略长按重复, 防止一秒翻几十屏
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getRepeatCount() == 0) {
                scrollByScreen(1);
            }
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getRepeatCount() == 0) {
                scrollByScreen(-1);
            }
            return true;
        }
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

    private void scrollByScreen(int dir) {
        int step = (int) (novelView.getHeight() * 0.9f);
        @SuppressWarnings("deprecation")
        int max = Math.max(0, (int) (novelView.getContentHeight() * novelView.getScale()) - novelView.getHeight());
        int target = Math.max(0, Math.min(max, novelView.getScrollY() + dir * step));
        novelView.scrollTo(0, target);
    }

    // OK/菜单键呼出的阅读菜单
    private void showReaderMenu() {
        String[] items = {
                getString(R.string.menu_toc),
                getString(R.string.menu_jump),
                getString(R.string.menu_zoom),
                getString(R.string.menu_dark, getString(dark ? R.string.on : R.string.off)),
                getString(R.string.menu_to_comic),
        };
        new AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showTocDialog();
                    } else if (which == 1) {
                        showJumpDialog();
                    } else if (which == 2) {
                        showZoomDialog();
                    } else if (which == 3) {
                        dark = !dark;
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("novel_dark", dark).apply();
                        applyBackground();
                        // 重新渲染当前页并按比例回到原位置
                        notifyPageChanged(currentRatio());
                    } else {
                        switchToComic();
                    }
                })
                .show();
    }

    // 阅读中切换为漫画模式: 记住选择, 在同一页起 ComicActivity 顶替自己.
    // 自己 finish 前 onPause 会先保存进度, 新 Activity 的 onCreate 在其之后, 读到的是最新页码
    private void switchToComic() {
        var db = Database.getInstance(this).getDatabase();
        db.set_view_type(resource_id, book_uri, 0); // 0 = 漫画
        var intent = new Intent(this, ComicActivity.class);
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
                    notifyPageChanged(0);
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
                    notifyPageChanged(0);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static final int[] TEXT_ZOOMS = {75, 90, 100, 115, 130, 150, 175, 200};

    private void showZoomDialog() {
        var prefs = getSharedPreferences("settings", MODE_PRIVATE);
        int saved = prefs.getInt("novel_text_zoom", 100);
        var labels = new String[TEXT_ZOOMS.length];
        int current = 2; // 默认 100%
        for (int i = 0; i < TEXT_ZOOMS.length; i++) {
            labels[i] = TEXT_ZOOMS[i] + "%";
            if (TEXT_ZOOMS[i] == saved) {
                current = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_zoom)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    int zoom = TEXT_ZOOMS[which];
                    prefs.edit().putInt("novel_text_zoom", zoom).apply();
                    // 先记住比例再改字号, 重新渲染后按比例回到原位置 (改字号会重排, 像素位置不可信)
                    int ratio = currentRatio();
                    novelView.getSettings().setTextZoom(zoom);
                    notifyPageChanged(ratio);
                    dialog.dismiss();
                })
                .show();
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
                    var db = Database.getInstance(NovelActivity.this).getDatabase();
                    var info = db.get_epub_info(resource_id, book_uri);
                    epub_book_page = info.current_page;
                    if (book_uri != null && book_uri.toLowerCase().endsWith(".pdf")) {
                        // pdf 整本下载到缓存后按页渲染 (PdfRenderer 只认本地文件)
                        epub_book = PdfBook.open(client.to_json(), book_uri, client, getCacheDir(),
                                done -> handler.post(() -> progressMessageTextView.setText(
                                        getString(R.string.pdf_downloading, fmt.format(done / 1048576.0)))));
                    } else {
                        // 有持久化索引时 0 次网络往返完成开书
                        epub_book = LazyEpub.open(client.to_json(), book_uri, client, db);
                    }
                    handler.post(() -> {
                        if (isDestroyed()) {
                            return; // 活动已销毁时窗口已被系统回收, 再 dismiss 会抛 View not attached
                        }
                        progressDialog.dismiss();
                        notifyPageChanged(info.page_offset);
                    });
                } catch (Exception e) {
                    // 打开失败必须关掉不可取消的进度框并退出, 否则界面永久卡在转圈上
                    // (onDestroy 会 shutdownNow 中断打开过程, 也会走到这里, 所以同样要判 isDestroyed)
                    handler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        progressDialog.dismiss();
                        Toast.makeText(NovelActivity.this,
                                getString(R.string.open_book_failed, Errors.describe(NovelActivity.this, e)),
                                Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
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
    }

}
