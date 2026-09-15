package com.localmanga.shelf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CoverLoader {
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache;
    private volatile boolean closed;

    CoverLoader() {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        cache = new LruCache<String, Bitmap>(Math.max(2048, maxMemoryKb / 16)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(File file, ImageView view, int targetWidth, int targetHeight) {
        if (file == null || closed) return;
        String path = file.getAbsolutePath();
        view.setTag(path);
        Bitmap cached = cache.get(path);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        worker.execute(() -> {
            Bitmap bitmap = decode(path, targetWidth, targetHeight);
            if (bitmap == null || closed) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            cache.put(path, bitmap);
            view.post(() -> {
                if (!closed && path.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        cache.evictAll();
    }

    private static Bitmap decode(String path, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, options);
    }
}
