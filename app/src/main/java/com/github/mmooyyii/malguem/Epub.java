package com.github.mmooyyii.malguem;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubReader;
import okhttp3.Response;

import java.io.ByteArrayInputStream;


import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Epub {

    Book book;
    List<Resource> contents;


    public Epub(byte[] bytes) throws IOException {
        var reader = new EpubReader();
        var stream = new ByteArrayInputStream(bytes);
        book = reader.readEpub(stream);
        contents = book.getContents();
    }

    public byte[] page(int page) throws IOException {
        var data = contents.get(page).getData();
        return data;
    }

    public int total_pages() {
        return contents.size();
    }

    public Map<String, Resource> resource_map() {
        return book.getResources().getResourceMap();
    }
}