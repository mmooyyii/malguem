package com.github.mmooyyii.malguem;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// 局域网扫描的端口清单: 扫哪些端口、扫到之后当成哪种数据源、URL 该拼什么路径.
// 内置常见服务的默认端口, 主人可在"扫描设置"里开关或自己加 (端口不标准的自建服务).
// 存 SharedPreferences 的一段 json, 手解手拼 —— 反序列化模型交给 Gson 反射会被 R8 吃掉字段 (见 CLAUDE.md)
public class ScanPort {

    public static final String KIND_WEBDAV = "webdav";
    public static final String KIND_SMB = "smb";
    public static final String KIND_OPDS = "opds";

    private static final String PREF = "settings";
    private static final String KEY = "scan_ports";

    public final String name;
    public final int port;
    public final String kind;
    public final String path; // 拼进 URL 的路径, smb 用不上
    public boolean on;
    public final boolean builtin; // 内置项只能停用不能删

    public ScanPort(String name, int port, String kind, String path, boolean on, boolean builtin) {
        this.name = name;
        this.port = port;
        this.kind = kind;
        this.path = path == null ? "" : path;
        this.on = on;
        this.builtin = builtin;
    }

    // 内置清单: alist/SMB 之外, OPDS 三家书库的默认端口 (Komga 25600 / Kavita 5000 / Calibre-Web 8083)
    public static List<ScanPort> defaults() {
        var out = new ArrayList<ScanPort>();
        out.add(new ScanPort("alist (WebDAV)", 5244, KIND_WEBDAV, "/dav", true, true));
        out.add(new ScanPort("SMB", 445, KIND_SMB, "", true, true));
        out.add(new ScanPort("Komga (OPDS)", 25600, KIND_OPDS, "/opds/v1.2/catalog", true, true));
        out.add(new ScanPort("Kavita (OPDS)", 5000, KIND_OPDS, "/api/opds", true, true));
        out.add(new ScanPort("Calibre-Web (OPDS)", 8083, KIND_OPDS, "/opds", true, true));
        return out;
    }

    public static List<ScanPort> load(Context ctx) {
        var raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        var out = new ArrayList<ScanPort>();
        try {
            var arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                var o = arr.getJSONObject(i);
                out.add(new ScanPort(o.optString("name"), o.optInt("port"), o.optString("kind"),
                        o.optString("path"), o.optBoolean("on", true), o.optBoolean("builtin", false)));
            }
        } catch (Exception e) {
            return defaults();
        }
        // 版本升级后新增的内置项补进来, 免得老用户永远扫不到新服务
        for (var d : defaults()) {
            boolean has = false;
            for (var p : out) {
                if (p.port == d.port && p.kind.equals(d.kind)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                out.add(d);
            }
        }
        return out;
    }

    public static void save(Context ctx, List<ScanPort> ports) {
        var arr = new JSONArray();
        try {
            for (var p : ports) {
                var o = new JSONObject();
                o.put("name", p.name);
                o.put("port", p.port);
                o.put("kind", p.kind);
                o.put("path", p.path);
                o.put("on", p.on);
                o.put("builtin", p.builtin);
                arr.put(o);
            }
        } catch (Exception ignore) {
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }

    // 扫描要用的端口 (去重: 同一端口被两个条目占用时只扫一次, 命中后按端口反查条目)
    public static int[] enabledPorts(List<ScanPort> ports) {
        var list = new ArrayList<Integer>();
        for (var p : ports) {
            if (p.on && !list.contains(p.port)) {
                list.add(p.port);
            }
        }
        var out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    public static ScanPort byPort(List<ScanPort> ports, int port) {
        for (var p : ports) {
            if (p.on && p.port == port) {
                return p;
            }
        }
        return null;
    }

    // 扫到的服务拼成添加数据源时预填的地址
    public String url(String ip) {
        return "http://" + ip + ":" + port + path;
    }
}
