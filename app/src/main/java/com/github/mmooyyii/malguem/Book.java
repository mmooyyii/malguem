package com.github.mmooyyii.malguem;

public interface Book {
    String page(int page);

    void prepare(int from, int to);

    int total_pages();

    byte[] GetResource(String filename) throws Exception;

    String GetMediaType(String filename);
}
