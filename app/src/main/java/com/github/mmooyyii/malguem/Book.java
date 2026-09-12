package com.github.mmooyyii.malguem;

import java.util.Collections;
import java.util.List;

public interface Book {
    String page(int page);

    void prepare(int from, int to);

    int total_pages();

    byte[] GetResource(String filename) throws Exception;

    String GetMediaType(String filename);

    // 封面图片字节, 没有封面返回 null
    byte[] cover() throws Exception;

    // 目录: 标题 -> spine 页码
    class TocEntry {
        public final String title;
        public final int page;

        public TocEntry(String title, int page) {
            this.title = title;
            this.page = page;
        }
    }

    default List<TocEntry> toc() {
        return Collections.emptyList();
    }

    // 全书进度万分比 (0-10000). page 是当前 spine 项, inner 是项内位置万分比.
    // 小说重排后没有稳定的总页数 (字号一改页数就变), 所以进度只能按"读到全书多少比例"表示.
    // 默认按项号均分, epub 覆盖成按各章长度加权 —— 章长差很多时均分的进度会忽快忽慢
    default int progress(int page, int inner) {
        int total = total_pages();
        if (total <= 0) {
            return 0;
        }
        long pos = (long) page * 10000 + Math.max(0, Math.min(10000, inner));
        return (int) Math.max(0, Math.min(10000, pos / total));
    }
}
