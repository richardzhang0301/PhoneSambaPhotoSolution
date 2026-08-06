package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.provider.MediaStore;
import android.util.Size;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileOutputStream;

final class SambaUploader {
    interface Listener {
        void onProgress(int done, int total, String message);

        void onItemFinished(PhotoItem item);
    }

    static final class Summary {
        int uploaded;
        int skipped;
        int failed;

        int totalDone() {
            return uploaded + skipped + failed;
        }
    }

    private SambaUploader() {
    }

    static Summary upload(Context context, SambaSettings settings, List<PhotoItem> items, Listener listener) {
        Summary summary = new Summary();
        if (items.isEmpty()) {
            return summary;
        }

        try {
            CIFSContext smbContext = createContext(settings);
            SmbFile directory = new SmbFile(settings.directoryUrl(), smbContext);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            ContentResolver resolver = context.getContentResolver();
            int total = items.size();
            for (PhotoItem item : items) {
                listener.onProgress(summary.totalDone(), total, uploadProgressText(context, item.name));
                try {
                    UploadState state = uploadOne(resolver, settings, smbContext, item);
                    if (state == UploadState.SKIPPED) {
                        summary.skipped++;
                    } else {
                        summary.uploaded++;
                    }
                    PhotoRepository.markUploaded(context, settings, item);
                    listener.onItemFinished(item);
                } catch (Exception ignored) {
                    summary.failed++;
                }
                listener.onProgress(summary.totalDone(), total, progressText(context, summary, total));
            }
        } catch (Exception ignored) {
            summary.failed = items.size();
        }
        return summary;
    }

    static CIFSContext createContext(SambaSettings settings) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.enableSMB2", "true");
        properties.setProperty("jcifs.smb.client.responseTimeout", "30000");
        properties.setProperty("jcifs.smb.client.soTimeout", "30000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(properties));
        NtlmPasswordAuthenticator authenticator = new NtlmPasswordAuthenticator(
                settings.domain,
                settings.username,
                settings.password
        );
        return base.withCredentials(authenticator);
    }

    private static UploadState uploadOne(ContentResolver resolver, SambaSettings settings, CIFSContext context, PhotoItem item) throws Exception {
        SmbFile target = new SmbFile(settings.fileUrl(item.name), context);
        if (target.exists() && item.size > 0 && target.length() == item.size) {
            uploadThumbnailBestEffort(resolver, settings, context, item, target);
            return UploadState.SKIPPED;
        }
        if (target.exists()) {
            target = findAvailableTarget(settings, context, item.name);
        }

        try (InputStream rawInput = resolver.openInputStream(item.uri)) {
            if (rawInput == null) {
                throw new IOException("Media cannot be opened");
            }
            try (BufferedInputStream input = new BufferedInputStream(rawInput);
                 BufferedOutputStream output = new BufferedOutputStream(new SmbFileOutputStream(target))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        }

        if (item.dateModifiedSeconds > 0) {
            target.setLastModified(item.dateModifiedSeconds * 1000L);
        }
        uploadThumbnailBestEffort(resolver, settings, context, item, target);
        return UploadState.UPLOADED;
    }

    private static void uploadThumbnailBestEffort(ContentResolver resolver, SambaSettings settings, CIFSContext context, PhotoItem item, SmbFile target) {
        try {
            Bitmap thumbnail = loadThumbnail(resolver, item);
            if (thumbnail == null) {
                return;
            }
            Bitmap prepared = prepareThumbnail(thumbnail, item.video);
            if (prepared == null) {
                return;
            }
            writeThumbnail(settings, context, target, prepared);
        } catch (Exception ignored) {
            // Media upload is authoritative; SambaTools can regenerate a missing thumbnail later.
        }
    }

    private static Bitmap loadThumbnail(ContentResolver resolver, PhotoItem item) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolver.loadThumbnail(
                    item.uri,
                    new Size(SambaThumbnailSpec.SIZE_PX, SambaThumbnailSpec.SIZE_PX),
                    new CancellationSignal()
            );
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
    }

