package com.github.mmooyyii.malguem;


import java.util.List;


interface ResourceInterface {
    List<File> ls(int id, List<String> path) throws Exception;

    byte[] open(String uri, Slice slice) throws Exception;


    byte[][] open(String uri, Slice[] slice) throws Exception;
}

