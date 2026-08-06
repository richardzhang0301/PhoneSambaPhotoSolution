package com.diytools.phonesambaphoto;

import android.text.TextUtils;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

final class SambaThumbnailStore {
    private SambaThumbnailStore() {
    }

    static void deleteIfPresent(CIFSContext context, String thumbnailUrl) throws Exception {
        if (TextUtils.isEmpty(thumbnailUrl)) {
            return;
        }
        SmbFile thumbnail = new SmbFile(thumbnailUrl, context);
        if (thumbnail.exists()) {
            thumbnail.delete();
        }
    }
}
