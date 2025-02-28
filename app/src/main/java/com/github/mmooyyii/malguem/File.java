package com.github.mmooyyii.malguem;


public class File {

    public enum FileType {
        Resource, Dir, Epub
    }

    public enum ViewType {
        Novel, Comic
    }

    int id = 0;
    String name;
    FileType type;

    ViewType view_type;

    public File(int i, String s, FileType fileType) {
        id = i;
        name = s;
        type = fileType;
    }
}
