package com.github.mmooyyii.malguem;


import java.io.IOException;

public interface DownloadListener  {
    void onProgress(long current_bytes, long total_bytes);
}
