package com.github.mmooyyii.malguem;

// 按扩展名分派到具体的书格式: epub 走 LazyEpub, cbz 走 LazyCbz.
// 两者都是 LazyZip 的流式随机读, 只是"包里的东西怎么解释"不同, 所以索引/封面/阅读全都能共用一套上层逻辑
public final class Books {

    private Books() {
    }

    static boolean isCbz(String uri) {
        return uri != null && uri.toLowerCase().endsWith(".cbz");
    }

    // 文件列表里当"书"收的扩展名
    static boolean isBook(String name) {
        var lower = name.toLowerCase();
        return lower.endsWith(".epub") || lower.endsWith(".cbz");
    }

    // 去掉书名后缀, 列表里显示用
    static String stripExt(String name) {
        if (isBook(name)) {
            return name.substring(0, name.lastIndexOf('.'));
        }
        return name;
    }

    // 优先用 SQLite 里的索引 0 往返开书.
    // OPDS 漫画是例外: 服务端给了页流就走页流, 按页取图比把整本拖下来随机读快几个数量级
    // (Komga 的下载端点压根不认 Range, 详见 OpdsBook 的注释)
    static Book open(String namespace, String uri, ResourceInterface client, Database.DatabaseHelper db) throws Exception {
        if (client instanceof OpdsResource) {
            try {
                var stream = ((OpdsResource) client).streamOf(uri);
                if (stream != null) {
                    return new OpdsBook(stream, (OpdsResource) client);
                }
            } catch (Exception ignore) {
                // 目录查不动就按老路开整本, 真是网络断了下面那步会抛出真正的原因
            }
        }
        return openText(namespace, uri, client, db);
    }

    // 小说模式专用: 必须拿到真正的 epub 文字流, 不能用 OPDS 页流顶替 —— 那边给的是图片,
    // 字号/夜间/重排/章内进度全都无从谈起. 服务端也只对图片型漫画给页流, 两边本来就不重合
    static Book openText(String namespace, String uri, ResourceInterface client, Database.DatabaseHelper db) throws Exception {
        return isCbz(uri)
                ? LazyCbz.open(namespace, uri, client, db)
                : LazyEpub.open(namespace, uri, client, db);
    }

    // 走网络重新解析并生成索引 json (IndexCrawler 批量建索引用)
    static String build_index(String uri, ResourceInterface client) throws Exception {
        return isCbz(uri)
                ? new LazyCbz(uri, client).index_json()
                : new LazyEpub(uri, client).index_json();
    }

    static boolean index_up_to_date(String uri, String json) {
        return isCbz(uri) ? LazyCbz.index_up_to_date(json) : LazyEpub.index_up_to_date(json);
    }
}
