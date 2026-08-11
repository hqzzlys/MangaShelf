package com.localmanga.shelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ComicRepository {
    public interface Progress { void onProgress(String message, int count); }
    public interface MergeProgress { void onProgress(int copiedPages, int totalPages); }
    private static final String PREFS = "comic_library_v1";
    private static final String COLLECTIONS = "collections";
    private static final long MAX_UNCOMPRESSED = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_FILES = 10000;
    private final Context context;
    private final SharedPreferences prefs;
    private final File root;

    public ComicRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = new File(this.context.getFilesDir(), "comics");
        if (!root.exists()) root.mkdirs();
        recoverInterruptedMerges();
    }

    private void recoverInterruptedMerges() {
        File[] directories = root.listFiles(File::isDirectory); if (directories == null) return;
        for (File directory : directories) {
            String name = directory.getName();
            if (name.startsWith(".merge_")) deleteRecursive(directory);
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
            List<File> pages = scanImages(dir);
            if (pages.isEmpty()) continue;
            String id = dir.getName();
            result.add(new Comic(id, prefs.getString(id + ".title", id), dir, pages,
                    Math.min(prefs.getInt(id + ".progress", 0), pages.size() - 1),
                    prefs.getLong(id + ".imported", dir.lastModified()), prefs.getLong(id + ".lastRead", 0L),
                    prefs.getBoolean(id + ".favorite", false), prefs.getString(id + ".collection", "")));
        }
        result.sort((a, b) -> Long.compare(Math.max(b.lastRead, b.importedAt), Math.max(a.lastRead, a.importedAt)));
        return result;
    }

    public synchronized Comic load(String id) {
        for (Comic comic : loadAll()) if (comic.id.equals(id)) return comic;
        return null;
    }

    public Comic importArchive(Uri uri, String displayName, Progress callback) throws IOException {
        String cleanTitle = stripExtension(displayName == null ? "新漫画" : displayName).trim();
        if (cleanTitle.isEmpty()) cleanTitle = "新漫画";
        String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        File destination = new File(root, id);
        if (!destination.mkdirs()) throw new IOException("无法创建漫画目录");
        long total = 0L; int imageCount = 0; int files = 0;
        Set<String> seen = new HashSet<>();
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法读取所选文件");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry entry; byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !isImage(entry.getName())) { zip.closeEntry(); continue; }
                    if (++files > MAX_FILES) throw new IOException("压缩包文件过多（上限 10000）");
                    String ext = extension(entry.getName());
                    String relative = sanitizePath(entry.getName());
                    if (relative.isEmpty()) relative = String.format(Locale.US, "%06d.%s", files, ext);
                    while (!seen.add(relative.toLowerCase(Locale.ROOT))) relative = String.format(Locale.US, "%06d.%s", files, ext);
                    File out = new File(destination, relative);
                    String rootPath = destination.getCanonicalPath() + File.separator;
                    if (!out.getCanonicalPath().startsWith(rootPath)) throw new IOException("压缩包包含不安全路径");
                    File parent = out.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
                    try (BufferedOutputStream target = new BufferedOutputStream(new FileOutputStream(out))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            if (total > MAX_UNCOMPRESSED) throw new IOException("解压内容超过 2 GB 安全上限");
                            target.write(buffer, 0, read);
                        }
                    }
                    imageCount++;
                    if (callback != null && imageCount % 4 == 0) callback.onProgress("正在整理图片…", imageCount);
                    zip.closeEntry();
                }
            }
        } catch (Exception e) {
            deleteRecursive(destination);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("导入失败：" + e.getMessage(), e);
        }
        if (imageCount == 0) {
            deleteRecursive(destination);
            throw new IOException("压缩包中没有找到 JPG、PNG、WEBP、GIF 或 BMP 图片");
        }
        long now = System.currentTimeMillis();
        prefs.edit().putString(id + ".title", cleanTitle).putLong(id + ".imported", now).apply();
        return new Comic(id, cleanTitle, destination, scanImages(destination), 0, now, 0L, false, "");
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
        List<File> ordered = new ArrayList<>(target.pages); ordered.addAll(sequel.pages);
        try {
            int copied = 0;
            for (File page : ordered) {
                File output = new File(temporary, String.format(Locale.US, "%08d.%s", copied + 1, extension(page.getName())));
                copyFile(page, output); copied++;
                if (progress != null) progress.onProgress(copied, total);
            }
            if (scanImages(temporary).size() != total) throw new IOException("合并后的页数校验失败");
            if (!target.directory.renameTo(backup)) throw new IOException("无法暂存原漫画，未修改任何文件");
            if (!temporary.renameTo(target.directory)) throw new IOException("无法替换合并结果");
            Comic merged = load(target.id);
            if (merged == null || merged.pageCount() != total) throw new IOException("合并结果页数校验失败");
            previousSources.add(sequel.id);
            prefs.edit().putStringSet(target.id + ".mergedSources", previousSources).apply();
            deleteRecursive(backup);
            return merged;
        } catch (IOException error) {
            if (backup.exists()) {
                if (target.directory.exists()) deleteRecursive(target.directory);
                backup.renameTo(target.directory);
            }
            deleteRecursive(temporary); throw error;
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            int read; while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
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

    private static List<File> scanImages(File directory) {
        List<File> files = new ArrayList<>(); collect(directory, files);
        Collator collator = Collator.getInstance(Locale.CHINA);
        files.sort((a, b) -> naturalCompare(a.getAbsolutePath(), b.getAbsolutePath(), collator)); return files;
    }
    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collect(child, out); else if (isImage(child.getName())) out.add(child); }
    }
    private static int naturalCompare(String a, String b, Collator collator) {
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                long na = 0, nb = 0;
                while (ia < a.length() && Character.isDigit(a.charAt(ia))) na = Math.min(Long.MAX_VALUE / 10, na) * 10 + a.charAt(ia++) - '0';
                while (ib < b.length() && Character.isDigit(b.charAt(ib))) nb = Math.min(Long.MAX_VALUE / 10, nb) * 10 + b.charAt(ib++) - '0';
                if (na != nb) return Long.compare(na, nb);
            } else {
                int sa = ia, sb = ib; while (ia < a.length() && !Character.isDigit(a.charAt(ia))) ia++;
                while (ib < b.length() && !Character.isDigit(b.charAt(ib))) ib++;
                int cmp = collator.compare(a.substring(sa, ia), b.substring(sb, ib)); if (cmp != 0) return cmp;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
    private static boolean isImage(String name) {
        String x = name.toLowerCase(Locale.ROOT);
        return x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png") || x.endsWith(".webp") || x.endsWith(".gif") || x.endsWith(".bmp");
    }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "jpg" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static String sanitizePath(String path) {
        String[] parts = path.replace('\\', '/').split("/"); StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("__MACOSX")) continue;
            String safe = part.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");
            if (out.length() > 0) out.append(File.separator); out.append(safe);
        }
        return out.toString();
    }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursive(child); }
        file.delete();
    }
}
