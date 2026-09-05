package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

// SMB/CIFS 数据源 (基于 jcifs-ng), 通过 SmbRandomAccessFile 支持按 range 随机读
public class SmbResource implements ResourceInterface {

    // 包内可见: MainActivity 编辑数据源时要读出来做预填
    final String host;
    final String share;
    final String username;
    final String password;
    final String domain;
    private final String baseUrl; // smb://host/share

    private transient CIFSContext cifs;

    SmbResource(String host, String share, String username, String password, String domain) {
        this.host = host == null ? "" : host;
        this.share = share == null ? "" : share;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.domain = domain == null ? "" : domain;
        this.baseUrl = "smb://" + this.host + "/" + this.share;
    }

    static SmbResource fromMap(HashMap<String, String> m) {
        return new SmbResource(m.get("host"), m.get("share"), m.get("username"), m.get("password"), m.get("domain"));
    }

    private CIFSContext ctx() throws CIFSException {
        if (cifs == null) {
            Properties p = new Properties();
            // 允许 SMB2/3, 兼容多数 NAS/群晖/Windows 共享
            p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            CIFSContext base = new BaseContext(new PropertyConfiguration(p));
            cifs = base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password));
        }
        return cifs;
    }

    @Override
    public List<ListItem> ls(int resource_id, List<String> path) throws Exception {
        var dirs = new ArrayList<ListItem>();
        var sb = new StringBuilder(baseUrl);
        for (var p : path) {
            sb.append("/").append(p);
        }
        sb.append("/");
        SmbFile dir = new SmbFile(sb.toString(), ctx());
        SmbFile[] children = dir.listFiles();
        if (children == null) {
            throw new IOException("SMB 无法读取目录: " + sb);
        }
        for (var f : children) {
            var name = f.getName(); // 目录名以 "/" 结尾
            if (f.isDirectory()) {
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                dirs.add(new ListItem(resource_id, name, ListItem.FileType.Dir));
            } else if (name.toLowerCase().endsWith(".epub")) {
                dirs.add(new ListItem(resource_id, name, ListItem.FileType.Epub));
            }
        }
        return dirs;
    }

    @Override
    public String to_json() {
        var map = new HashMap<String, String>();
        map.put("type", "smb");
        map.put("host", host);
        map.put("share", share);
        map.put("username", username);
        map.put("password", password);
        map.put("domain", domain);
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
        SmbFile f = new SmbFile(baseUrl + uri, ctx());
        long len = f.length();
        try (SmbRandomAccessFile raf = new SmbRandomAccessFile(f, "r")) {
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
                    size = (int) (len - off); // 越界读则截到文件尾
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
