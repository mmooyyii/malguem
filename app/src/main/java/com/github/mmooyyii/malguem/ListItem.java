package com.github.mmooyyii.malguem;


public class ListItem {

    enum FileType {
        Resource, Dir, Epub, AddWebDav, SetCache, ClearCache
    }

    public enum ViewType {
        Novel, Comic
    }

    int id = 0;
    String name;
    FileType type;

    ViewType view_type;

    public ListItem(int i, String s, FileType fileType) {
        id = i;
        name = s;
        type = fileType;
    }
}
