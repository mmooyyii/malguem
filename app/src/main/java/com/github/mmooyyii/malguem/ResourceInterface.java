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
        String t = map.get("type");
        if ("smb".equals(t)) {
            return SmbResource.fromMap(map);
        }
        if ("local".equals(t)) {
            return LocalResource.fromMap(map);
        }
        if ("opds".equals(t)) {
            return OpdsResource.fromMap(map);
        }
        // 缺省(含没有 type 字段的旧数据)按 webdav 处理
        return new WebdavResource(map.get("url"), map.get("username"), map.get("passwd"));
    }

    byte[] open(String uri, Slice slice) throws Exception;

    HashMap<Slice, byte[]> open(String uri, List<Slice> slice) throws Exception;

    // 文件总字节数, 拿不到时返回 -1 (用于下载进度百分比, 不影响功能)
    default long size(String uri) throws Exception {
        return -1;
    }
}

