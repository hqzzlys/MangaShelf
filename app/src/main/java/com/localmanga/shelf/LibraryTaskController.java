package com.localmanga.shelf;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class LibraryTaskController {
    interface Listener {
        void onTaskState(State state);
        void onLibrarySnapshot(Snapshot snapshot);
        void onTaskEvent(Event event);
    }

    static final class State {
        final boolean running;
        final String title;
        final String message;
        final boolean cancellable;

        private State(boolean running, String title, String message, boolean cancellable) {
            this.running = running;
            this.title = title;
            this.message = message;
            this.cancellable = cancellable;
        }

        static State idle() { return new State(false, "", "", false); }
        static State running(String title, String message, boolean cancellable) {
            return new State(true, title, message, cancellable);
        }
    }

    static final class Snapshot {
        final List<Comic> comics;
        final long bytes;
        final List<String> collections;

        Snapshot(List<Comic> comics, long bytes, List<String> collections) {
            this.comics = comics;
            this.bytes = bytes;
            this.collections = collections;
        }
    }

    static final class Event {
        final String title;
        final String message;
        final boolean toast;

        Event(String title, String message, boolean toast) {
            this.title = title;
            this.message = message;
            this.toast = toast;
        }
    }

    private final Context context;
    private final ComicRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile State state = State.idle();
    private volatile Snapshot lastSnapshot;
    private volatile Event pendingEvent;
    private volatile Listener listener;
    private volatile Future<?> current;
    private volatile boolean closed;

    LibraryTaskController(Context context) {
        this.context = context.getApplicationContext();
        repository = new ComicRepository(this.context);
    }

    ComicRepository repository() { return repository; }

    void attach(Listener value) {
        listener = value;
        main.post(() -> {
            if (listener != value) return;
            value.onTaskState(state);
            if (lastSnapshot != null) value.onLibrarySnapshot(lastSnapshot);
            Event event = pendingEvent;
            if (event != null) {
                pendingEvent = null;
                value.onTaskEvent(event);
            }
        });
    }

    void detach(Listener value) {
        if (listener == value) listener = null;
    }

    void refresh() {
        if (closed) return;
        worker.execute(this::publishSnapshot);
    }

    void importArchives(List<Uri> uris) {
        start("正在导入漫画", "正在读取压缩包…", true, () -> {
            int success = 0;
            int pages = 0;
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < uris.size(); index++) {
                checkCancelled();
                Uri uri = uris.get(index);
                String name = displayName(uri);
                int currentIndex = index + 1;
                update("正在导入漫画", "正在导入 " + currentIndex + "/" + uris.size() + "\n" + name, true);
                try {
                    Comic comic = repository.importArchive(uri, name, (message, count) ->
                            update("正在导入漫画", "正在导入 " + currentIndex + "/" + uris.size() + "\n" + name + " · " + count + " 页", true));
                    success++;
                    pages += comic.pageCount();
                } catch (Exception error) {
                    if (Thread.currentThread().isInterrupted()) throw error;
                    failures.add(name + "：" + messageOf(error));
                }
            }
            publishSnapshot();
            if (failures.isEmpty()) {
                event(new Event("", "已导入 " + success + " 本漫画，共 " + pages + " 页", true));
            } else {
                String message = "成功 " + success + "/" + uris.size() + " 本\n\n未导入：\n" + android.text.TextUtils.join("\n", failures);
                event(new Event(success > 0 ? "部分导入完成" : "导入失败", message, false));
            }
        });
    }

    void merge(Comic target, Comic sequel) {
        start("正在合并漫画", "正在准备页面…", true, () -> {
            Comic merged = repository.mergeComics(target.id, sequel.id, (copied, total) ->
                    update("正在合并漫画", "正在复制页面 " + copied + "/" + total, true));
            publishSnapshot();
            event(new Event("合并完成", "《" + merged.title + "》现在共有 " + merged.pageCount()
                    + " 页。\n\n续集《" + sequel.title + "》仍保留在书架中，核对页序后可手动删除。", false));
        });
    }

    void delete(Comic comic) {
        start("正在删除漫画", "正在删除《" + comic.title + "》…", false, () -> {
            repository.delete(comic);
            publishSnapshot();
        });
    }

    void exportBackup(Uri destination) {
        start("正在导出书架备份", "正在准备漫画和阅读记录…", true, () -> {
            repository.exportBackup(destination, (message, currentCount, total) ->
                    update("正在导出书架备份", message + " " + currentCount + "/" + total + " 页", true));
            event(new Event("", "书架备份已保存。请妥善保管，备份文件未加密。", true));
        });
    }

    void restoreBackup(Uri source) {
        start("正在恢复书架", "正在检查备份文件…", true, () -> {
            ComicRepository.RestoreResult result = repository.restoreBackup(source, (message, currentCount, total) ->
                    update("正在恢复书架", message + " " + currentCount + " 页", true));
            publishSnapshot();
            event(new Event("书架恢复完成", "已恢复 " + result.comics + " 本漫画，共 " + result.pages + " 页。", false));
        });
    }

    void cancel() {
        Future<?> task = current;
        if (task != null && state.cancellable) task.cancel(true);
    }

    void close() {
        closed = true;
        Future<?> task = current;
        if (task != null) task.cancel(true);
        worker.shutdownNow();
        listener = null;
    }

    private synchronized void start(String title, String message, boolean cancellable, Operation operation) {
        if (closed || state.running) return;
        update(title, message, cancellable);
        current = worker.submit(() -> {
            try {
                operation.run();
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) {
                    publishSnapshot();
                    event(new Event("", "操作已取消，已经完成的内容会保留。", true));
                } else {
                    event(new Event(title.replace("正在", "") + "失败", messageOf(error), false));
                }
            } finally {
                current = null;
                update("", "", false);
            }
        });
    }

    private void publishSnapshot() {
        if (closed) return;
        List<Comic> comics = repository.loadAll();
        Snapshot snapshot = new Snapshot(comics, repository.totalBytes(comics), repository.loadCollections());
        lastSnapshot = snapshot;
        post(listener -> listener.onLibrarySnapshot(snapshot));
    }

    private void update(String title, String message, boolean cancellable) {
        state = title.isEmpty() ? State.idle() : State.running(title, message, cancellable);
        State snapshot = state;
        post(listener -> listener.onTaskState(snapshot));
    }

    private void event(Event event) {
        main.post(() -> {
            Listener target = listener;
            if (closed) return;
            if (target == null) pendingEvent = event;
            else target.onTaskEvent(event);
        });
    }

    private void post(ListenerAction action) {
        main.post(() -> {
            Listener target = listener;
            if (!closed && target != null) action.run(target);
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "新漫画.cbz";
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("操作已取消");
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "发生未知错误，请重试。" : message;
    }

    private interface Operation { void run() throws Exception; }
    private interface ListenerAction { void run(Listener listener); }
}
