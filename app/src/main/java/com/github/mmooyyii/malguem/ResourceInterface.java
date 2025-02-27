package com.github.mmooyyii.malguem;


import java.util.List;


interface ResourceInterface {
    List<ListItem> ls(int id, List<String> path) throws Exception;

    byte[] open(String uri) throws Exception;
}

