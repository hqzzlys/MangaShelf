package com.localmanga.shelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class ComicRepository {
    public interface Progress { void onProgress(String message, int count); }
    public interface MergeProgress { void onProgress(int copiedPages, int totalPages); }
    public interface BackupProgress { void onProgress(String message, int current, int total); }
    public static final class RestoreResult {
        public final int comics;
        public final int pages;

        RestoreResult(int comics, int pages) {
            this.comics = comics;
            this.pages = pages;
        }
    }
    private static final String PREFS = "comic_library_v1";
    private static final String COLLECTIONS = "collections";
    private final Context context;
    private final SharedPreferences prefs;
    private final File root;
    private final MergeTransaction mergeTransaction;

    public ComicRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = new File(this.context.getFilesDir(), "comics");
        if (!root.exists()) root.mkdirs();
        mergeTransaction = new MergeTransaction(root, new PreferencesMergeStore(prefs));
        recoverInterruptedMerges();
    }

    private void recoverInterruptedMerges() {
        mergeTransaction.recover();
        File[] directories = root.listFiles(File::isDirectory); if (directories == null) return;
        for (File directory : directories) {
            String name = directory.getName();
            if (name.startsWith(".merge_") || name.startsWith(".import_") || name.startsWith(".restore_")) deleteRecursive(directory);
            else if (name.startsWith(".backup_")) {
                File original = new File(root, name.substring(".backup_".length()));
                if (original.exists()) deleteRecursive(directory); else directory.renameTo(original);
            }
        }
    }

    public synchronized List<Comic> loadAll() {
        List<Comic> result = new ArrayList<>();
        File[] dirs = root.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (dirs == null) return result;
        for (File dir : dirs) {
            Comic comic = loadDirectory(dir);
            if (comic != null) result.add(comic);
        }
        result.sort((a, b) -> Long.compare(Math.max(b.lastRead, b.importedAt), Math.max(a.lastRead, a.importedAt)));
        return result;
    }

    public synchronized Comic load(String id) {
        if (!ArchiveUtils.isSafeBackupId(id)) return null;
        return loadDirectory(new File(root, id));
    }

    private Comic loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory() || dir.getName().startsWith(".")) return null;
        List<File> pages = ArchiveUtils.scanImages(dir);
        if (pages.isEmpty()) return null;
        String id = dir.getName();
        int savedProgress = prefs.getInt(id + ".progress", -1);
        int progress = Math.max(-1, Math.min(savedProgress, pages.size() - 1));
        return new Comic(id, prefs.getString(id + ".title", id), dir, pages, progress,
                prefs.getLong(id + ".imported", dir.lastModified()), prefs.getLong(id + ".lastRead", 0L),
                prefs.getBoolean(id + ".favorite", false), prefs.getString(id + ".collection", ""));
    }

    public synchronized void exportBackup(Uri destination, BackupProgress progress) throws IOException {
        LibraryBackup.exportLibrary(context, prefs, loadAll(), destination, progress);
    }

    public synchronized RestoreResult restoreBackup(Uri source, BackupProgress progress) throws IOException {
        return LibraryBackup.restoreLibrary(context, prefs, root, source, progress);
    }

    public Comic importArchive(Uri uri, String displayName, Progress callback) throws IOException {
        String cleanTitle = stripExtension(displayName == null ? "新漫画" : displayName).trim();
        if (cleanTitle.isEmpty()) cleanTitle = "新漫画";
        String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        File destination = new File(root, id);
        File temporary = new File(root, ".import_" + id);
        if (!temporary.mkdirs()) throw new IOException("无法创建漫画目录");
        int imageCount;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法读取所选文件");
            imageCount = ArchiveExtractor.extract(raw, temporary,
                    count -> { if (callback != null) callback.onProgress("正在整理图片…", count); },
                    ComicRepository::isDecodableImage);
        } catch (Exception e) {
            deleteRecursive(temporary);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("导入失败：" + e.getMessage(), e);
        }
        if (imageCount == 0) {
            deleteRecursive(temporary);
            throw new IOException("压缩包中没有找到 JPG、PNG、WEBP、GIF 或 BMP 图片");
        }
        if (!temporary.renameTo(destination)) {
            deleteRecursive(temporary);
            throw new IOException("无法提交导入结果，请重试");
        }
        long now = System.currentTimeMillis();
        prefs.edit().putString(id + ".title", cleanTitle).putLong(id + ".imported", now).apply();
        return new Comic(id, cleanTitle, destination, ArchiveUtils.scanImages(destination), -1, now, 0L, false, "");
    }

    public void saveProgress(String id, int page) {
        prefs.edit().putInt(id + ".progress", Math.max(0, page)).putLong(id + ".lastRead", System.currentTimeMillis()).apply();
    }
    public void setFavorite(Comic comic, boolean value) {
        comic.favorite = value; prefs.edit().putBoolean(comic.id + ".favorite", value).apply();
    }
    public void rename(Comic comic, String name) {
        String clean = name == null ? "" : name.trim(); if (clean.isEmpty()) return;
        comic.title = clean; prefs.edit().putString(comic.id + ".title", clean).apply();
    }

    public synchronized List<String> loadCollections() {
        Set<String> saved = new TreeSet<>(Collator.getInstance(Locale.CHINA));
        saved.addAll(prefs.getStringSet(COLLECTIONS, new HashSet<>()));
        return new ArrayList<>(saved);
    }

    public synchronized boolean addCollection(String name) {
        String clean = cleanCollectionName(name); if (clean.isEmpty()) return false;
        Set<String> saved = new HashSet<>(prefs.getStringSet(COLLECTIONS, new HashSet<>()));
        for (String item : saved) if (item.equalsIgnoreCase(clean)) return false;
        saved.add(clean); prefs.edit().putStringSet(COLLECTIONS, saved).apply(); return true;
    }

    public synchronized void setCollection(Comic comic, String collection) {
        String clean = cleanCollectionName(collection);
        if (!clean.isEmpty()) addCollection(clean);
        comic.collection = clean;
        prefs.edit().putString(comic.id + ".collection", clean).apply();
    }

    public synchronized boolean renameCollection(String oldName, String newName) {
        String clean = cleanCollectionName(newName); if (clean.isEmpty()) return false;
        List<String> collections = loadCollections();
        for (String item : collections) if (!item.equals(oldName) && item.equalsIgnoreCase(clean)) return false;
        Set<String> saved = new HashSet<>(collections); saved.remove(oldName); saved.add(clean);
        SharedPreferences.Editor editor = prefs.edit().putStringSet(COLLECTIONS, saved);
        for (Comic comic : loadAll()) if (comic.collection.equals(oldName)) editor.putString(comic.id + ".collection", clean);
        editor.apply(); return true;
    }

    public synchronized void deleteCollection(String name) {
        Set<String> saved = new HashSet<>(prefs.getStringSet(COLLECTIONS, new HashSet<>())); saved.remove(name);
        SharedPreferences.Editor editor = prefs.edit().putStringSet(COLLECTIONS, saved);
        for (Comic comic : loadAll()) if (comic.collection.equals(name)) editor.putString(comic.id + ".collection", "");
        editor.apply();
    }

    public synchronized Comic mergeComics(String targetId, String sequelId, MergeProgress progress) throws IOException {
        if (targetId == null || sequelId == null || targetId.equals(sequelId)) throw new IOException("请选择两本不同的漫画");
        Comic target = load(targetId), sequel = load(sequelId);
        if (target == null || sequel == null) throw new IOException("漫画文件已不存在，请刷新书架后重试");
        Set<String> previousSources = new HashSet<>(prefs.getStringSet(target.id + ".mergedSources", new HashSet<>()));
        if (previousSources.contains(sequel.id)) throw new IOException("这本续集已经合并到目标漫画中");
        int total = target.pageCount() + sequel.pageCount();
        File temporary = new File(root, ".merge_" + UUID.randomUUID());
        File backup = new File(root, ".backup_" + target.id);
        deleteRecursive(temporary); deleteRecursive(backup);
        if (!temporary.mkdirs()) throw new IOException("无法创建合并临时目录");
        if (!mergeTransaction.begin(target.directory, temporary, backup)) {
            deleteRecursive(temporary);
            throw new IOException("无法保存合并事务状态");
        }
        List<File> ordered = new ArrayList<>(target.pages); ordered.addAll(sequel.pages);
        try {
            int copied = 0;
            for (File page : ordered) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消");
                File output = new File(temporary, String.format(Locale.US, "%08d.%s", copied + 1, ArchiveUtils.extension(page.getName())));
                copyFile(page, output); copied++;
                if (progress != null) progress.onProgress(copied, total);
            }
            if (ArchiveUtils.scanImages(temporary).size() != total) throw new IOException("合并后的页数校验失败");
            if (!target.directory.renameTo(backup)) throw new IOException("无法暂存原漫画，未修改任何文件");
            if (!mergeTransaction.markBackedUp()) throw new IOException("无法保存合并事务状态");
            if (!temporary.renameTo(target.directory)) throw new IOException("无法替换合并结果");
            if (!mergeTransaction.markReplaced()) throw new IOException("无法保存合并事务状态");
            Comic merged = load(target.id);
            if (merged == null || merged.pageCount() != total) throw new IOException("合并结果页数校验失败");
            previousSources.add(sequel.id);
            if (!mergeTransaction.commitMergedSources(target.id, previousSources)) throw new IOException("无法提交合并元数据");
            mergeTransaction.finish();
            return merged;
        } catch (IOException error) {
            mergeTransaction.rollback();
            throw error;
        }
    }

    private static boolean isDecodableImage(File file) {
        if (file == null || !file.isFile() || file.length() == 0L) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private static void copyFile(File source, File destination) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            int read; while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消");
                output.write(buffer, 0, read);
            }
        }
        if (destination.length() != source.length()) throw new IOException("复制页面失败：" + source.getName());
    }

    private static String cleanCollectionName(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.length() > 30 ? clean.substring(0, 30) : clean;
    }
    public synchronized void delete(Comic comic) {
        deleteRecursive(comic.directory);
        prefs.edit().remove(comic.id + ".title").remove(comic.id + ".progress").remove(comic.id + ".imported")
                .remove(comic.id + ".lastRead").remove(comic.id + ".favorite").remove(comic.id + ".collection")
                .remove(comic.id + ".mergedSources").apply();
    }
    public long totalBytes(List<Comic> comics) {
        long total = 0; for (Comic comic : comics) for (File page : comic.pages) total += page.length(); return total;
    }

    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursive(child); }
        file.delete();
    }
}
