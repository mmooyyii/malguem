package com.github.mmooyyii.malguem;

public class ReadHistory {

    public int current_page;

    public int page_offset;

    public ListItem.ViewType view_type = ListItem.ViewType.Comic;

    public boolean rtl; // 漫画从右到左阅读(日漫)

    public boolean single_page; // 漫画单页模式(一屏一页, 跨页大图用)
}
