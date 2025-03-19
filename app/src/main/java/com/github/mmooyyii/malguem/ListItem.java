package com.github.mmooyyii.malguem;


public class ListItem {

    public enum FileType {
        Resource, Dir, Epub, AddWebDav, SetCache, ClearCache, UseEpubStream
    }

    public enum ViewType {
        Novel, Comic
    }

    int id;
    String name;
    FileType type;

    ViewType view_type;

    public ListItem(int i, String s, FileType fileType) {
        id = i;
        name = s;
        type = fileType;
    }
}
