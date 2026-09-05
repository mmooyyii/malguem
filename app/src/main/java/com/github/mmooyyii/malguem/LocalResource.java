package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

// 本地硬盘/USB 数据源, RandomAccessFile 支持按 range 随机读 (需 MANAGE_EXTERNAL_STORAGE 权限)
public class LocalResource implements ResourceInterface {

    final String root; // 包内可见: MainActivity 编辑数据源时要读出来做预填

    LocalResource(String root) {
        this.root = root == null ? "" : root;
    }

    static LocalResource fromMap(HashMap<String, String> m) {
        return new LocalResource(m.get("root"));
    }

    @Override
    public List<ListItem> ls(int resource_id, List<String> path) throws Exception {
        var out = new ArrayList<ListItem>();
        var dir = new File(root, String.join("/", path));
        var children = dir.listFiles();
        if (children == null) {
            throw new IOException("无法读取本地目录(可能无权限): " + dir);
        }
        for (var f : children) {
            if (f.isDirectory()) {
                out.add(new ListItem(resource_id, f.getName(), ListItem.FileType.Dir));
            } else if (f.getName().toLowerCase().endsWith(".epub") || f.getName().toLowerCase().endsWith(".pdf")) {
                out.add(new ListItem(resource_id, f.getName(), ListItem.FileType.Epub));
            }
        }
        return out;
    }

    @Override
    public String to_json() {
        var map = new HashMap<String, String>();
        map.put("type", "local");
        map.put("root", root);
        return new Gson().toJson(map);
    }

    @Override
    public byte[] open(String uri, Slice slice) throws Exception {
        var slices = new ArrayList<Slice>();
        slices.add(slice);
        return open(uri, slices).get(slice);
    }

    @Override
    public HashMap<Slice, byte[]> open(String uri, List<Slice> slices) throws Exception {
        var out = new HashMap<Slice, byte[]>();
        var f = new File(root, uri.startsWith("/") ? uri.substring(1) : uri);
        try (var raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            for (var slice : slices) {
                long off = slice.offset;
                if (off < 0) {
                    off = len + off; // 后缀 range
                }
                if (off < 0) {
                    off = 0;
                }
                int size = slice.size == null ? (int) (len - off) : slice.size;
                if (off + size > len) {
                    size = (int) (len - off);
                }
                byte[] buf = new byte[Math.max(0, size)];
                raf.seek(off);
                raf.readFully(buf);
                out.put(slice, buf);
            }
        }
        return out;
    }
}
