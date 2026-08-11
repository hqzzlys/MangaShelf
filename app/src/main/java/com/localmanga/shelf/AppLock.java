package com.localmanga.shelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AppLock {
    private static final String PREFS = "app_lock_v1";
    private static final String SALT = "salt";
    private static final String HASH = "hash";
    private final SharedPreferences preferences;
    private static volatile boolean sessionUnlocked;

    AppLock(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return preferences.contains(SALT) && preferences.contains(HASH);
    }

    static boolean isSessionUnlocked() { return sessionUnlocked; }
    static void unlockSession() { sessionUnlocked = true; }

    void setPassword(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        preferences.edit()
                .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(HASH, Base64.encodeToString(digest(salt, password), Base64.NO_WRAP))
                .apply();
    }

    boolean verify(String password) {
        try {
            byte[] salt = Base64.decode(preferences.getString(SALT, ""), Base64.NO_WRAP);
            byte[] saved = Base64.decode(preferences.getString(HASH, ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(saved, digest(salt, password));
        } catch (Exception ignored) { return false; }
    }

    void disable() { preferences.edit().clear().apply(); sessionUnlocked = true; }

    private static byte[] digest(byte[] salt, String password) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        } finally { spec.clearPassword(); }
    }
}
