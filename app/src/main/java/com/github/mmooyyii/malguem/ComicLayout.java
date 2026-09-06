package com.github.mmooyyii.malguem;

import com.google.gson.Gson;

// 漫画布局配置, 按书序列化进 epub.layout_json; 字段名就是 json key (proguard 已 keep, 改名要慎重)
public class ComicLayout {

    int fit = 0;              // 图片适配: 0=适应宽度(可滚动) 1=适应整页(一屏放下)
    int margin = 1;           // 边距: 0=贴合 1=标准 2=宽松
    int bg = 0;               // 背景: 0=纸白 1=深灰 2=纯黑
    boolean shift = false;    // 对页错位修正: 第 1 页单独成屏, 配对从第 2 页开始
    boolean hidePage = false; // 隐藏页码指示

    static ComicLayout from_json(String json) {
        if (json == null || json.isEmpty()) {
            return new ComicLayout();
        }
        try {
            var l = new Gson().fromJson(json, ComicLayout.class);
            return l == null ? new ComicLayout() : l;
        } catch (Exception e) {
            return new ComicLayout();
        }
    }

    String to_json() {
        return new Gson().toJson(this);
    }
}
