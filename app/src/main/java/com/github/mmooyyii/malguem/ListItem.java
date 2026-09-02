package com.github.mmooyyii.malguem;


public class ListItem {

    public enum FileType {
        Resource, Dir, Epub, AddWebDav
    }

    public enum ViewType {
        Novel, Comic
    }

    int id;
    String name;
    String uri; // epub 的完整路径 (pwd + name), 用于加载封面/打开
    FileType type;

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
