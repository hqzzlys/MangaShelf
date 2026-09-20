package com.localmanga.shelf;

import android.content.Context;
import android.content.SharedPreferences;

final class ReaderSettings {
    private static final String PREFERENCES = "reader_settings_v2";
    private final SharedPreferences preferences;
    private final String prefix;
    private final boolean defaultRightToLeft;

    ReaderSettings(Context context, String comicId, boolean defaultRightToLeft) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        prefix = comicId + ".";
        this.defaultRightToLeft = defaultRightToLeft;
    }

    boolean isContinuous() { return preferences.getBoolean(prefix + "continuous", false); }
    void setContinuous(boolean value) { preferences.edit().putBoolean(prefix + "continuous", value).apply(); }

    boolean isAutoCrop() { return preferences.getBoolean(prefix + "autoCrop", false); }
    void setAutoCrop(boolean value) { preferences.edit().putBoolean(prefix + "autoCrop", value).apply(); }

    boolean isRightToLeft() { return preferences.getBoolean(prefix + "rightToLeft", defaultRightToLeft); }
    void setRightToLeft(boolean value) { preferences.edit().putBoolean(prefix + "rightToLeft", value).apply(); }

    boolean isFillScreen() { return preferences.getBoolean(prefix + "fillScreen", false); }
    void setFillScreen(boolean value) { preferences.edit().putBoolean(prefix + "fillScreen", value).apply(); }

    boolean isLandscape() { return preferences.getBoolean(prefix + "landscape", false); }
    void setLandscape(boolean value) { preferences.edit().putBoolean(prefix + "landscape", value).apply(); }

    int brightnessPercent() {
        return Math.max(5, Math.min(100, preferences.getInt(prefix + "brightness", 70)));
    }

    void setBrightnessPercent(int value) {
        preferences.edit().putInt(prefix + "brightness", Math.max(5, Math.min(100, value))).apply();
    }
}
