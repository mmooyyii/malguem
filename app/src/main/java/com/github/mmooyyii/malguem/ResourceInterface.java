package com.github.mmooyyii.malguem;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.List;


public interface ResourceInterface {
    List<ListItem> ls(int id, List<String> path) throws Exception;

    String to_json();

    static ResourceInterface from_json(String json) {
        Gson gson = new Gson();
        var type = new TypeToken<HashMap<String, String>>() {
        }.getType();
        HashMap<String, String> map = gson.fromJson(json, type);
        return new WebdavResource(map.get("url"), map.get("username"), map.get("passwd"));
    }

    byte[] open(String uri, Slice slice) throws Exception;

    HashMap<Slice, byte[]> open(String uri, List<Slice> slice) throws Exception;
}

