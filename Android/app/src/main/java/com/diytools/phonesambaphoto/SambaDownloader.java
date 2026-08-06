package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

final class SambaDownloader {
    interface Listener {
        void onProgress(int done, int total, String message);

        void onItemFinished(RemotePhotoItem remoteItem, PhotoItem localItem);
    }

    static final class Summary {
        int downloaded;
        int skipped;
        int failed;

        int totalDone() {
            return downloaded + skipped + failed;
        }
    }

    private SambaDownloader() {
    }

    static Summary download(Context context, SambaSettings settings, List<RemotePhotoItem> items, Listener listener) {
        Summary summary = new Summary();
        if (items.isEmpty()) {
            return summary;
        }

        try {
            CIFSContext smbContext = SambaUploader.createContext(settings);
            ContentResolver resolver = context.getContentResolver();
            int total = items.size();
            for (RemotePhotoItem item : items) {
                listener.onProgress(summary.totalDone(), total, downloadProgressText(context, item.name));
                try {
                    PhotoItem localItem = findExistingCameraItem(resolver, item);
                    if (localItem == null) {
                        localItem = downloadOne(context, resolver, smbContext, item);
                        summary.downloaded++;
                    } else {
                        summary.skipped++;
                    }
                    PhotoRepository.markUploaded(context, settings, localItem);
                    listener.onItemFinished(item, localItem);
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

    private static PhotoItem downloadOne(Context context, ContentResolver resolver, CIFSContext smbContext, RemotePhotoItem item) throws Exception {
        SmbFile source = new SmbFile(item.url, smbContext);
        if (!source.exists() || source.isDirectory()) {
            throw new IOException("Remote media does not exist");
        }

        String originalName = cleanDisplayName(item.name, item.video);
        String displayName = findAvailableDisplayName(resolver, item.video, originalName);
        String mimeType = mimeType(displayName, item.video);
        Uri collection = collectionUri(item.video);
        File preQFile = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            preQFile = availablePreQFile(displayName);
            displayName = preQFile.getName();
        }

        long modifiedMillis = item.lastModifiedMillis > 0L ? item.lastModifiedMillis : System.currentTimeMillis();
        ContentValues values = mediaValues(displayName, mimeType, item.video, modifiedMillis);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else if (preQFile != null) {
            values.put(MediaStore.MediaColumns.DATA, preQFile.getAbsolutePath());
        }

        Uri destination = resolver.insert(collection, values);
        if (destination == null) {
            throw new IOException("Could not create local media");
        }

        boolean success = false;
        try {
            copy(source, resolver, destination);
            finishMediaInsert(context, resolver, destination, preQFile, mimeType, modifiedMillis);
            success = true;
            PhotoItem downloaded = queryMediaItem(resolver, destination, item.video);
            return downloaded != null && downloaded.size > 0L
                    ? downloaded
                    : fallbackPhotoItem(destination, displayName, item.size, modifiedMillis, item.video);
        } finally {
            if (!success) {
                try {
                    resolver.delete(destination, null, null);
                } catch (RuntimeException ignored) {
                    // Best effort cleanup for incomplete downloads.
                }
            }
        }
    }

    private static ContentValues mediaValues(String displayName, String mimeType, boolean video, long modifiedMillis) {
        long modifiedSeconds = Math.max(1L, modifiedMillis / 1000L);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.DATE_ADDED, modifiedSeconds);
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, modifiedSeconds);
        if (!video) {
            values.put(MediaStore.Images.Media.DATE_TAKEN, modifiedMillis);
        }
        return values;
    }

    private static void copy(SmbFile source, ContentResolver resolver, Uri destination) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(new SmbFileInputStream(source));
             OutputStream rawOutput = resolver.openOutputStream(destination);
             BufferedOutputStream output = rawOutput == null ? null : new BufferedOutputStream(rawOutput)) {
            if (output == null) {
                throw new IOException("Local media cannot be opened");
            }
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static void finishMediaInsert(Context context, ContentResolver resolver, Uri destination, File preQFile, String mimeType, long modifiedMillis) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, Math.max(1L, modifiedMillis / 1000L));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        }
        try {
            resolver.update(destination, values, null, null);
        } catch (RuntimeException ignored) {
            // The row is already inserted; refresh is best effort.
        }
        if (preQFile != null) {
            preQFile.setLastModified(modifiedMillis);
            MediaScannerConnection.scanFile(context, new String[]{preQFile.getAbsolutePath()}, new String[]{mimeType}, null);
        }
    }

    private static PhotoItem findExistingCameraItem(ContentResolver resolver, RemotePhotoItem item) {
        String displayName = cleanDisplayName(item.name, item.video);
        Uri collection = collectionUri(item.video);
        String[] projection = projection(item.video);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=?";
        String[] args = new String[]{displayName, String.valueOf(item.size)};
        try (Cursor cursor = resolver.query(collection, projection, selection, args, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (isCameraPath(pathFromCursor(cursor))) {
                    return mediaItemFromCursor(cursor, collection, item.video);
                }
            }
        } catch (RuntimeException ignored) {
            // Duplicate detection is a convenience; download can still proceed.
        }
        return null;
    }

    private static String findAvailableDisplayName(ContentResolver resolver, boolean video, String displayName) {
        if (!cameraNameExists(resolver, video, displayName)) {
            return displayName;
        }
        String baseName = displayName;
        String extension = "";
        int dot = displayName.lastIndexOf('.');
        if (dot > 0 && dot < displayName.length() - 1) {
            baseName = displayName.substring(0, dot);
            extension = displayName.substring(dot);
        }
        for (int index = 2; index < 10000; index++) {
            String candidate = String.format(Locale.US, "%s_%d%s", baseName, index, extension);
            if (!cameraNameExists(resolver, video, candidate)) {
                return candidate;
            }
        }
        return displayName;
    }

    private static boolean cameraNameExists(ContentResolver resolver, boolean video, String displayName) {
        Uri collection = collectionUri(video);
        String[] projection = projection(video);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = resolver.query(collection, projection, selection, new String[]{displayName}, null)) {
            if (cursor == null) {
                return false;
            }
            while (cursor.moveToNext()) {
                if (isCameraPath(pathFromCursor(cursor))) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static File availablePreQFile(String displayName) throws IOException {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create Camera folder");
        }
        File target = new File(directory, displayName);
        if (!target.exists()) {
            return target;
        }
        String baseName = displayName;
        String extension = "";
        int dot = displayName.lastIndexOf('.');
        if (dot > 0 && dot < displayName.length() - 1) {
            baseName = displayName.substring(0, dot);
            extension = displayName.substring(dot);
        }
        for (int index = 2; index < 10000; index++) {
            target = new File(directory, String.format(Locale.US, "%s_%d%s", baseName, index, extension));
            if (!target.exists()) {
                return target;
            }
        }
        throw new IOException("No available local file name");
    }

    private static PhotoItem queryMediaItem(ContentResolver resolver, Uri uri, boolean video) {
        try (Cursor cursor = resolver.query(uri, projection(video), null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return mediaItemFromCursor(cursor, uri, video);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the values we already know.
        }
        return null;
    }

    private static PhotoItem mediaItemFromCursor(Cursor cursor, Uri collectionOrItemUri, boolean video) {
        int idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
        int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
        int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
        int modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
        int addedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
        int takenColumn = video ? -1 : cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);

        long id = getLong(cursor, idColumn, parseId(collectionOrItemUri));
        Uri uri = collectionOrItemUri;
        if ("content".equals(collectionOrItemUri.getScheme())) {
            try {
                uri = ContentUris.withAppendedId(collectionUri(video), id);
            } catch (RuntimeException ignored) {
                uri = collectionOrItemUri;
            }
        }

        String name = getString(cursor, nameColumn, uri.getLastPathSegment());
        long size = getLong(cursor, sizeColumn, 0L);
        long modified = getLong(cursor, modifiedColumn, 0L);
        long added = getLong(cursor, addedColumn, modified);
        long taken = video ? 0L : getLong(cursor, takenColumn, 0L);
        long displayTime = taken > 0L ? taken : added * 1000L;
        return new PhotoItem(id, uri, name, size, modified, displayTime, false, video);
    }

    private static PhotoItem fallbackPhotoItem(Uri uri, String name, long size, long modifiedMillis, boolean video) {
        return new PhotoItem(parseId(uri), uri, name, size, modifiedMillis / 1000L, modifiedMillis, false, video);
    }

    private static long parseId(Uri uri) {
        try {
            return ContentUris.parseId(uri);
        } catch (RuntimeException ignored) {
            return Math.abs(uri.toString().hashCode());
        }
    }

    private static String[] projection(boolean video) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (video) {
                return new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_MODIFIED,
                        MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.MediaColumns.RELATIVE_PATH
                };
            }
            return new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
        }
        if (video) {
            return new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.MediaColumns.DATA
            };
        }
        return new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.MediaColumns.DATA
        };
    }

    private static String pathFromCursor(Cursor cursor) {
        int pathColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.MediaColumns.RELATIVE_PATH
                : MediaStore.MediaColumns.DATA);
        return getString(cursor, pathColumn, "");
    }

    private static boolean isCameraPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.US);
        return normalized.equals("dcim/camera")
                || normalized.equals("dcim/camera/")
                || normalized.contains("/dcim/camera/");
    }

    private static Uri collectionUri(boolean video) {
        return video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private static String cleanDisplayName(String name, boolean video) {
        String clean = TextUtils.isEmpty(name) ? "" : name.trim().replace('\\', '_').replace('/', '_');
        if (TextUtils.isEmpty(clean)) {
            clean = video ? "video.mp4" : "photo.jpg";
        }
        if (TextUtils.isEmpty(fileExtension(clean))) {
            clean = clean + (video ? ".mp4" : ".jpg");
        }
        return clean;
    }

    private static String mimeType(String name, boolean video) {
        String extension = fileExtension(name);
        if (!TextUtils.isEmpty(extension)) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(type)) {
                return type;
            }
        }
        return video ? "video/mp4" : "image/jpeg";
    }

    private static String fileExtension(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String getString(Cursor cursor, int column, String fallback) {
        if (column < 0 || cursor.isNull(column)) {
            return fallback;
        }
        String value = cursor.getString(column);
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static long getLong(Cursor cursor, int column, long fallback) {
        if (column < 0 || cursor.isNull(column)) {
            return fallback;
        }
        return cursor.getLong(column);
    }

    private static String downloadProgressText(Context context, String name) {
        return UiText.isChinese(context) ? "正在下载 " + name : "Downloading " + name;
    }

    private static String progressText(Context context, Summary summary, int total) {
        if (UiText.isChinese(context)) {
            return "完成 " + summary.totalDone() + " / " + total
                    + "  已下载 " + summary.downloaded
                    + "  已跳过 " + summary.skipped
                    + "  失败 " + summary.failed;
        }
        return "Done " + summary.totalDone() + " of " + total
                + "  Downloaded " + summary.downloaded
                + "  Skipped " + summary.skipped
                + "  Failed " + summary.failed;
    }
}
