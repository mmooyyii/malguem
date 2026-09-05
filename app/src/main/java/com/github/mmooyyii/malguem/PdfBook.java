package com.github.mmooyyii.malguem;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.function.LongConsumer;

// PDF 阅读: PdfRenderer 只认本地文件, 没法像 epub 那样流式读, 所以整本下载到 cache/books 后按页渲染.
// 每页 html 只有一个 <img>, 图片字节由 GetResource 按需渲染, 天然适配 Comic/Novel 两种界面与单页/RTL.
// "整本下载"对网盘后端反而是友好模式: 一次顺序大流量, 不会触发高频小请求的风控
public class PdfBook implements Book {

    private static final int CHUNK = 8 * 1024 * 1024; // 整本下载的分块大小
    private static final int RENDER_WIDTH = 1440;     // 渲染宽度(px), 1080p 电视双栏/单栏都够清晰
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024; // 渲染结果内存缓存上限

    private final PdfRenderer renderer;
    private final ParcelFileDescriptor pfd;
    // 页码 -> 渲染出的 jpeg 字节, 带容量上限的 LRU
    private final LinkedHashMap<Integer, byte[]> pages = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes = 0;
    private boolean closed = false;

    private PdfBook(File file) throws Exception {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pfd);
    }

    // 下载(或复用本地缓存)后打开; progress 收到已下载的字节数
    public static PdfBook open(String namespace, String uri, ResourceInterface client,
                               File cacheDir, LongConsumer progress) throws Exception {
        var dir = new File(cacheDir, "books");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var key = Integer.toHexString((namespace + "|" + uri).hashCode());
        var file = new File(dir, key + ".pdf");
        if (file.length() == 0) {
            // 先写 .part 再原子改名, 避免下到一半的文件被当成完整缓存
            var tmp = new File(dir, key + ".part");
            download(client, uri, tmp, progress);
            if (!tmp.renameTo(file)) {
                throw new IOException("无法写入缓存: " + file);
            }
        }
        try {
            return new PdfBook(file);
        } catch (Exception e) {
            // 打不开说明缓存损坏(比如旧版下载中断), 删掉让下次重新下载
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
    }

    // 按 8MB 分块顺序拉整本: 三种数据源在文件尾都会截断返回, 精确越界读则可能报 416, 都视为读完
    private static void download(ResourceInterface client, String uri, File out, LongConsumer progress) throws Exception {
        long off = 0;
        try (var fos = new FileOutputStream(out)) {
            while (true) {
                var s = new Slice();
                s.offset = Math.toIntExact(off);
                s.size = CHUNK;
                byte[] part;
                try {
                    part = client.open(uri, s);
                } catch (Exception e) {
                    if (off > 0) {
                        break; // 上一块是整块且正好读到文件尾, 这次越界读报错属正常
                    }
                    throw e;
                }
                if (part == null || part.length == 0) {
                    break;
                }
                fos.write(part);
                off += part.length;
                progress.accept(off);
                if (part.length < CHUNK) {
                    break;
                }
            }
        }
        if (off == 0) {
            throw new IOException("下载到空文件: " + uri);
        }
    }

    @Override
    public String page(int page_num) {
        return "<html><head><meta name=\"viewport\" content=\"width=device-width\"/></head>"
                + "<body style=\"margin:0\">"
                + "<img style=\"width:100%\" src=\"/__pdf__/" + page_num + ".jpg\"/></body></html>";
    }

    @Override
    public void prepare(int from, int to) {
        for (int i = from; i < to; i++) {
            try {
                renderPage(i);
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    public synchronized int total_pages() {
        return closed ? 0 : renderer.getPageCount();
    }

    @Override
    public byte[] GetResource(String filename) throws Exception {
        // 形如 __pdf__/3.jpg, 取中间的页码
        var name = filename;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.indexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        return renderPage(Integer.parseInt(name));
    }

    @Override
    public String GetMediaType(String filename) {
        return "image/jpeg";
    }

    @Override
    public synchronized void close() {
        closed = true;
        pages.clear();
        cacheBytes = 0;
        try {
            renderer.close();
            pfd.close();
        } catch (Exception ignore) {
        }
    }

    // PdfRenderer 非线程安全且同时只能开一页, 渲染整个串行化
    private synchronized byte[] renderPage(int index) throws Exception {
        if (closed) {
            throw new IllegalStateException("book closed");
        }
        var hit = pages.get(index);
        if (hit != null) {
            return hit;
        }
        try (var page = renderer.openPage(index)) {
            int w = RENDER_WIDTH;
            int h = Math.max(1, (int) ((long) page.getHeight() * w / Math.max(1, page.getWidth())));
            var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE); // pdf 背景可能是透明的, 垫白避免文字页发花
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            var out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            bmp.recycle();
            var bytes = out.toByteArray();
            cachePut(index, bytes);
            return bytes;
        }
    }

    private void cachePut(int index, byte[] bytes) {
        var old = pages.put(index, bytes);
        if (old != null) {
            cacheBytes -= old.length;
        }
        cacheBytes += bytes.length;
        var it = pages.entrySet().iterator();
        while (cacheBytes > MAX_CACHE_BYTES && pages.size() > 1 && it.hasNext()) {
            var eldest = it.next();
            cacheBytes -= eldest.getValue().length;
            it.remove();
        }
    }
}
