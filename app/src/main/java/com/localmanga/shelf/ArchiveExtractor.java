package com.localmanga.shelf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ArchiveExtractor {
    interface Progress { void onProgress(int imageCount); }
    interface ImageValidator { boolean isValid(File file); }

    private ArchiveExtractor() {}

    static int extract(InputStream input, File destination, Progress progress, ImageValidator validator) throws IOException {
        ArchiveLimits limits = new ArchiveLimits();
        Set<String> seen = new HashSet<>();
        int imageCount = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                limits.startEntry(entry.getSize());
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                boolean image = ArchiveUtils.isImage(entry.getName());
                File output = image ? outputFile(destination, entry.getName(), entries, seen) : null;
                copyEntry(zip, entry, output, destination, limits, buffer);
                if (!image) {
                    zip.closeEntry();
                    continue;
                }
                if (!validator.isValid(output)) {
                    if (output != null) output.delete();
                    throw new IOException("压缩包包含扩展名与内容不符的图片：" + entry.getName());
                }
                imageCount++;
                if (progress != null && imageCount % 4 == 0) progress.onProgress(imageCount);
                zip.closeEntry();
            }
        }
        return imageCount;
    }

    private static File outputFile(File destination, String entryName, int entryNumber, Set<String> seen) throws IOException {
        String extension = ArchiveUtils.extension(entryName);
        String relative = ArchiveUtils.sanitizeArchivePath(entryName);
        if (relative.isEmpty()) relative = String.format(Locale.US, "%06d.%s", entryNumber, extension);
        while (!seen.add(relative.toLowerCase(Locale.ROOT))) {
            relative = String.format(Locale.US, "%06d.%s", entryNumber, extension);
        }
        File output = new File(destination, relative);
        String rootPath = destination.getCanonicalPath() + File.separator;
        if (!output.getCanonicalPath().startsWith(rootPath)) throw new IOException("压缩包包含不安全路径");
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建漫画页面目录");
        return output;
    }

    @SuppressWarnings("UsableSpace")
    private static void copyEntry(ZipInputStream zip, ZipEntry entry, File output, File destination,
                                  ArchiveLimits limits, byte[] buffer) throws IOException {
        BufferedOutputStream target = output == null ? null : new BufferedOutputStream(new FileOutputStream(output));
        try {
            int read;
            while ((read = zip.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消");
                limits.recordBytes(read, entry.getCompressedSize(), destination.getUsableSpace(), output != null);
                if (target != null) target.write(buffer, 0, read);
            }
        } finally {
            if (target != null) target.close();
        }
    }
}
