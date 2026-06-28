package com.diytools.phonesambaphoto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

final class RemoteThumbLoader {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    RemoteThumbLoader(Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.cache = new LruCache<String, Bitmap>(Math.max(4096, maxKb / 10)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(ImageView imageView, RemotePhotoItem item, SambaSettings settings, int sizePx) {
        String cacheKey = item.cacheKey();
        imageView.setTag(cacheKey);
        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);
        imageView.setBackgroundResource(R.drawable.grid_item_bg);
        executor.execute(() -> {
            Bitmap bitmap = loadBitmap(item, settings, sizePx);
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

    void clear() {
        cache.evictAll();
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private Bitmap loadBitmap(RemotePhotoItem item, SambaSettings settings, int sizePx) {
        try {
            CIFSContext context = SambaUploader.createContext(settings);
            SmbFile file = new SmbFile(item.url, context);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
                BitmapFactory.decodeStream(input, null, bounds);
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int target) {
        if (width <= 0 || height <= 0 || target <= 0) {
            return 1;
        }
        int sample = 1;
        while (height / sample > target * 2 || width / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }
}
