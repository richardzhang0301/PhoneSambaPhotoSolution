package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PhotoRepository {
    private static final String PREFS = "photo_sync_state";
    private static final String UPLOADED_KEYS = "uploaded_keys";

    private PhotoRepository() {
    }

    static List<PhotoItem> loadPhotos(Context context, SambaSettings settings) {
        Set<String> uploadedKeys = loadUploadedKeys(context);
        List<PhotoItem> photos = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_TAKEN
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = resolver.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) {
                return photos;
            }

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            int takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                long size = cursor.getLong(sizeColumn);
                long modified = cursor.getLong(modifiedColumn);
                long taken = cursor.getLong(takenColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                if (TextUtils.isEmpty(name)) {
                    name = "photo_" + id + ".jpg";
                }
                PhotoItem item = new PhotoItem(id, uri, name, size, modified, taken, false);
                item.uploaded = settings.isConfigured() && uploadedKeys.contains(uploadKey(settings, item));
                photos.add(item);
            }
        } catch (RuntimeException ignored) {
            return photos;
        }
        return photos;
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

    private static Set<String> loadUploadedKeys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(UPLOADED_KEYS, new HashSet<String>()));
    }

    private static String uploadKey(SambaSettings settings, PhotoItem item) {
        return settings.identityKey() + "|" + item.mediaKey();
    }
}
