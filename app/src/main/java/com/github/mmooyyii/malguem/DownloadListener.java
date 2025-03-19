package com.github.mmooyyii.malguem;


public interface DownloadListener  {
    void onProgress(long current_bytes, long total_bytes);
}
