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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Slice slice = (Slice) o;
        if (offset != null ? !offset.equals(slice.offset) : slice.offset != null) return false;
        return size != null ? size.equals(slice.size) : slice.size == null;
    }

    // 重写 hashCode 方法，保证相等的对象有相同的哈希码
    @Override
    public int hashCode() {
        int result = offset != null ? offset.hashCode() : 0;
        result = 31 * result + (size != null ? size.hashCode() : 0);
        return result;
    }
}
