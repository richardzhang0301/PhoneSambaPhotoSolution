package com.diytools.phonesambaphoto;

final class RemotePhotoItem {
    final String name;
    final String url;
    final long size;
    final long lastModifiedMillis;

    RemotePhotoItem(String name, String url, long size, long lastModifiedMillis) {
        this.name = name;
        this.url = url;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    String cacheKey() {
        return url + "|" + size + "|" + lastModifiedMillis;
    }
}
