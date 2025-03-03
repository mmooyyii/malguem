package com.github.mmooyyii.malguem;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class Database {


    private static Database instance;
    private DatabaseHelper database;

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

    public class DatabaseHelper extends SQLiteOpenHelper {

        // 数据库名称和版本
        private static final String DATABASE_NAME = "malguem.db";
        private static final int DATABASE_VERSION = 1;

        // 创建表的 SQL 语句
        private static final String RESOURCE_TABLE = "CREATE TABLE resource (id INTEGER PRIMARY KEY, name TEXT NOT NULL, resource_type INTEGER NOT NULL, json_info TEXT NOT NULL);";

        private static final String EPUB_TABLE = "CREATE TABLE epub (resource_id INTEGER NOT NULL,path TEXT NOT NULL, current_page INTEGER NOT NULL default 0, page_offset INTEGER NOT NULL default 0, view_type INTEGER NOT NULL default 0, PRIMARY KEY (resource_id, path));";

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // 创建数据库表
            db.execSQL(RESOURCE_TABLE);
            db.execSQL(EPUB_TABLE);
            Log.d("db", "create database");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 升级数据库时的操作，例如删除旧表并创建新表
            db.execSQL("drop table IF EXISTS epub");
            db.execSQL("drop table IF EXISTS resource");
            onCreate(db);
        }

        public void add_webdav(String url, String username, String passwd) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("name", url);
            values.put("resource_type", 1);

            var map = new HashMap<String, String>();
            map.put("url", url);
            map.put("username", username);
            map.put("passwd", passwd);

            Gson gson = new Gson();
            values.put("json_info", gson.toJson(map));
            cur.insert("resource", null, values);
        }

        public WebdavResource get_webdav(int resource_id) {
            var db = getReadableDatabase();
            var cursor = db.query("resource", new String[]{"json_info"}, "id=?", new String[]{String.valueOf(resource_id)}, null, null, null);
            Gson gson = new Gson();

            if (cursor.moveToNext()) {
                var json = cursor.getString(cursor.getColumnIndexOrThrow("json_info"));
                // 使用 TypeToken 来指定转换的目标类型
                var type = new TypeToken<HashMap<String, String>>() {
                }.getType();
                HashMap<String, String> map = gson.fromJson(json, type);
                return new WebdavResource(map.get("url"), map.get("username"), map.get("passwd"));
            }
            return null;
        }

        public void delete_webdav(int resource_id) {
            var db = getWritableDatabase();
            db.delete("resource", "id=?", new String[]{String.valueOf(resource_id)});
        }

        public void save_history(int resource_id, String path, int current_page, int page_offset) {
            init_epub(resource_id, path);
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("current_page", current_page);
            values.put("page_offset", page_offset);
            cur.update("epub", values, "resource_id=? and path=?", new String[]{String.valueOf(resource_id), path});
        }

        public void init_epub(int resource_id, String path) {
            var cur = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("resource_id", resource_id);
            values.put("path", path);
            values.put("current_page", 0);
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
            return output;
        }

        public HashMap<String, ListItem.ViewType> get_view_types(int resource_id, List<String> paths) {
            var output = new HashMap<String, ListItem.ViewType>();
            if (paths.isEmpty()) {
                return output;
            }
            var db = getReadableDatabase();
            paths.add(String.valueOf(resource_id));
            var cursor = db.query("epub",
                    new String[]{"path", "view_type"}, "path in " + make_in_list(paths.size() - 1) + " and resource_id=?",
                    paths.toArray(new String[0]), null, null, null);
            while (cursor.moveToNext()) {
                var path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                var view_type = cursor.getInt(cursor.getColumnIndexOrThrow("view_type"));
                if (view_type == 0) {
                    output.put(path, ListItem.ViewType.Comic);
                } else {
                    output.put(path, ListItem.ViewType.Novel);
                }
            }
            return output;
        }

        public List<ListItem> resource_list() {
            var db = getReadableDatabase();
            var cursor = db.query("resource", new String[]{"id", "name"}, null, null, null, null, null);
            var list = new ArrayList<ListItem>();
            while (cursor.moveToNext()) {
                var id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
                var name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                list.add(new ListItem(id, name, ListItem.FileType.Resource));
            }
            cursor.close();
            return list;
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



