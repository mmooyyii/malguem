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
}
