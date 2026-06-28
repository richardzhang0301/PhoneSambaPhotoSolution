package com.diytools.phonesambaphoto;

import android.net.Uri;

final class PhotoItem {
    final long id;
    final Uri uri;
    final String name;
    final long size;
    final long dateModifiedSeconds;
    final long dateTakenMillis;
    boolean selected;
    boolean uploaded;

    PhotoItem(long id, Uri uri, String name, long size, long dateModifiedSeconds, long dateTakenMillis, boolean uploaded) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.dateModifiedSeconds = dateModifiedSeconds;
        this.dateTakenMillis = dateTakenMillis;
        this.uploaded = uploaded;
    }

    String mediaKey() {
        return id + ":" + size + ":" + dateModifiedSeconds;
    }
}
