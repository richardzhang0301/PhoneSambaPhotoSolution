package com.diytools.phonesambaphoto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

final class RemoteThumbLoader {
    private static final String DISK_CACHE_DIR = "samba_thumb_cache";
    private static final int DISK_CACHE_QUALITY = 86;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final File diskCacheDir;
    private final Object diskLock = new Object();

    RemoteThumbLoader(Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        Context appContext = context.getApplicationContext();
        this.diskCacheDir = new File(appContext.getFilesDir(), DISK_CACHE_DIR);
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
            Bitmap bitmap = loadDiskBitmap(cacheKey);
            if (bitmap == null) {
                bitmap = loadRemoteBitmap(item, settings, sizePx);
                if (bitmap != null) {
                    saveDiskBitmap(cacheKey, bitmap);
                }
            }
            if (bitmap == null) {
                return;
            }
            final Bitmap loadedBitmap = bitmap;
            cache.put(cacheKey, loadedBitmap);
            main.post(() -> {
                Object tag = imageView.getTag();
                if (cacheKey.equals(tag)) {
                    imageView.setImageBitmap(loadedBitmap);
                }
            });
        });
    }

    void clear() {
        cache.evictAll();
    }

    int clearDiskCacheBefore(long cutoffMillis) {
        cache.evictAll();
        synchronized (diskLock) {
            File[] files = diskCacheDir.listFiles();
            if (files == null) {
                return 0;
            }
            int deleted = 0;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoffMillis && file.delete()) {
                    deleted++;
                }
            }
            return deleted;
        }
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private Bitmap loadRemoteBitmap(RemotePhotoItem item, SambaSettings settings, int sizePx) {
        try {
            CIFSContext context = SambaUploader.createContext(settings);
            Bitmap thumbnail = decodeSmallFile(item.thumbnailUrl, context);
            if (thumbnail != null) {
                return thumbnail;
            }
            return decodeOriginal(item.url, context, sizePx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeSmallFile(String url, CIFSContext context) {
        try {
            SmbFile file = new SmbFile(url, context);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeOriginal(String url, CIFSContext context, int sizePx) {
        try {
            SmbFile file = new SmbFile(url, context);

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

    private Bitmap loadDiskBitmap(String cacheKey) {
        File file = diskCacheFile(cacheKey);
        synchronized (diskLock) {
            if (!file.exists() || file.length() <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) {
                file.delete();
                return null;
            }
            file.setLastModified(System.currentTimeMillis());
            return bitmap;
        }
    }

    private void saveDiskBitmap(String cacheKey, Bitmap bitmap) {
        synchronized (diskLock) {
            if (!diskCacheDir.exists() && !diskCacheDir.mkdirs()) {
                return;
            }
            File file = diskCacheFile(cacheKey);
            File temp = new File(diskCacheDir, file.getName() + ".tmp");
            boolean written;
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                written = bitmap.compress(Bitmap.CompressFormat.JPEG, DISK_CACHE_QUALITY, output);
            } catch (Exception ignored) {
                temp.delete();
                return;
            }
            if (!written) {
                temp.delete();
                return;
            }
            if (file.exists() && !file.delete()) {
                temp.delete();
                return;
            }
            if (temp.renameTo(file)) {
                file.setLastModified(System.currentTimeMillis());
            } else {
                temp.delete();
            }
        }
    }

    private File diskCacheFile(String cacheKey) {
        return new File(diskCacheDir, sha1(cacheKey) + ".jpg");
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[hash.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int unsigned = hash[i] & 0xff;
                hex[i * 2] = digits[unsigned >>> 4];
                hex[i * 2 + 1] = digits[unsigned & 0x0f];
            }
            return new String(hex);
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
