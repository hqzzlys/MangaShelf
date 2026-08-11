package com.localmanga.shelf;

import java.io.File;
import java.util.List;

public final class Comic {
    public final String id;
    public String title;
    public final File directory;
    public final List<File> pages;
    public int progress;
    public long importedAt;
    public long lastRead;
    public boolean favorite;
    public String collection;

    Comic(String id, String title, File directory, List<File> pages, int progress, long importedAt, long lastRead, boolean favorite, String collection) {
        this.id = id; this.title = title; this.directory = directory; this.pages = pages;
        this.progress = progress; this.importedAt = importedAt; this.lastRead = lastRead; this.favorite = favorite;
        this.collection = collection == null ? "" : collection;
    }
    public int pageCount() { return pages.size(); }
    public boolean isComplete() { return !pages.isEmpty() && progress >= pages.size() - 1; }
    public boolean isUnread() { return lastRead == 0L; }
    public File cover() { return pages.isEmpty() ? null : pages.get(0); }
}
