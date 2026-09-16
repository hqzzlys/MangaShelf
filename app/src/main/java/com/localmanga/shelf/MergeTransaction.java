package com.localmanga.shelf;

import java.io.File;
import java.util.Set;

final class MergeTransaction {
    static final String PREPARING = "preparing";
    static final String BACKED_UP = "backed_up";
    static final String REPLACED = "replaced";
    static final String COMMITTED = "committed";

    interface Store {
        State load();
        boolean save(State state);
        boolean commitMergedSources(String targetId, Set<String> sources, State committedState);
        void clear();
    }

    static final class State {
        final String stage;
        final String targetName;
        final String temporaryName;
        final String backupName;

        State(String stage, String targetName, String temporaryName, String backupName) {
            this.stage = stage;
            this.targetName = targetName;
            this.temporaryName = temporaryName;
            this.backupName = backupName;
        }

        boolean exists() { return stage != null && !stage.isEmpty(); }
        State at(String nextStage) { return new State(nextStage, targetName, temporaryName, backupName); }
    }

    private final File root;
    private final Store store;
    private State state;

    MergeTransaction(File root, Store store) {
        this.root = root;
        this.store = store;
    }

    boolean begin(File target, File temporary, File backup) {
        state = new State(PREPARING, target.getName(), temporary.getName(), backup.getName());
        return store.save(state);
    }

    boolean markBackedUp() { return mark(BACKED_UP); }
    boolean markReplaced() { return mark(REPLACED); }

    boolean commitMergedSources(String targetId, Set<String> sources) {
        state = state.at(COMMITTED);
        return store.commitMergedSources(targetId, sources, state);
    }

    void finish() {
        if (state != null) {
            deleteRecursive(child(state.backupName));
            deleteRecursive(child(state.temporaryName));
        }
        store.clear();
        state = null;
    }

    void rollback() {
        if (state == null) state = store.load();
        if (state == null || !state.exists()) return;
        File target = child(state.targetName);
        File backup = child(state.backupName);
        if (target == null || backup == null) return;
        if (backup.exists()) {
            deleteRecursive(target);
            if (!backup.renameTo(target)) return;
        }
        deleteRecursive(child(state.temporaryName));
        store.clear();
        state = null;
    }

    void recover() {
        state = store.load();
        if (state == null || !state.exists()) return;
        if (COMMITTED.equals(state.stage)) finish();
        else rollback();
    }

    private boolean mark(String stage) {
        state = state.at(stage);
        return store.save(state);
    }

    private File child(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) return null;
        return new File(root, name);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
