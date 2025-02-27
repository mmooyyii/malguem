package com.github.mmooyyii.malguem;

class Slice {
    Integer offset;
    Integer size;


    public String to_string() {
        if (offset < 0 && size == null) {
            return String.valueOf(offset);
        }
        if (size == null) {
            return offset + "-";
        }
        return offset + "-" + (offset + size - 1);
    }
}