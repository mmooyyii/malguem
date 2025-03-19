package com.github.mmooyyii.malguem;

import java.util.Objects;

public class Slice {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Slice slice = (Slice) o;
        if (!Objects.equals(offset, slice.offset)) return false;
        return Objects.equals(size, slice.size);
    }

    // 重写 hashCode 方法，保证相等的对象有相同的哈希码
    @Override
    public int hashCode() {
        int result = offset != null ? offset.hashCode() : 0;
        result = 31 * result + (size != null ? size.hashCode() : 0);
        return result;
    }
}
