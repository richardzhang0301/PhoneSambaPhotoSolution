package com.diytools.phonesambaphoto;

import android.content.ContentResolver;
import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
                listener.onProgress(summary.totalDone(), total, "Uploading " + item.name);
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
                listener.onProgress(summary.totalDone(), total, progressText(summary, total));
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
        return UploadState.UPLOADED;
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

    private static String progressText(Summary summary, int total) {
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
