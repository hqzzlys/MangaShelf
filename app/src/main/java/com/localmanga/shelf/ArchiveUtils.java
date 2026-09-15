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
        files.sort((a, b) -> naturalCompare(a.getAbsolutePath(), b.getAbsolutePath(), collator));
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
        int firstIndex = 0;
        int secondIndex = 0;
        while (firstIndex < first.length() && secondIndex < second.length()) {
            char firstChar = first.charAt(firstIndex);
            char secondChar = second.charAt(secondIndex);
            if (Character.isDigit(firstChar) && Character.isDigit(secondChar)) {
                int firstEnd = digitRunEnd(first, firstIndex);
                int secondEnd = digitRunEnd(second, secondIndex);
                int firstSignificant = skipLeadingZeros(first, firstIndex, firstEnd);
                int secondSignificant = skipLeadingZeros(second, secondIndex, secondEnd);
                int firstLength = firstEnd - firstSignificant;
                int secondLength = secondEnd - secondSignificant;
                if (firstLength != secondLength) return Integer.compare(firstLength, secondLength);
                for (int offset = 0; offset < firstLength; offset++) {
                    int comparison = Character.compare(first.charAt(firstSignificant + offset), second.charAt(secondSignificant + offset));
                    if (comparison != 0) return comparison;
                }
                int firstRunLength = firstEnd - firstIndex;
                int secondRunLength = secondEnd - secondIndex;
                if (firstRunLength != secondRunLength) return Integer.compare(firstRunLength, secondRunLength);
                firstIndex = firstEnd;
                secondIndex = secondEnd;
            } else {
                int firstEnd = nonDigitRunEnd(first, firstIndex);
                int secondEnd = nonDigitRunEnd(second, secondIndex);
                int comparison = collator.compare(first.substring(firstIndex, firstEnd), second.substring(secondIndex, secondEnd));
                if (comparison != 0) return comparison;
                firstIndex = firstEnd;
                secondIndex = secondEnd;
            }
        }
        return Integer.compare(first.length(), second.length());
    }

    private static int digitRunEnd(String value, int start) {
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        return end;
    }

    private static int nonDigitRunEnd(String value, int start) {
        int end = start;
        while (end < value.length() && !Character.isDigit(value.charAt(end))) end++;
        return end;
    }

    private static int skipLeadingZeros(String value, int start, int end) {
        int index = start;
        while (index < end - 1 && value.charAt(index) == '0') index++;
        return index;
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
