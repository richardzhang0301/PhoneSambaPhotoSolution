package com.diytools.phonesambaphoto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class RemotePhotoRepository {
    private static final String THUMB_DIR = ".phonesamba_thumbs";

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
            boolean video = isVideoName(name);
            if (!video && !isImageName(name)) {
                continue;
            }
            long size = file.length();
            long modified = file.lastModified();
            String thumbnailName = thumbnailName(name, size, modified);
            photos.add(new RemotePhotoItem(
                    name,
                    settings.fileUrl(name),
                    settings.childUrl(THUMB_DIR + "/" + thumbnailName),
                    size,
                    modified,
                    video
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

    private static String thumbnailName(String name, long size, long lastModifiedMillis) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        String payload = name + "|" + size + "|" + lastModifiedMillis;
        byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2 + 4);
        for (byte value : hash) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        builder.append(".jpg");
        return builder.toString();
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

    private static boolean isVideoName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4")
                || lower.endsWith(".m4v")
                || lower.endsWith(".mov")
                || lower.endsWith(".avi")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".3gp")
                || lower.endsWith(".3g2")
                || lower.endsWith(".wmv")
                || lower.endsWith(".mpg")
                || lower.endsWith(".mpeg")
                || lower.endsWith(".mts")
                || lower.endsWith(".m2ts");
    }
}
