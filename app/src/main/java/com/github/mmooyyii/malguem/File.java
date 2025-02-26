package com.github.mmooyyii.malguem;


enum FileType {
    Resource, Dir, Epub
}

public class File {

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
