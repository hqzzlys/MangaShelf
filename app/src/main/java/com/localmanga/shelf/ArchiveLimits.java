package com.localmanga.shelf;

import java.io.IOException;

final class ArchiveLimits {
    static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;
    static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    static final long MIN_FREE_BYTES = 32L * 1024L * 1024L;
    static final long MAX_COMPRESSION_RATIO = 200L;
    static final int MAX_ENTRIES = 10000;

    private int entries;
    private long totalBytes;
    private long entryBytes;

    void startEntry(long declaredSize) throws IOException {
        if (++entries > MAX_ENTRIES) throw new IOException("压缩包文件过多（上限 10000）");
        if (declaredSize > MAX_ENTRY_BYTES) throw new IOException("压缩包中的单个文件超过 512 MB 安全上限");
        entryBytes = 0L;
    }

    void recordBytes(long bytes, long compressedSize, long usableSpace, boolean writesToDisk) throws IOException {
        entryBytes += bytes;
        totalBytes += bytes;
        if (entryBytes > MAX_ENTRY_BYTES) throw new IOException("压缩包中的单个文件超过 512 MB 安全上限");
        if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("解压内容超过 2 GB 安全上限");
        if (compressedSize > 0 && entryBytes / compressedSize > MAX_COMPRESSION_RATIO) {
            throw new IOException("压缩包包含异常压缩比的文件");
        }
        if (writesToDisk && usableSpace > 0 && usableSpace < MIN_FREE_BYTES) {
            throw new IOException("设备剩余空间不足，已停止导入");
        }
    }

    int entries() { return entries; }
    long totalBytes() { return totalBytes; }
}
