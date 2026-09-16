package com.localmanga.shelf;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class MergeTransactionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recoveryRollsBackReplacementBeforeMetadataCommit() throws Exception {
        File root = temporary.newFolder("rollback");
        File target = folderWithMarker(root, "comic", "replacement");
        File backup = folderWithMarker(root, ".backup_comic", "original");
        File pending = folderWithMarker(root, ".merge_pending", "temporary");
        FakeStore store = new FakeStore(new MergeTransaction.State(
                MergeTransaction.REPLACED, target.getName(), pending.getName(), backup.getName()));

        new MergeTransaction(root, store).recover();

        assertTrue(new File(target, "original").isFile());
        assertFalse(new File(target, "replacement").exists());
        assertFalse(backup.exists());
        assertFalse(pending.exists());
        assertNull(store.state);
    }

    @Test public void recoveryKeepsReplacementAfterAtomicMetadataCommit() throws Exception {
        File root = temporary.newFolder("commit");
        File target = folderWithMarker(root, "comic", "replacement");
        File backup = folderWithMarker(root, ".backup_comic", "original");
        File pending = folderWithMarker(root, ".merge_pending", "temporary");
        FakeStore store = new FakeStore(new MergeTransaction.State(
                MergeTransaction.COMMITTED, target.getName(), pending.getName(), backup.getName()));

        new MergeTransaction(root, store).recover();

        assertTrue(new File(target, "replacement").isFile());
        assertFalse(backup.exists());
        assertFalse(pending.exists());
        assertNull(store.state);
    }

    private static File folderWithMarker(File root, String name, String marker) throws Exception {
        File directory = new File(root, name);
        assertTrue(directory.mkdir());
        Files.write(new File(directory, marker).toPath(), marker.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static final class FakeStore implements MergeTransaction.Store {
        MergeTransaction.State state;

        FakeStore(MergeTransaction.State state) { this.state = state; }
        @Override public MergeTransaction.State load() { return state; }
        @Override public boolean save(MergeTransaction.State value) { state = value; return true; }
        @Override public boolean commitMergedSources(String targetId, Set<String> sources, MergeTransaction.State value) {
            state = value; return true;
        }
        @Override public void clear() { state = null; }
    }
}
