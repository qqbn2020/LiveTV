package com.livetv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.livetv.net.Http;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 频道台标异步加载，带内存缓存与按目标尺寸降采样。 */
public final class LogoLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(40);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LogoLoader() {
    }

    public static void load(ImageView view, String url, String ua) {
        view.setTag(url == null ? "" : url);
        if (url == null || url.trim().isEmpty()) {
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setVisibility(View.VISIBLE);
            return;
        }
        view.setImageDrawable(null);
        view.setVisibility(View.VISIBLE);
        final String key = url;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = Http.bytes(view.getContext().getApplicationContext(), key, ua, null);
                    Bitmap bitmap = decode(data, 96);
                    if (bitmap == null) return;
                    CACHE.put(key, bitmap);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (key.equals(view.getTag())) {
                                view.setImageBitmap(bitmap);
                                view.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static Bitmap decode(byte[] data, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > target * 2 && bounds.outHeight / sample > target * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
}
