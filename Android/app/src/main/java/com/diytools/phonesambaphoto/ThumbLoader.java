package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ThumbLoader {
    private final Context context;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    ThumbLoader(Context context) {
        this.context = context.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.cache = new LruCache<String, Bitmap>(Math.max(4096, maxKb / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(ImageView imageView, PhotoItem item, int sizePx) {
        String cacheKey = item.mediaKey();
        imageView.setTag(cacheKey);
        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);
        imageView.setBackgroundResource(R.drawable.grid_item_bg);
        executor.execute(() -> {
            Bitmap bitmap = loadBitmap(item, sizePx);
            if (bitmap == null) {
                return;
            }
            cache.put(cacheKey, bitmap);
            main.post(() -> {
                Object tag = imageView.getTag();
                if (cacheKey.equals(tag)) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private Bitmap loadBitmap(PhotoItem item, int sizePx) {
        ContentResolver resolver = context.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return resolver.loadThumbnail(item.uri, new Size(sizePx, sizePx), new CancellationSignal());
            }
            if (item.video) {
                return MediaStore.Video.Thumbnails.getThumbnail(
                        resolver,
                        item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                );
            }
            return MediaStore.Images.Thumbnails.getThumbnail(
                    resolver,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
            );
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}