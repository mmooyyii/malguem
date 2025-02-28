package com.github.mmooyyii.malguem;

import android.os.Build;
import android.util.Pair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import io.documentnode.epub4j.domain.Resource;

public class LazyEpub {
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIR_SIGNATURE = 0x02014b50;

    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;


    String uri;
    ResourceInterface file;
    // resource map
    List<String> contents; // page -> html

    HashMap<String, byte[]> resource; // name -> html

    Map<String, CentralDirEntry> zip_dir; // page -> (offset,size)

    Integer centralDirOffset;
    Integer centralDirSize;

    public LazyEpub(String epub_uri, ResourceInterface client) throws Exception {
        uri = epub_uri;
        file = client;
        contents = new ArrayList<>();
        resource = new HashMap<>();
        zip_dir = new HashMap<>();
        init_epub_dir();
    }

    private void init_epub_dir() throws Exception {
        // 请求ZIP文件末尾的22字节（End of Central Directory最小长度）
        var slice = new Slice();
        slice.offset = -22;
        initCentralDirLocate(file.open(uri, slice));
        slice.offset = centralDirOffset;
        slice.size = centralDirSize;
        initCentralDirectory(file.open(uri, slice));
    }

    private void initCentralDirLocate(byte[] endBytes) {
        // 检查最小长度（End of Central Directory的最小长度为22字节）
        if (endBytes == null || endBytes.length < 22) {
            throw new IllegalArgumentException("Invalid EOCD data: length < 22 bytes");
        }
        // 从后往前搜索EOCD签名（处理ZIP注释可能存在的干扰）
        for (int i = endBytes.length - 22; i >= 0; i--) {
            // 将4字节转换为int（小端序转换）
            int signature = ByteBuffer.wrap(endBytes, i, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (signature == EOCD_SIGNATURE) {
                centralDirSize = ByteBuffer.wrap(endBytes, i + 12, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                centralDirOffset = ByteBuffer.wrap(endBytes, i + 16, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                return;
            }
        }
        throw new IllegalArgumentException("EOCD signature not found");
    }

    public void initCentralDirectory(byte[] centralDirData) {
        zip_dir = new HashMap<>();
        int position = 0;
        while (position < centralDirData.length) {
            // 校验中央目录条目签名
            int signature = ByteBuffer.wrap(centralDirData, position, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (signature != CENTRAL_DIR_SIGNATURE) {
                break;
            }
            int compressionMethod = ByteBuffer.wrap(centralDirData, position + 10, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            long compressedSize = ByteBuffer.wrap(centralDirData, position + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            long uncompressedSize = ByteBuffer.wrap(centralDirData, position + 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            // 解析文件名长度和扩展字段长度
            int fileNameLength = ByteBuffer.wrap(centralDirData, position + 28, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            int extraFieldLength = ByteBuffer.wrap(centralDirData, position + 30, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            int fileCommentLength = ByteBuffer.wrap(centralDirData, position + 32, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
            // 解析本地文件头偏移量（关键字段）
            long localHeaderOffset = ByteBuffer.wrap(centralDirData, position + 42, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            // 提取文件名（UTF-8编码处理）
            String fileName = new String(centralDirData, position + 46, fileNameLength);
            // 构建条目对象
            CentralDirEntry entry = new CentralDirEntry(
                    fileName, compressedSize, uncompressedSize,
                    localHeaderOffset, compressionMethod, extraFieldLength
            );
            zip_dir.put(fileName, entry);
            if (fileName.startsWith("OEBPS/Text")) {
                contents.add(fileName);
            }
            // 计算下一个条目的起始位置
            position += 46 + fileNameLength + extraFieldLength + fileCommentLength;
        }
    }

    public byte[] load_file(String filename) throws Exception {
        if (resource.containsKey(filename)) {
            return resource.get(filename);
        }
        if (!zip_dir.containsKey(filename)) {
            throw new IllegalArgumentException("no such file");
        }
        var entry = zip_dir.get(filename);
        var slice = new Slice();
        assert entry != null;
        slice.offset = Math.toIntExact(entry.localHeaderOffset);
        slice.size = 1024;
        long dataOffset = entry.localHeaderOffset + parseDataOffset(file.open(uri, slice));

        slice.offset = Math.toIntExact(dataOffset);
        slice.size = Math.toIntExact(entry.compressedSize);
        var bytes = file.open(uri, slice);

        if (entry.compressionMethod == 0) {
            resource.put(filename, bytes);
            return bytes;
        } else if (entry.compressionMethod == 8) {
            Inflater decompresser = new Inflater(true);
            decompresser.setInput(bytes);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try {
                while (!decompresser.finished()) {
                    int count = decompresser.inflate(buffer); // 解压数据块
                    outputStream.write(buffer, 0, count);
                }
            } finally {
                decompresser.end(); // 必须手动释放资源
            }
            var output = outputStream.toByteArray();
            resource.put(filename, output);
            return output;
        } else {
            throw new IllegalArgumentException("无法解压epub: " + entry.compressionMethod);
        }

    }

    public static long parseDataOffset(byte[] localHeaderData) {
        // 校验最小长度（至少需要38字节）
        if (localHeaderData == null || localHeaderData.length < 38) {
            throw new IllegalArgumentException("本地文件头数据不完整");
        }
        // 校验签名
        int signature = ByteBuffer.wrap(localHeaderData, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        if (signature != LOCAL_HEADER_SIGNATURE) {
            throw new IllegalArgumentException("无效的本地文件头签名");
        }

        // 解析文件名和扩展字段长度
        int fileNameLength = ByteBuffer.wrap(localHeaderData, 26, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;

        int extraFieldLength = ByteBuffer.wrap(localHeaderData, 28, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;

        // 计算数据偏移量：本地头长度(30) + 文件名长度 + 扩展字段长度
        return 30L + fileNameLength + extraFieldLength;
    }


    public static class CentralDirEntry {
        public final String fileName;
        public final long compressedSize;
        public final long uncompressedSize;
        public final long localHeaderOffset;
        public final int compressionMethod;
        public final int extraFieldLength;

        public CentralDirEntry(String fileName, long compressedSize,
                               long uncompressedSize, long localHeaderOffset,
                               int compressionMethod, int extraFieldLength) {
            this.fileName = fileName;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.compressionMethod = compressionMethod;
            this.extraFieldLength = extraFieldLength;
        }
    }
}
