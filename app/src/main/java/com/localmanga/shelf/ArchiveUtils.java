package com.localmanga.shelf;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ArchiveUtils {
    private ArchiveUtils() {}

    static List<File> scanImages(File directory) {
        List<File> files = new ArrayList<>();
        collect(directory, files);
        Collator collator = Collator.getInstance(Locale.CHINA);
        files.sort((a, b) -> NaturalPageSorter.compare(a.getAbsolutePath(), b.getAbsolutePath(), collator));
        return files;
    }

    private static void collect(File directory, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, output);
            else if (isImage(child.getName())) output.add(child);
        }
    }

    static int naturalCompare(String first, String second, Collator collator) {
        return NaturalPageSorter.compare(first, second, collator);
    }

    static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "jpg" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String sanitizeArchivePath(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        StringBuilder output = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("__MACOSX")) continue;
            String safe = part.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");
            if (output.length() > 0) output.append(File.separator);
            output.append(safe);
        }
        return output.toString();
    }

    static boolean isSafeBackupId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    static boolean isSafeRelativeBackupPath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.contains("\\")) return false;
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (int index = 0; index < part.length(); index++) {
                if (part.charAt(index) <= 0x1F) return false;
            }
        }
        return true;
    }
}
