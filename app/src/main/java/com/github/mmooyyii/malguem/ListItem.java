package com.github.mmooyyii.malguem;


public class ListItem {

    public enum FileType {
        Resource, Dir, Epub, AddWebDav, RecentEpub
    }

    public enum ViewType {
        Novel, Comic
    }

    int id; // Resource 行是数据源 id; Epub/RecentEpub 行是所属数据源 id
    String name;
    String uri; // epub 的完整路径 (pwd + name), 用于加载封面/打开
    String ns; // RecentEpub 专用: 所属数据源的 namespace json, 首页混排时按条目加载封面
    FileType type;
    int resource_type; // 数据源类型 (1=webdav 2=smb 3=local), 仅 Resource 行用于选图标

    ViewType view_type;

    int read_to_page;
    int total_page;

    public ListItem(int id, String name, FileType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public ListItem() {

    }
}
