package com.diytools.phonesambaphoto;

final class RemotePhotoItem {
    final String name;
    final String url;
    final String thumbnailUrl;
    final long size;
    final long lastModifiedMillis;
    final boolean video;

    RemotePhotoItem(String name, String url, String thumbnailUrl, long size, long lastModifiedMillis, boolean video) {
        this.name = name;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
        this.video = video;
    }

    String cacheKey() {
        return url + "|" + thumbnailUrl + "|" + size + "|" + lastModifiedMillis + "|" + video;
    }
}
