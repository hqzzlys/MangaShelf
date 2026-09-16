package com.localmanga.shelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class LibraryBackup {
    private static final String FORMAT = "mangashelf-library";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BACKUP_FILES = 100000;
    private static final long MAX_BACKUP_BYTES = 20L * 1024L * 1024L * 1024L;

    private LibraryBackup() {}

    static void exportLibrary(Context context, SharedPreferences preferences, List<Comic> comics, Uri destination,
                              ComicRepository.BackupProgress progress) throws IOException {
        JSONObject manifest = createManifest(preferences, comics);
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        int totalPages = 0;
        for (Comic comic : comics) totalPages += comic.pageCount();

        OutputStream raw = context.getContentResolver().openOutputStream(destination, "w");
        if (raw == null) throw new IOException("无法创建备份文件");
        Set<String> entries = new HashSet<>();
        int exported = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            zip.setLevel(Deflater.BEST_SPEED);
            writeBytes(zip, "manifest.json", manifestBytes, entries);
            byte[] buffer = new byte[64 * 1024];
            for (Comic comic : comics) {
                for (File page : comic.pages) {
                    checkCancelled();
                    String relative = relativePath(comic.directory, page);
                    if (!ArchiveUtils.isSafeRelativeBackupPath(relative)) {
                        throw new IOException("漫画包含无法备份的文件路径：" + page.getName());
                    }
                    String entryName = "comics/" + comic.id + "/" + relative;
                    if (!entries.add(entryName.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IOException("漫画包含重复文件路径：" + page.getName());
                    }
                    ZipEntry entry = new ZipEntry(entryName);
                    zip.putNextEntry(entry);
                    try (InputStream input = new BufferedInputStream(new FileInputStream(page))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkCancelled();
                            zip.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                    exported++;
                    if (progress != null) progress.onProgress("正在备份漫画页面…", exported, totalPages);
                }
            }
        }
    }

    static ComicRepository.RestoreResult restoreLibrary(Context context, SharedPreferences preferences, File root,
                                                         Uri source, ComicRepository.BackupProgress progress) throws IOException {
        File temporary = new File(root, ".restore_" + UUID.randomUUID());
        File temporaryComics = new File(temporary, "comics");
        if (!temporaryComics.mkdirs()) throw new IOException("无法创建恢复临时目录");
        List<File> movedDirectories = new ArrayList<>();
        try {
            byte[] manifestBytes = extractBackup(context, source, temporaryComics, progress);
            JSONObject manifest = parseManifest(manifestBytes);
            List<RestoreRecord> records = validateManifest(manifest, temporaryComics);
            Map<String, String> restoredIds = allocateIds(root, records);

            for (RestoreRecord record : records) {
                checkCancelled();
                File target = new File(root, restoredIds.get(record.sourceId));
                if (!record.directory.renameTo(target)) throw new IOException("无法恢复漫画《" + record.title + "》");
                record.restoredDirectory = target;
                movedDirectories.add(target);
            }

            SharedPreferences.Editor editor = preferences.edit();
            Set<String> collections = new HashSet<>(preferences.getStringSet("collections", new HashSet<>()));
            JSONArray savedCollections = manifest.optJSONArray("collections");
            if (savedCollections != null) {
                for (int index = 0; index < savedCollections.length(); index++) {
                    String name = cleanCollectionName(savedCollections.optString(index, ""));
                    if (!name.isEmpty()) collections.add(name);
                }
            }
            editor.putStringSet("collections", collections);

            int pages = 0;
            for (RestoreRecord record : records) {
                checkCancelled();
                String targetId = restoredIds.get(record.sourceId);
                List<File> restoredPages = ArchiveUtils.scanImages(record.restoredDirectory);
                pages += restoredPages.size();
                editor.putString(targetId + ".title", record.title)
                        .putInt(targetId + ".progress", Math.max(-1, Math.min(record.progress, restoredPages.size() - 1)))
                        .putLong(targetId + ".imported", record.importedAt)
                        .putLong(targetId + ".lastRead", record.lastRead)
                        .putBoolean(targetId + ".favorite", record.favorite)
                        .putString(targetId + ".collection", record.collection);
                Set<String> mergedSources = new HashSet<>();
                for (String sourceId : record.mergedSources) {
                    String restoredId = restoredIds.get(sourceId);
                    if (restoredId != null) mergedSources.add(restoredId);
                }
                editor.putStringSet(targetId + ".mergedSources", mergedSources);
            }
            if (!editor.commit()) throw new IOException("无法保存恢复后的书架信息");
            deleteRecursive(temporary);
            return new ComicRepository.RestoreResult(records.size(), pages);
        } catch (Exception error) {
            for (File directory : movedDirectories) deleteRecursive(directory);
            deleteRecursive(temporary);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("备份恢复失败：" + error.getMessage(), error);
        }
    }

    private static JSONObject createManifest(SharedPreferences preferences, List<Comic> comics) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", FORMAT_VERSION);
            manifest.put("createdAt", System.currentTimeMillis());
            JSONArray collections = new JSONArray();
            for (String collection : preferences.getStringSet("collections", new HashSet<>())) collections.put(collection);
            manifest.put("collections", collections);
            JSONArray items = new JSONArray();
            for (Comic comic : comics) {
                JSONObject item = new JSONObject();
                item.put("id", comic.id);
                item.put("title", comic.title);
                item.put("progress", comic.progress);
                item.put("importedAt", comic.importedAt);
                item.put("lastRead", comic.lastRead);
                item.put("favorite", comic.favorite);
                item.put("collection", comic.collection);
                JSONArray sources = new JSONArray();
                for (String source : preferences.getStringSet(comic.id + ".mergedSources", new HashSet<>())) sources.put(source);
                item.put("mergedSources", sources);
                items.put(item);
            }
            manifest.put("comics", items);
            return manifest;
        } catch (JSONException error) {
            throw new IOException("无法生成备份清单", error);
        }
    }

    private static byte[] extractBackup(Context context, Uri source, File temporaryComics,
                                        ComicRepository.BackupProgress progress) throws IOException {
        InputStream raw = context.getContentResolver().openInputStream(source);
        if (raw == null) throw new IOException("无法读取备份文件");
        byte[] manifest = null;
        long totalBytes = 0L;
        int files = 0;
        Set<String> extractedPaths = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (name.equals("manifest.json")) {
                    if (manifest != null) throw new IOException("备份包含重复清单");
                    manifest = readManifest(zip, buffer);
                    zip.closeEntry();
                    continue;
                }
                if (!name.startsWith("comics/")) throw new IOException("备份包含不支持的文件：" + name);
                String[] parts = name.split("/", 3);
                if (parts.length != 3 || !ArchiveUtils.isSafeBackupId(parts[1])
                        || !ArchiveUtils.isSafeRelativeBackupPath(parts[2]) || !ArchiveUtils.isImage(parts[2])) {
                    throw new IOException("备份包含不安全的文件路径");
                }
                if (!extractedPaths.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IOException("备份包含重复的漫画文件路径");
                }
                if (++files > MAX_BACKUP_FILES) throw new IOException("备份文件数量超过安全上限");
                File comicRoot = new File(temporaryComics, parts[1]);
                File output = new File(comicRoot, parts[2].replace('/', File.separatorChar));
                String rootPath = comicRoot.getCanonicalPath() + File.separator;
                if (!output.getCanonicalPath().startsWith(rootPath)) throw new IOException("备份包含不安全的文件路径");
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建漫画恢复目录");
                try (OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        checkCancelled();
                        totalBytes += read;
                        if (totalBytes > MAX_BACKUP_BYTES) throw new IOException("备份内容超过 20 GB 安全上限");
                        target.write(buffer, 0, read);
                    }
                }
                if (progress != null) progress.onProgress("正在恢复漫画页面…", files, -1);
                zip.closeEntry();
            }
        }
        if (manifest == null) throw new IOException("这不是有效的漫匣备份：缺少清单");
        return manifest;
    }

    private static byte[] readManifest(ZipInputStream zip, byte[] buffer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buffer)) != -1) {
            checkCancelled();
            if (output.size() + read > MAX_MANIFEST_BYTES) throw new IOException("备份清单过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static JSONObject parseManifest(byte[] bytes) throws IOException {
        try {
            JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.optString("format")) || manifest.optInt("version", -1) != FORMAT_VERSION) {
                throw new IOException("不支持的漫匣备份格式或版本");
            }
            return manifest;
        } catch (JSONException error) {
            throw new IOException("备份清单已损坏", error);
        }
    }

    private static List<RestoreRecord> validateManifest(JSONObject manifest, File temporaryComics) throws IOException {
        JSONArray comics = manifest.optJSONArray("comics");
        if (comics == null) throw new IOException("备份清单缺少漫画列表");
        List<RestoreRecord> records = new ArrayList<>();
        Set<String> sourceIds = new HashSet<>();
        for (int index = 0; index < comics.length(); index++) {
            JSONObject item = comics.optJSONObject(index);
            if (item == null) throw new IOException("备份清单中的漫画信息无效");
            String sourceId = item.optString("id", "");
            if (!ArchiveUtils.isSafeBackupId(sourceId) || !sourceIds.add(sourceId)) {
                throw new IOException("备份清单包含无效或重复的漫画编号");
            }
            File directory = new File(temporaryComics, sourceId);
            List<File> pages = ArchiveUtils.scanImages(directory);
            if (!directory.isDirectory() || pages.isEmpty()) throw new IOException("备份中的漫画页面不完整");
            String title = item.optString("title", sourceId).trim();
            if (title.isEmpty()) title = sourceId;
            RestoreRecord record = new RestoreRecord(sourceId, title, directory);
            record.progress = item.optInt("progress", -1);
            record.importedAt = item.optLong("importedAt", directory.lastModified());
            record.lastRead = item.optLong("lastRead", 0L);
            record.favorite = item.optBoolean("favorite", false);
            record.collection = cleanCollectionName(item.optString("collection", ""));
            JSONArray sources = item.optJSONArray("mergedSources");
            if (sources != null) {
                for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
                    String mergedSource = sources.optString(sourceIndex, "");
                    if (ArchiveUtils.isSafeBackupId(mergedSource)) record.mergedSources.add(mergedSource);
                }
            }
            records.add(record);
        }
        return records;
    }

    private static Map<String, String> allocateIds(File root, List<RestoreRecord> records) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> reserved = new HashSet<>();
        File[] existing = root.listFiles(File::isDirectory);
        if (existing != null) for (File directory : existing) reserved.add(directory.getName());
        for (RestoreRecord record : records) {
            String targetId = record.sourceId;
            while (reserved.contains(targetId)) targetId = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            reserved.add(targetId);
            ids.put(record.sourceId, targetId);
        }
        return ids;
    }

    private static void writeBytes(ZipOutputStream zip, String name, byte[] bytes, Set<String> entries) throws IOException {
        entries.add(name.toLowerCase(java.util.Locale.ROOT));
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath)) throw new IOException("漫画页面不在漫画目录中");
        return filePath.substring(rootPath.length()).replace(File.separatorChar, '/');
    }

    private static String cleanCollectionName(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.length() > 30 ? clean.substring(0, 30) : clean;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static void checkCancelled() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("操作已取消");
    }

    private static final class RestoreRecord {
        final String sourceId;
        final String title;
        final File directory;
        final Set<String> mergedSources = new HashSet<>();
        File restoredDirectory;
        int progress;
        long importedAt;
        long lastRead;
        boolean favorite;
        String collection = "";

        RestoreRecord(String sourceId, String title, File directory) {
            this.sourceId = sourceId;
            this.title = title;
            this.directory = directory;
        }
    }
}
