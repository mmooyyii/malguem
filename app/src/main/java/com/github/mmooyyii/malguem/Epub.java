package com.github.mmooyyii.malguem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubReader;

public class Epub {

    Book book;
    List<Resource> contents;


    public Epub(byte[] bytes) throws IOException {
        var reader = new EpubReader();
        var stream = new ByteArrayInputStream(bytes);
        book = reader.readEpub(stream);
        contents = book.getContents();
    }


    public String page(int page) {
        try {
            var bytes = contents.get(page).getData();
            return new String(bytes, "UTF-8");
        } catch (IOException e) {
            return "无法读取页面";
        }
    }

    public int total_pages() {
        return contents.size();
    }

    public Map<String, Resource> resource_map() {
        return book.getResources().getResourceMap();
    }
}