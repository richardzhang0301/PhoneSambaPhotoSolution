package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PhotoRepository {
    private static final String PREFS = "photo_sync_state";
    private static final String UPLOADED_KEYS = "uploaded_keys";

    private PhotoRepository() {
    }

    static List<PhotoItem> loadPhotos(Context context, SambaSettings settings) {
        Set<String> uploadedKeys = loadUploadedKeys(context);
        List<PhotoItem> media = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        loadImages(resolver, settings, uploadedKeys, media);
        loadVideos(resolver, settings, uploadedKeys, media);
        Collections.sort(media, new Comparator<PhotoItem>() {
            @Override
            public int compare(PhotoItem left, PhotoItem right) {
                return Long.compare(right.sortTimeMillis(), left.sortTimeMillis());
            }
        });
        return media;
    }

    static void markUploaded(Context context, SambaSettings settings, PhotoItem item) {
        if (!settings.isConfigured()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> keys = new HashSet<>(prefs.getStringSet(UPLOADED_KEYS, new HashSet<String>()));
        keys.add(uploadKey(settings, item));
        prefs.edit().putStringSet(UPLOADED_KEYS, keys).apply();
        item.uploaded = true;
    }

    private static void loadImages(ContentResolver resolver, SambaSettings settings, Set<String> uploadedKeys, List<PhotoItem> media) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_TAKEN
            };
        }
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = resolver.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            int addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int ownerColumn = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                long size = cursor.getLong(sizeColumn);
                long modified = cursor.getLong(modifiedColumn);
                long added = cursor.getLong(addedColumn);
                long taken = cursor.getLong(takenColumn);
                String ownerPackage = ownerColumn >= 0 ? cursor.getString(ownerColumn) : "";
                Uri uri = ContentUris.withAppendedId(collection, id);
                if (TextUtils.isEmpty(name)) {
                    name = "photo_" + id + ".jpg";
                }
                long displayTime = taken > 0L ? taken : added * 1000L;
                PhotoItem item = new PhotoItem(id, uri, name, size, modified, displayTime, false, false, isGoogleDriveOwner(ownerPackage));
                item.uploaded = settings.isConfigured() && uploadedKeys.contains(uploadKey(settings, item));
                media.add(item);
            }
        } catch (RuntimeException ignored) {
            // Keep the rest of the gallery usable if one MediaStore query fails.
        }
    }

    private static void loadVideos(ContentResolver resolver, SambaSettings settings, Set<String> uploadedKeys, List<PhotoItem> media) {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED
            };
        }
        String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = resolver.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int ownerColumn = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                long size = cursor.getLong(sizeColumn);
                long modified = cursor.getLong(modifiedColumn);
                long added = cursor.getLong(addedColumn);
                String ownerPackage = ownerColumn >= 0 ? cursor.getString(ownerColumn) : "";
                Uri uri = ContentUris.withAppendedId(collection, id);
                if (TextUtils.isEmpty(name)) {
                    name = "video_" + id + ".mp4";
                }
                PhotoItem item = new PhotoItem(id, uri, name, size, modified, added * 1000L, false, true, isGoogleDriveOwner(ownerPackage));
                item.uploaded = settings.isConfigured() && uploadedKeys.contains(uploadKey(settings, item));
                media.add(item);
            }
        } catch (RuntimeException ignored) {
            // Keep the rest of the gallery usable if one MediaStore query fails.
        }
    }

    private static boolean isGoogleDriveOwner(String ownerPackage) {
        if (TextUtils.isEmpty(ownerPackage)) {
            return false;
        }
        String owner = ownerPackage.toLowerCase(Locale.US);
        return owner.equals("com.google.android.apps.docs")
                || owner.startsWith("com.google.android.apps.docs.")
                || owner.contains("google.android.apps.drive");
    }

    private static Set<String> loadUploadedKeys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(UPLOADED_KEYS, new HashSet<String>()));
    }

    private static String uploadKey(SambaSettings settings, PhotoItem item) {
        return settings.identityKey() + "|" + item.mediaKey();
    }
}