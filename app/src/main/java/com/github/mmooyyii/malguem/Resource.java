package com.github.mmooyyii.malguem;


import java.util.List;

import okhttp3.Response;


class Slice {
    Integer offset;
    Integer size;


    public String to_string() {
        if (offset < 0 && size == null) {
            return String.valueOf(offset);
        }
        if (size == null) {
            return offset + "-";
        }
        return offset + "-" + (offset + size - 1);
    }
}

interface ResourceInterface {
    List<File> ls(int id, List<String> path) throws Exception;

    byte[] open(String uri) throws Exception;

    byte[] open(String uri, Slice slice) throws Exception;
}