    private static Bitmap prepareThumbnail(Bitmap source, boolean video) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        Bitmap scaled = scaleInside(source, SambaThumbnailSpec.SIZE_PX);
        return video ? addPlayOverlay(scaled) : copyForJpeg(scaled);
    }

    private static Bitmap scaleInside(Bitmap source, int maxSize) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxSize && height <= maxSize) {
            return source;
        }
        float scale = Math.min(maxSize / (float) width, maxSize / (float) height);
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
    }

    private static Bitmap copyForJpeg(Bitmap source) {
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, false);
        return result == null ? source : result;
    }

    private static Bitmap addPlayOverlay(Bitmap source) {
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        if (result == null) {
            result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            new Canvas(result).drawBitmap(source, 0, 0, null);
        }

        int width = result.getWidth();
        int height = result.getHeight();
        int minDim = Math.max(1, Math.min(width, height));
        int diameter = Math.max(32, (int) (minDim * 0.42f));
        float radius = diameter / 2f;
        float centerX = width / 2f;
        float centerY = height / 2f;

        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(118, 0, 0, 0));
        canvas.drawOval(new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius), paint);

        float triangleWidth = diameter * 0.38f;
        float triangleHeight = diameter * 0.46f;
        float left = centerX - triangleWidth * 0.28f;
        Path triangle = new Path();
        triangle.moveTo(left, centerY - triangleHeight / 2f);
        triangle.lineTo(left, centerY + triangleHeight / 2f);
        triangle.lineTo(centerX + triangleWidth * 0.58f, centerY);
        triangle.close();
        paint.setColor(Color.argb(215, 255, 255, 255));
        canvas.drawPath(triangle, paint);
        return result;
    }

    private static void writeThumbnail(SambaSettings settings, CIFSContext context, SmbFile mediaFile, Bitmap bitmap) throws Exception {
        String remoteName = cleanName(mediaFile.getName());
        if (TextUtils.isEmpty(remoteName)) {
            return;
        }
        long remoteSize = mediaFile.length();
        long remoteModified = mediaFile.lastModified();
        String thumbnailName = SambaThumbnailSpec.thumbnailName(remoteName, remoteSize, remoteModified);
        SmbFile thumbnailDirectory = new SmbFile(settings.childUrl(SambaThumbnailSpec.DIR + "/"), context);
        if (!thumbnailDirectory.exists()) {
            thumbnailDirectory.mkdirs();
        }

        SmbFile thumbnail = new SmbFile(settings.childUrl(SambaThumbnailSpec.DIR + "/" + thumbnailName), context);
        if (thumbnail.exists() && thumbnail.length() > 0) {
            return;
        }

        String tempName = thumbnailName.substring(0, thumbnailName.length() - 4) + ".tmp";
        SmbFile temp = new SmbFile(settings.childUrl(SambaThumbnailSpec.DIR + "/" + tempName), context);
        if (temp.exists()) {
            temp.delete();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, SambaThumbnailSpec.QUALITY, bytes)) {
            return;
        }
        try (BufferedOutputStream output = new BufferedOutputStream(new SmbFileOutputStream(temp))) {
            output.write(bytes.toByteArray());
            output.flush();
        }
        temp.renameTo(thumbnail);
    }

    private static String cleanName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static SmbFile findAvailableTarget(SambaSettings settings, CIFSContext context, String originalName) throws Exception {
        String baseName = originalName;
        String extension = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0 && dot < originalName.length() - 1) {
            baseName = originalName.substring(0, dot);
            extension = originalName.substring(dot);
        }
        for (int index = 2; index < 10000; index++) {
            String candidate = String.format(Locale.US, "%s_%d%s", baseName, index, extension);
            SmbFile file = new SmbFile(settings.fileUrl(candidate), context);
            if (!file.exists()) {
                return file;
            }
        }
        throw new IOException("No available file name");
    }

    private static String uploadProgressText(Context context, String name) {
        return UiText.isChinese(context) ? "正在上传 " + name : "Uploading " + name;
    }

    private static String progressText(Context context, Summary summary, int total) {
        if (UiText.isChinese(context)) {
            return "完成 " + summary.totalDone() + " / " + total
                    + "  已上传 " + summary.uploaded
                    + "  已跳过 " + summary.skipped
                    + "  失败 " + summary.failed;
        }
        return "Done " + summary.totalDone() + " of " + total
                + "  Uploaded " + summary.uploaded
                + "  Skipped " + summary.skipped
                + "  Failed " + summary.failed;
    }

    private enum UploadState {
        UPLOADED,
        SKIPPED
    }
}
