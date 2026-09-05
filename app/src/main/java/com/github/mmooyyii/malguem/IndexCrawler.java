package com.github.mmooyyii.malguem;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// 启动后在后台把各数据源里的书补建 epub 索引, 并回收孤儿:
// 1) 数据源已删除/配置已变更 -> 该 namespace 下所有索引与封面直接删;
// 2) 书已不在服务器上 -> 仅在该数据源完整遍历成功后, 删掉不在存活集合里的行 (部分失败不删, 防误删)
public class IndexCrawler {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final int MAX_DIRS = 500;              // 单资源最多遍历目录数, 防失控
    private static final int MAX_NEW_INDEX = 200;         // 单次启动最多新建索引数
    private static final int MAX_CONSECUTIVE_FAILS = 3;   // 连续建索引失败次数上限, 网络不通时尽早放弃

    // 每个进程只跑一轮, 低优先级后台线程
    public static void start(Context ctx) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        var app = ctx.getApplicationContext();
        var t = new Thread(() -> run(app), "epub-index-crawler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run(Context ctx) {
        var db = Database.getInstance(ctx).getDatabase();
        var covers = CoverLoader.get(ctx);
        var clients = new ArrayList<ResourceInterface>();
        var namespaces = new HashSet<String>();
        for (var res : db.resource_list()) {
            var client = db.get_resource(res.id);
            if (client != null) {
                clients.add(client);
                namespaces.add(client.to_json());
            }
        }
        // 孤儿回收 1: 数据源已删除或配置已变更
        for (var row : db.epub_index_rows()) {
            if (!namespaces.contains(row[0])) {
                db.delete_epub_index(row[0], row[1]);
                covers.removeCover(row[0], row[1]);
            }
        }
        int budget = MAX_NEW_INDEX;
        for (var client : clients) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            var ns = client.to_json();
            var live = new HashSet<String>();
            boolean complete = crawl(client, live);
            // 补建缺失的索引; 连续失败多次说明网络有问题, 放弃该资源
            int fails = 0;
            for (var uri : live) {
                if (budget <= 0 || fails >= MAX_CONSECUTIVE_FAILS || Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (db.get_epub_index(ns, uri) != null) {
                    continue;
                }
                try {
                    var book = new LazyEpub(uri, client);
                    db.put_epub_index(ns, uri, book.index_json());
                    budget--;
                    fails = 0;
                } catch (Exception e) {
                    fails++;
                }
            }
            // 孤儿回收 2: 只有完整遍历成功才敢删
            if (complete) {
                for (var row : db.epub_index_rows()) {
                    if (row[0].equals(ns) && !live.contains(row[1])) {
                        db.delete_epub_index(row[0], row[1]);
                        covers.removeCover(row[0], row[1]);
                    }
                }
            }
        }
    }

    // BFS 遍历目录树, 收集所有 epub 的 uri (与 MainActivity.make_uri 一致的 "/a/b/c.epub" 形式); 返回是否完整遍历
    private static boolean crawl(ResourceInterface client, HashSet<String> live) {
        var queue = new ArrayDeque<List<String>>();
        queue.add(new ArrayList<>());
        int dirs = 0;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || ++dirs > MAX_DIRS) {
                return false;
            }
            var pwd = queue.poll();
            List<ListItem> files;
            try {
                files = client.ls(0, pwd);
            } catch (Exception e) {
                return false; // 任一目录读不到都视为不完整, 本轮禁止回收
            }
            for (var f : files) {
                if (f.type == ListItem.FileType.Dir) {
                    var next = new ArrayList<>(pwd);
                    next.add(f.name);
                    queue.add(next);
                } else if (f.type == ListItem.FileType.Epub) {
                    var sb = new StringBuilder();
                    for (var p : pwd) {
                        sb.append("/").append(p);
                    }
                    sb.append("/").append(f.name);
                    live.add(sb.toString());
                }
            }
        }
        return true;
    }
}
