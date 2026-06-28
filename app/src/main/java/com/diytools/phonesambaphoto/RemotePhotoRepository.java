package com.diytools.phonesambaphoto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class RemotePhotoRepository {
    private RemotePhotoRepository() {
    }

    static List<RemotePhotoItem> loadPhotos(SambaSettings settings) throws Exception {
        List<RemotePhotoItem> photos = new ArrayList<>();
        if (!settings.isConfigured()) {
            return photos;
        }

        CIFSContext context = SambaUploader.createContext(settings);
        SmbFile directory = new SmbFile(settings.directoryUrl(), context);
        if (!directory.exists() || !directory.isDirectory()) {
            return photos;
        }

        SmbFile[] files = directory.listFiles();
        if (files == null) {
            return photos;
        }

        for (SmbFile file : files) {
            if (file.isDirectory()) {
                continue;
            }
            String name = cleanName(file.getName());
            if (!isImageName(name)) {
                continue;
            }
            photos.add(new RemotePhotoItem(
                    name,
                    settings.fileUrl(name),
                    file.length(),
                    file.lastModified()
            ));
        }

        Collections.sort(photos, new Comparator<RemotePhotoItem>() {
            @Override
            public int compare(RemotePhotoItem left, RemotePhotoItem right) {
                return Long.compare(right.lastModifiedMillis, left.lastModifiedMillis);
            }
        });
        return photos;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif")
                || lower.endsWith(".bmp");
    }
}
