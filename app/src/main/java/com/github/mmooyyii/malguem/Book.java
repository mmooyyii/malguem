package com.github.mmooyyii.malguem;

import java.io.IOException;
import java.util.Map;

import io.documentnode.epub4j.domain.Resource;

public interface Book {
    String page(int page);

    void prepare(int from, int to);

    int total_pages();

    byte[] GetResource(String filename) throws Exception;

    String GetMediaType(String filename);
}
