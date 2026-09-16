package com.localmanga.shelf;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

final class WindowInsetsHelper {
    private WindowInsetsHelper() {}

    static void enableEdgeToEdge(Window window, View root) {
        if (Build.VERSION.SDK_INT < 30) return;
        window.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
