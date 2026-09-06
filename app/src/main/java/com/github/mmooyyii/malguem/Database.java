package com.github.mmooyyii.malguem;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class Database {


    private static Database instance;
    private final DatabaseHelper database;

    private Database(Context context) {
        database = new DatabaseHelper(context);
    }

    public static synchronized Database getInstance(Context context) {
        if (instance == null) {
            instance = new Database(context.getApplicationContext());
        }
        return instance;
    }

    public DatabaseHelper getDatabase() {
        return database;
    }

    public static class DatabaseHelper extends SQLiteOpenHelper {

        // 数据库名称和版本
        private static final String DATABASE_NAME = "malguem.db";
        private static final int DATABASE_VERSION = 7;

        // 创建表的 SQL 语句
        private static final String RESOURCE_TABLE = "CREATE TABLE resource (id INTEGER PRIMARY KEY, name TEXT NOT NULL, resource_type INTEGER NOT NULL, json_info TEXT NOT NULL);";

        // rtl: 漫画从右到左阅读(日漫); single_page: 漫画单页模式; 都按书保存.
        // last_read: 最近一次阅读的毫秒时间戳, 首页"最近阅读"按它排序.
        // page_offset: 小说章内滚动位置, 存 0-10000 的万分比而不是像素 —— 字号/夜间模式会改变排版高度, 按比例才能对得上
        private static final String EPUB_TABLE = "CREATE TABLE epub (resource_id INTEGER NOT NULL,path TEXT NOT NULL, total_page INTEGER NOT NULL default 0, current_page INTEGER NOT NULL default 0, page_offset INTEGER NOT NULL default 0, view_type INTEGER NOT NULL default 0, rtl INTEGER NOT NULL default 0, last_read INTEGER NOT NULL default 0, single_page INTEGER NOT NULL default 0, layout_json TEXT NOT NULL default '', PRIMARY KEY (resource_id, path));";

        // epub 索引缓存: 中央目录 + opf 解析结果, 免掉开书/加载封面时的元数据网络往返.
        // namespace 是数据源配置的 json(区分不同服务器/账号), path 是包内路径, 一起做主键; 不做内容失效
        private static final String EPUB_INDEX_TABLE = "CREATE TABLE epub_index (namespace TEXT NOT NULL, path TEXT NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL default 0, PRIMARY KEY (namespace, path));";

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // 创建数据库表
            db.execSQL(RESOURCE_TABLE);
            db.execSQL(EPUB_TABLE);
            db.execSQL(EPUB_INDEX_TABLE);
            Log.d("db", "create database");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // v2 之前无兼容路径, 重建 (会丢阅读进度, 仅影响远古版本)
                db.execSQL("drop table IF EXISTS epub");
                db.execSQL("drop table IF EXISTS resource");
                db.execSQL("drop table IF EXISTS epub_index");
                onCreate(db);
                return;
            }
            if (oldVersion < 3) {
                // v3 新增 epub 索引表, 保留既有数据
                db.execSQL(EPUB_INDEX_TABLE);
            }
            if (oldVersion < 4) {
                // v4 新增 漫画阅读方向 与 最近阅读时间, 保留既有数据
                db.execSQL("ALTER TABLE epub ADD COLUMN rtl INTEGER NOT NULL default 0");
                db.execSQL("ALTER TABLE epub ADD COLUMN last_read INTEGER NOT NULL default 0");
            }
            if (oldVersion < 5) {
                // v5 新增 漫画单页模式
                db.execSQL("ALTER TABLE epub ADD COLUMN single_page INTEGER NOT NULL default 0");
            }
            if (oldVersion < 6) {
                // v6 page_offset 语义从像素改为万分比, 旧像素值无法换算, 一次性清零 (只丢章内位置, 章节进度保留)
                db.execSQL("update epub set page_offset = 0");
            }
            if (oldVersion < 7) {
                // v7 新增漫画布局配置 json (以后布局类配置都进这一列, 不再逐项加列)
                db.execSQL("ALTER TABLE epub ADD COLUMN layout_json TEXT NOT NULL default ''");
            }
        }

        // type: 1=webdav 2=smb 3=local; json 由各 ResourceInterface.to_json() 生成(自带 type 字段)
        public void add_resource(String name, int type, String json) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("resource_type", type);
            values.put("json_info", json);
            cur.insert("resource", null, values);
        }

        // 编辑数据源: 保住 id 不变, 这样 epub 表里按 resource_id 存的阅读进度不会丢
        public void update_resource(int resource_id, String name, int type, String json) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("resource_type", type);
            values.put("json_info", json);
            cur.update("resource", values, "id=?", new String[]{String.valueOf(resource_id)});
        }

        public ResourceInterface get_resource(int resource_id) {
            var db = getReadableDatabase();
            var cursor = db.query("resource", new String[]{"json_info"}, "id=?", new String[]{String.valueOf(resource_id)}, null, null, null);
            ResourceInterface resource = null;
            if (cursor.moveToNext()) {
                var json = cursor.getString(cursor.getColumnIndexOrThrow("json_info"));
                resource = ResourceInterface.from_json(json);
            }
            cursor.close();
            return resource;
        }

        public void delete_resource(int resource_id) {
            var db = getWritableDatabase();
            db.delete("resource", "id=?", new String[]{String.valueOf(resource_id)});
        }

        public void save_history(int resource_id, String path, int total_page, int current_page, int page_offset) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("current_page", current_page);
            values.put("page_offset", page_offset);
            values.put("total_page", total_page);
            values.put("last_read", System.currentTimeMillis());
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        // 首页"最近阅读": 按 last_read 倒序取最近读过的书, 联表带出数据源配置
        public List<ListItem> recent_books(int limit) {
            var db = getReadableDatabase();
            var cursor = db.rawQuery(
                    "select e.resource_id, e.path, e.total_page, e.current_page, e.view_type, r.json_info"
                            + " from epub e join resource r on r.id = e.resource_id"
                            + " where e.last_read > 0 order by e.last_read desc limit ?",
                    new String[]{String.valueOf(limit)});
            var list = new ArrayList<ListItem>();
            while (cursor.moveToNext()) {
                var path = cursor.getString(1);
                var name = path.substring(path.lastIndexOf('/') + 1);
                var item = new ListItem(cursor.getInt(0), name, ListItem.FileType.RecentEpub);
                item.uri = path;
                item.total_page = cursor.getInt(2);
                item.read_to_page = cursor.getInt(3);
                item.view_type = cursor.getInt(4) == 0 ? ListItem.ViewType.Comic : ListItem.ViewType.Novel;
                try {
                    // 统一走 from_json→to_json 的往返结果做 namespace, 与封面/索引处保持同一个 key
                    item.ns = ResourceInterface.from_json(cursor.getString(5)).to_json();
                } catch (Exception e) {
                    continue;
                }
                list.add(item);
            }
            cursor.close();
            return list;
        }

        // 从首页"最近阅读"移除一条 (进度本身保留)
        public void clear_last_read(int resource_id, String path) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("last_read", 0);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void set_single_page(int resource_id, String path, boolean single) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("single_page", single ? 1 : 0);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void set_layout(int resource_id, String path, String layout_json) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("layout_json", layout_json);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void set_rtl(int resource_id, String path, boolean rtl) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("rtl", rtl ? 1 : 0);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void init_epub(int resource_id, String path) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("resource_id", resource_id);
            values.put("path", path);
            values.put("current_page", 0);
            values.put("total_page", 0);
            values.put("page_offset", 0);
            values.put("view_type", 0);
            cur.insertWithOnConflict("epub", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        // 0=漫画 1=小说; 阅读中切换模式时用, 明确目标值比 toggle 稳
        public void set_view_type(int resource_id, String path, int view_type) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("view_type", view_type);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void switch_view_type(int resource_id, String path) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            cur.execSQL("update epub set view_type = 1 - view_type where resource_id=? and path=?",
                    new String[]{String.valueOf(resource_id), path});
        }

        public ReadHistory get_epub_info(int resource_id, String path) {
            var output = new ReadHistory();
            var db = getReadableDatabase();
            var cursor = db.query("epub", new String[]{"current_page", "page_offset", "view_type", "rtl", "single_page", "layout_json"}, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path}, null, null, null);
            if (cursor.moveToNext()) {
                output.current_page = cursor.getInt(cursor.getColumnIndexOrThrow("current_page"));
                output.page_offset = cursor.getInt(cursor.getColumnIndexOrThrow("page_offset"));
                output.rtl = cursor.getInt(cursor.getColumnIndexOrThrow("rtl")) == 1;
                output.single_page = cursor.getInt(cursor.getColumnIndexOrThrow("single_page")) == 1;
                output.layout_json = cursor.getString(cursor.getColumnIndexOrThrow("layout_json"));
                var type = cursor.getInt(cursor.getColumnIndexOrThrow("view_type"));
                if (type == 0) {
                    output.view_type = ListItem.ViewType.Comic;
                } else {
                    output.view_type = ListItem.ViewType.Novel;
                }
            }
            cursor.close();
            return output;
        }

        public HashMap<String, ListItem> get_view_types(int resource_id, List<String> paths) {
            var output = new HashMap<String, ListItem>();
            if (paths.isEmpty()) {
                return output;
            }
            var db = getReadableDatabase();
            paths.add(String.valueOf(resource_id));
            var cursor = db.query("epub",
                    new String[]{"path", "view_type", "total_page", "current_page"}, "path in " + make_in_list(paths.size() - 1) + " and resource_id=?",
                    paths.toArray(new String[0]), null, null, null);
            while (cursor.moveToNext()) {
                var path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                var view_type = cursor.getInt(cursor.getColumnIndexOrThrow("view_type"));
                var current_page = cursor.getInt(cursor.getColumnIndexOrThrow("current_page"));
                var total_page = cursor.getInt(cursor.getColumnIndexOrThrow("total_page"));
                var item = new ListItem();
                item.name = path;
                item.read_to_page = current_page;
                item.total_page = total_page;
                if (view_type == 0) {
                    item.view_type = ListItem.ViewType.Comic;
                } else {
                    item.view_type = ListItem.ViewType.Novel;
                }
                output.put(path, item);
            }
            cursor.close();
            return output;
        }

        public List<ListItem> resource_list() {
            var db = getReadableDatabase();
            var cursor = db.query("resource", new String[]{"id", "name", "resource_type"}, null, null, null, null, null);
            var list = new ArrayList<ListItem>();
            while (cursor.moveToNext()) {
                var id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
                var name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                var item = new ListItem(id, name, ListItem.FileType.Resource);
                item.resource_type = cursor.getInt(cursor.getColumnIndexOrThrow("resource_type"));
                list.add(item);
            }
            cursor.close();
            return list;
        }

        // ---- epub 索引缓存 ----

        public String get_epub_index(String namespace, String path) {
            var db = getReadableDatabase();
            var cursor = db.query("epub_index", new String[]{"json"}, "namespace=? and path=?",
                    new String[]{namespace, path}, null, null, null);
            String json = null;
            if (cursor.moveToNext()) {
                json = cursor.getString(0);
            }
            cursor.close();
            return json;
        }

        public void put_epub_index(String namespace, String path, String json) {
            var db = getWritableDatabase();
            var values = new ContentValues();
            values.put("namespace", namespace);
            values.put("path", path);
            values.put("json", json);
            values.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("epub_index", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        public void delete_epub_index(String namespace, String path) {
            getWritableDatabase().delete("epub_index", "namespace=? and path=?", new String[]{namespace, path});
        }

        // 全部 (namespace, path) 行, 供孤儿回收比对
        public List<String[]> epub_index_rows() {
            var db = getReadableDatabase();
            var cursor = db.query("epub_index", new String[]{"namespace", "path"}, null, null, null, null, null);
            var out = new ArrayList<String[]>();
            while (cursor.moveToNext()) {
                out.add(new String[]{cursor.getString(0), cursor.getString(1)});
            }
            cursor.close();
            return out;
        }

        // {条数, json 总字节数}, 供缓存对话框展示
        public long[] epub_index_stats() {
            var db = getReadableDatabase();
            var cursor = db.rawQuery("select count(*), coalesce(sum(length(json)), 0) from epub_index", null);
            var out = new long[]{0, 0};
            if (cursor.moveToNext()) {
                out[0] = cursor.getLong(0);
                out[1] = cursor.getLong(1);
            }
            cursor.close();
            return out;
        }

        public void clear_epub_index() {
            getWritableDatabase().delete("epub_index", null, null);
        }

        private String make_in_list(int n) {
            var sb = new StringBuilder("(");
            for (var i = 0; i < n - 1; ++i) {
                sb.append("?,");
            }
            sb.append("?");
            sb.append(")");
            return sb.toString();
        }
    }
}



