package com.github.mmooyyii.malguem;


import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;


public interface ResourceInterface {
    List<ListItem> ls(int id, List<String> path) throws Exception;

    String to_json();

    static ResourceInterface from_json(String json) {
        // 不用 TypeToken: 它依赖匿名子类的泛型签名, R8 full mode 会剥掉签名导致 fromJson
        // 退化返回 LinkedTreeMap, 赋值处 ClassCastException (v1.7.0 进/编辑数据源闪退的根因)
        var obj = JsonParser.parseString(json).getAsJsonObject();
        var map = new HashMap<String, String>();
        for (var e : obj.entrySet()) {
            map.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
        }
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
}

