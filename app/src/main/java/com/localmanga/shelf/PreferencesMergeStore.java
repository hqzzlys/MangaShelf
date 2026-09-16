package com.localmanga.shelf;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import java.util.Set;

final class PreferencesMergeStore implements MergeTransaction.Store {
    private static final String STAGE = "merge.txn.stage";
    private static final String TARGET = "merge.txn.target";
    private static final String TEMPORARY = "merge.txn.temp";
    private static final String BACKUP = "merge.txn.backup";
    private final SharedPreferences preferences;

    PreferencesMergeStore(SharedPreferences preferences) { this.preferences = preferences; }

    @Override public MergeTransaction.State load() {
        return new MergeTransaction.State(preferences.getString(STAGE, ""), preferences.getString(TARGET, ""),
                preferences.getString(TEMPORARY, ""), preferences.getString(BACKUP, ""));
    }

    @Override public boolean save(MergeTransaction.State state) {
        return preferences.edit().putString(STAGE, state.stage).putString(TARGET, state.targetName)
                .putString(TEMPORARY, state.temporaryName).putString(BACKUP, state.backupName).commit();
    }

    @Override public boolean commitMergedSources(String targetId, Set<String> sources, MergeTransaction.State state) {
        return preferences.edit().putStringSet(targetId + ".mergedSources", sources)
                .putString(STAGE, state.stage).putString(TARGET, state.targetName)
                .putString(TEMPORARY, state.temporaryName).putString(BACKUP, state.backupName).commit();
    }

    @SuppressLint("ApplySharedPref")
    @Override public void clear() {
        // A synchronous clear prevents a stale recovery record from affecting the next merge.
        preferences.edit().remove(STAGE).remove(TARGET).remove(TEMPORARY).remove(BACKUP).commit();
    }
}
