package com.diytools.phonesambaphoto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class SambaThumbnailSpec {
    static final String DIR = ".phonesamba_thumbs";
    static final int SIZE_PX = 384;
    static final int QUALITY = 82;

    private SambaThumbnailSpec() {
    }

    static String thumbnailName(String name, long size, long lastModifiedMillis) throws Exception {
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
}
