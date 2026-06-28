package com.diytools.phonesambaphoto;

final class RemotePhotoItem {
    final String name;
    final String url;
    final String thumbnailUrl;
    final long size;
    final long lastModifiedMillis;

    RemotePhotoItem(String name, String url, String thumbnailUrl, long size, long lastModifiedMillis) {
        this.name = name;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    String cacheKey() {
        return url + "|" + thumbnailUrl + "|" + size + "|" + lastModifiedMillis;
    }
}
