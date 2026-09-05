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
        private static final int DATABASE_VERSION = 3;

        // 创建表的 SQL 语句
        private static final String RESOURCE_TABLE = "CREATE TABLE resource (id INTEGER PRIMARY KEY, name TEXT NOT NULL, resource_type INTEGER NOT NULL, json_info TEXT NOT NULL);";

        private static final String EPUB_TABLE = "CREATE TABLE epub (resource_id INTEGER NOT NULL,path TEXT NOT NULL, total_page INTEGER NOT NULL default 0, current_page INTEGER NOT NULL default 0, page_offset INTEGER NOT NULL default 0, view_type INTEGER NOT NULL default 0, PRIMARY KEY (resource_id, path));";

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

        public void switch_view_type(int resource_id, String path) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            cur.execSQL("update epub set view_type = 1 - view_type where resource_id=? and path=?",
                    new String[]{String.valueOf(resource_id), path});
        }

        public ReadHistory get_epub_info(int resource_id, String path) {
            var output = new ReadHistory();
            var db = getReadableDatabase();
            var cursor = db.query("epub", new String[]{"current_page", "page_offset", "view_type"}, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path}, null, null, null);
            if (cursor.moveToNext()) {
                output.current_page = cursor.getInt(cursor.getColumnIndexOrThrow("current_page"));
                output.page_offset = cursor.getInt(cursor.getColumnIndexOrThrow("page_offset"));
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



