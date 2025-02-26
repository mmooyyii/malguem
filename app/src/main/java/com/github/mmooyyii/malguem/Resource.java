package com.github.mmooyyii.malguem;


import java.util.List;

import okhttp3.Response;


interface ResourceInterface {
    List<File> ls(int id, List<String> path) throws Exception;

    byte[] open(String uri) throws Exception;
}

