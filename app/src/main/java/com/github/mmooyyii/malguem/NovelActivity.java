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
    private android.widget.ProgressBar pageLoading;
    private SmoothScroller scroller;

    // ---- 章内翻页 ----
    // 用 CSS 多列把正文流成"一列一屏", 翻页就是横向移一屏; 断行交给浏览器, 不会把文字切成半行.
    // 全靠 JS 在加载完成后设置, 不改 HTML 结构 (epub 的 html 五花八门, 硬塞标签容易出岔子).
    // pagerPages<=0 表示这台设备没按多列排出来 (老 WebView), 自动退回原来的上下滚动, 不至于没法读
    private int pagerPages = 0;
    private int pagerPage = 0;
    private static final int PAGER_PAD = 28; // 左右留白 (css px)
    private static final int PAGER_GAP = 56; // 列间距, 也就是翻页时相邻两屏之间的空隙

    // 建立分页并返回总页数; 返回 0 表示没分成
    private static final String PAGER_INIT =
            "(function(){try{"
                    + "var b=document.body,d=document.documentElement;"
                    + "if(!b){return 0}"
                    + "var pad=%1$d,gap=%2$d,colW=window.innerWidth-pad*2;"
                    + "if(colW<=0){return 0}"
                    + "b.style.margin='0';b.style.padding='0 '+pad+'px';"
                    + "b.style.boxSizing='border-box';b.style.height='100vh';"
                    + "b.style.columnWidth=colW+'px';b.style.columnGap=gap+'px';b.style.columnFill='auto';"
                    + "d.style.overflow='hidden';b.style.overflow='hidden';"
                    + "b.style.transform='translateX(0)';b.style.transition='none';"
                    + "window.__step=colW+gap;"
                    // 末列不带 gap, 补一个再除, 否则最后一页会被算漏
                    + "return Math.max(1,Math.round((b.scrollWidth+gap)/window.__step));"
                    + "}catch(e){return 0}})()";

    // 用 transform 平移而不是 scrollLeft: 根元素设了 overflow:hidden 后, 有的 WebView 会无视
    // scrollLeft; transform 是纯变换, 不依赖滚动容器. body 的左 padding 一起平移, 每翻一屏
    // 正好让下一列的左边缘落到原来 padding 的位置, 留白保持一致
    private static final String PAGER_GO =
            "(function(){try{document.body.style.transform='translateX('+(-(%1$d*(window.__step||0)))+'px)';"
                    + "return 1}catch(e){return 0}})()";
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
        pageLoading = findViewById(R.id.pageLoading);
        novelView = findViewById(R.id.webView);
        scroller = new SmoothScroller(novelView);
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

    // 章内位置的万分比 (0-10000): 翻页模式按页序, 退回滚动时按滚动位置.
    // 两种模式存的是同一个语义 (章内读到百分之几), 所以互相切换、跨版本都不会指错地方
    private int currentRatio() {
        if (pagerPages > 0) {
            return pagerPages <= 1 ? 0 : (int) (10000L * pagerPage / (pagerPages - 1));
        }
        @SuppressWarnings("deprecation")
        int max = Math.max(1, (int) (novelView.getContentHeight() * novelView.getScale()) - novelView.getHeight());
        return (int) (10000L * Math.max(0, Math.min(max, novelView.getScrollY())) / max);
    }

    // 内容排完后建立章内分页, 再按万分比跳到上次读到的位置
    private void setupPager(int offset) {
        var js = String.format(java.util.Locale.US, PAGER_INIT, PAGER_PAD, PAGER_GAP);
        novelView.evaluateJavascript(js, value -> {
            pagerPages = parseJsInt(value);
            if (pagerPages > 0) {
                int target = pagerPages <= 1 ? 0
                        : (int) Math.round((double) offset * (pagerPages - 1) / 10000.0);
                gotoPagerPage(target);
            } else {
                // 分页没生效: 退回滚动, 按万分比换算成像素 (老逻辑)
                @SuppressWarnings("deprecation")
                int max = Math.max(0, (int) (novelView.getContentHeight() * novelView.getScale()) - novelView.getHeight());
                novelView.scrollTo(0, (int) ((long) offset * max / 10000));
                updateProgressLabel();
            }
        });
    }

    private void gotoPagerPage(int n) {
        pagerPage = Math.max(0, Math.min(Math.max(0, pagerPages - 1), n));
        novelView.evaluateJavascript(String.format(java.util.Locale.US, PAGER_GO, pagerPage), null);
        updateProgressLabel();
    }

    // evaluateJavascript 回传的是 json 字面量, 拿不到数就当分页失败
    private static int parseJsInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(value.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // 页码区: 翻页模式给"本章第几页", 否则给章号; 后面统一跟全书百分比 —— 重排式排版下
    // 全书总页数本来就不存在 (字号一改页数就变), 能稳定表达的只有读到全书多少比例
    private void updateProgressLabel() {
        if (epub_book == null) {
            return;
        }
        int total = epub_book.total_pages();
        var pct = String.format(java.util.Locale.US, "%.1f",
                epub_book.progress(epub_book_page, currentRatio()) / 100.0);
        if (pagerPages > 0) {
            pageView.setText(getString(R.string.novel_pager, pagerPage + 1, pagerPages, pct));
        } else {
            pageView.setText(getString(R.string.novel_chapter, epub_book_page + 1, total, pct));
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        loadExecutor.shutdownNow();
        scroller.cancel(); // 先停动画再销毁 WebView, 避免动画回调摸已销毁的 view
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

    // 注入样式到 </head> 前 (没有 head 就拼在最前面): 图片尺寸约束 + 夜间色.
    // 图片必须限制在一屏内 —— 多列分页下超过列高的图会被生生切断, 且会把分页算歪
    private String decorate(String html) {
        var css = new StringBuilder("<style>img,svg{max-width:100% !important;max-height:100vh !important}</style>");
        if (dark) {
            css.append(DARK_CSS);
        }
        int i = html.toLowerCase().indexOf("</head>");
        if (i >= 0) {
            return html.substring(0, i) + css + html.substring(i);
        }
        return css + html;
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
                // 内容布局完成后再分页并恢复位置; 在 loadData 之前动位置会被加载重置, 恢复无效
                pagerPages = 0;
                pagerPage = 0;
                setupPager(page_offset);
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
        // 上下键: 翻页模式下没有纵向滚动可言 (每列正好一屏高), 改成跳章;
        // 退回滚动模式时仍是原来的短按滚一屏 / 长按巡航
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            int dir = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
            if (pagerPages > 0) {
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    changeChapter(dir);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    scroller.pageScroll(dir);
                } else {
                    scroller.startCruise(dir);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                scroller.stopCruise();
            }
            return true;
        }
        // 左右键: 翻页模式翻章内的页, 翻到头自动跨到相邻章; 否则直接翻章
        if (action == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            if (pagerPages > 0) {
                int next = pagerPage + dir;
                if (next >= 0 && next < pagerPages) {
                    gotoPagerPage(next);
                    return true;
                }
            }
            changeChapter(dir);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }


    // 换章: 往后翻落在新章第一页, 往前翻落在上一章最后一页 (才像连续往回读)
    private void changeChapter(int dir) {
        int total = epub_book.total_pages();
        if (dir > 0) {
            if (epub_book_page >= total - 1) {
                return;
            }
            epub_book_page++;
            notifyPageChanged(0);
        } else {
            if (epub_book_page <= 0) {
                return;
            }
            epub_book_page--;
            notifyPageChanged(10000);
        }
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
        // 换章时停掉滚动动画, 别和新页的位置恢复打架
        scroller.cancel();
        // 章节要从网络拉时会卡一下, 转个圈让人知道在加载 (调用方都在主线程)
        pageLoading.setVisibility(android.view.View.VISIBLE);
        // 丢弃排队中的过时预取区间, 把带宽让给当前章 (同 ComicActivity)
        taskQueue.clear();
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
                pageLoading.setVisibility(android.view.View.GONE);
                // 新章还没排版, 先清掉上一章的分页状态, 否则页码会闪一下上一章的页数
                pagerPages = 0;
                pagerPage = 0;
                if (html != null) {
                    show_new_page(html, page_offset);
                }
                // 这时只能按章号显示; 分页建立后 setupPager 会再刷成本章页码
                updateProgressLabel();
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
                    // 有持久化索引时 0 次网络往返完成开书 (epub / cbz 按后缀分派)
                    epub_book = Books.open(client.to_json(), book_uri, client, db);
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
