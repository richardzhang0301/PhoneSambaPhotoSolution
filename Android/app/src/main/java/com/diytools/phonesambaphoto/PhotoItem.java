package com.diytools.phonesambaphoto;

import android.net.Uri;

final class PhotoItem {
    final long id;
    final Uri uri;
    final String name;
    final long size;
    final long dateModifiedSeconds;
    final long dateTakenMillis;
    final boolean video;
    boolean selected;
    boolean uploaded;

    PhotoItem(long id, Uri uri, String name, long size, long dateModifiedSeconds, long dateTakenMillis, boolean uploaded, boolean video) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.dateModifiedSeconds = dateModifiedSeconds;
        this.dateTakenMillis = dateTakenMillis;
        this.uploaded = uploaded;
        this.video = video;
    }

    String mediaKey() {
        return (video ? "video" : "image") + ":" + id + ":" + size + ":" + dateModifiedSeconds;
    }

    long sortTimeMillis() {
        if (dateTakenMillis > 0L) {
            return dateTakenMillis;
        }
        return dateModifiedSeconds * 1000L;
    }
}