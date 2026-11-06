package com.tvbox.desktop.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    void load(String url, ImageView view) {
        view.setTag(url);
        if (url == null || url.isEmpty()) return;
        Bitmap ready = cache.get(url);
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap == null) return;
            cache.put(url, bitmap);
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    void shutdown() {
        io.shutdownNow();
        cache.evictAll();
    }

    private static Bitmap download(String target) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(7_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Android TV");
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
