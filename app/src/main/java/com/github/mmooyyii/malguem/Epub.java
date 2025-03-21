package com.github.mmooyyii.malguem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubReader;

public class Epub implements Book {

    io.documentnode.epub4j.domain.Book book;
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
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "无法读取页面";
        }
    }

    @Override
    public void prepare(int from, int to) {

    }

    public int total_pages() {
        return contents.size();
    }

    public byte[] GetResource(String filename) throws IOException {
        return Objects.requireNonNull(book.getResources().getResourceMap().get(filename)).getData();
    }

    public String GetMediaType(String filename) {
        return Objects.requireNonNull(book.getResources().getResourceMap().get(filename)).getMediaType().getName();
    }
}