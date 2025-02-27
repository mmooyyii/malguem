package com.github.mmooyyii.malguem;

import android.content.Context;
import android.content.SharedPreferences;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class DiskCache {

    private static DiskCache instance;

    private static DiskLruCache cache;

    private boolean enable;

    private DiskCache(Context context) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "file_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        SharedPreferences sharedPref = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        int maxSize = sharedPref.getInt("max_local_cache_size", 5 * 1024) * 1024 * 1024;
        if (maxSize <= 0) {
            maxSize = 1;
            enable = false;
        }
        cache = DiskLruCache.open(cacheDir, 1, 1, maxSize);
    }

    public static synchronized DiskCache getInstance(Context context) throws IOException {
        if (instance == null) {
            instance = new DiskCache(context.getApplicationContext());
        }
        return instance;
    }

    public DiskLruCache getCache() {
        return cache;
    }

    public static String generateKey(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(url.getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return String.valueOf(url.hashCode());
        }
    }

    public boolean isEnable() {
        return enable;
    }

    public void setMaxSize(int size) {
        if (size == 0) {
            enable = false;
            cache.setMaxSize(1);
        } else {
            enable = true;
            cache.setMaxSize(size);
        }
    }

}
