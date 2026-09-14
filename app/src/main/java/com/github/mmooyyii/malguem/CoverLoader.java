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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    // 进程内记下"挖不出封面"的目录: 目录封面要先 ls, 不记的话 RecyclerView 每绑一次就重来一趟
    private final Set<String> barren = ConcurrentHashMap.newKeySet();

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
                    // 有持久化索引时 0 次往返完成初始化, 只为封面本体发一次请求
                    byte[] bytes = Books.open(namespace, uri, client, db).cover();
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

    // 目录封面: 拿目录里第一本书的封面顶掉文件夹图标.
    // 比书封面多一次 ls 往返, 所以同样按目录 key 落盘, 第二次进来就直接命中不再 ls
    public void loadDir(String namespace, int resourceId, List<String> path,
                        ResourceInterface client, ImageView target) {
        final String key = dirKeyOf(namespace, path);
        target.setTag(R.id.cover_key_tag, key);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            show(target, key, cached);
            return;
        }
        target.setVisibility(View.GONE);
        if (barren.contains(key)) {
            return; // 这个目录已经确认挖不出封面, 露出文件夹图标就好
        }
        pool.execute(() -> {
            Bitmap bmp = readDisk(key);
            if (bmp == null) {
                try {
                    var uri = firstBookIn(resourceId, path, client);
                    if (uri == null) {
                        barren.add(key); // 目录里确实没有书
                    } else {
                        byte[] bytes = Books.open(namespace, uri, client, db).cover();
                        bmp = bytes == null ? null : decode(bytes);
                        if (bmp == null) {
                            barren.add(key); // 书本身没有封面图, 再试也是这个结果
                        } else {
                            writeDisk(key, bmp);
                        }
                    }
                } catch (Exception ignore) {
                    // 网络/解析失败不记 barren, 下次滚过来还能再试
                }
            }
            if (bmp != null) {
                memory.put(key, bmp);
            }
            final Bitmap result = bmp;
            main.post(() -> show(target, key, result));
        });
    }

    // 先在本层找书; 整层都是子目录时 (漫画库常见的 "书库/系列/卷01.epub") 再下钻一层.
    // 只钻一层: 电视上每多一层就多一次往返, 而再深的书跟这个文件夹也没什么代表性了
    private String firstBookIn(int resourceId, List<String> path, ResourceInterface client) throws Exception {
        String firstSub = null;
        for (var it : client.ls(resourceId, path)) {
            if (it.type == ListItem.FileType.Epub) {
                return uriOf(path, it.name);
            }
            if (it.type == ListItem.FileType.Dir && firstSub == null) {
                firstSub = it.name;
            }
        }
        if (firstSub == null) {
            return null;
        }
        var sub = new ArrayList<>(path);
        sub.add(firstSub);
        for (var it : client.ls(resourceId, sub)) {
            if (it.type == ListItem.FileType.Epub) {
                return uriOf(sub, it.name);
            }
        }
        return null;
    }

    // 与 MainActivity.make_uri 保持一致: /目录/.../文件名
    private String uriOf(List<String> path, String name) {
        var sb = new StringBuilder();
        for (var p : path) {
            sb.append("/").append(p);
        }
        return sb.append("/").append(name).toString();
    }

    // 走同一个 keyOf 但输入带 "dir:" 前缀: 真实 uri 一定以 "/" 开头, 不会和书的 key 撞上
    private String dirKeyOf(String namespace, List<String> path) {
        return keyOf(namespace, "dir:" + String.join("/", path));
    }

    // 清理缓存时调用, 磁盘文件由调用方删除
    public void clearMemory() {
        memory.evictAll();
        barren.clear();
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
