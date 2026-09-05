package com.github.mmooyyii.malguem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 异步加载 epub 封面: 内存 LRU + 磁盘缓存, 基于 tag 处理 RecyclerView 复用
public class CoverLoader {

    private static CoverLoader instance;

    public static synchronized CoverLoader get(Context ctx) {
        if (instance == null) {
            instance = new CoverLoader(ctx.getApplicationContext());
        }
        return instance;
    }

    private static final int TARGET_W = 320; // 缩略图目标宽度(px), 封面无需原图那么大

    private final LruCache<String, Bitmap> memory;
    private final File diskDir;
    private final Database.DatabaseHelper db;
    // 封面加载是纯网络 IO 且链路延迟高, 并发放宽到 6
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Handler main = new Handler(Looper.getMainLooper());

    private CoverLoader(Context ctx) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        memory = new LruCache<String, Bitmap>(maxKb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(ctx.getCacheDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
        db = Database.getInstance(ctx).getDatabase();
    }

    // namespace 用于区分不同服务器/账号 (同名路径不冲突); target 加载成功显示, 失败隐藏(露出兜底封面)
    public void load(String namespace, String uri, ResourceInterface client, ImageView target) {
        // pdf 没有流式取封面的能力 (要整本下载), 直接用兜底书封
        if (uri != null && uri.toLowerCase().endsWith(".pdf")) {
            target.setTag(R.id.cover_key_tag, null);
            target.setVisibility(View.GONE);
            return;
        }
        final String key = keyOf(namespace, uri);
        target.setTag(R.id.cover_key_tag, key);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            show(target, key, cached);
            return;
        }
        target.setVisibility(View.GONE);
        pool.execute(() -> {
            Bitmap bmp = readDisk(key);
            if (bmp == null) {
                try {
                    // 有持久化索引时 0 次往返完成 epub 初始化, 只为封面本体发一次请求
                    byte[] bytes = LazyEpub.open(namespace, uri, client, db).cover();
                    if (bytes != null) {
                        bmp = decode(bytes);
                        if (bmp != null) {
                            writeDisk(key, bmp);
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            if (bmp != null) {
                memory.put(key, bmp);
            }
            final Bitmap result = bmp;
            main.post(() -> show(target, key, result));
        });
    }

    // 清理缓存时调用, 磁盘文件由调用方删除
    public void clearMemory() {
        memory.evictAll();
    }

    // 删除单本书的封面缓存 (内存 + 磁盘)
    public void removeCover(String namespace, String uri) {
        var key = keyOf(namespace, uri);
        memory.remove(key);
        //noinspection ResultOfMethodCallIgnored
        fileOf(key).delete();
    }

    private void show(ImageView target, String key, Bitmap bmp) {
        if (!key.equals(target.getTag(R.id.cover_key_tag))) {
            return; // 该 view 已被复用到别的条目
        }
        if (bmp != null) {
            target.setImageBitmap(bmp);
            target.setVisibility(View.VISIBLE);
        } else {
            target.setImageDrawable(null);
            target.setVisibility(View.GONE);
        }
    }

    private String keyOf(String namespace, String uri) {
        return Integer.toHexString((namespace + "|" + uri).hashCode());
    }

    private File fileOf(String key) {
        return new File(diskDir, key + ".jpg");
    }

    private Bitmap readDisk(String key) {
        File f = fileOf(key);
        if (!f.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private void writeDisk(String key, Bitmap bmp) {
        try (FileOutputStream fos = new FileOutputStream(fileOf(key))) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        } catch (Exception ignore) {
        }
    }

    private Bitmap decode(byte[] bytes) {
        var bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        if (bounds.outWidth > TARGET_W) {
            sample = Math.max(1, Integer.highestOneBit(bounds.outWidth / TARGET_W));
        }
        var opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }
}
