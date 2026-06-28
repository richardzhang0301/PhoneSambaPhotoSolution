package com.diytools.phonesambaphoto;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

final class SambaSettings {
    private static final String PREFS = "samba_destination";
    private static final String HOST = "host";
    private static final String SHARE = "share";
    private static final String FOLDER = "folder";
    private static final String DOMAIN = "domain";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";

    final String host;
    final String share;
    final String folder;
    final String domain;
    final String username;
    final String password;

    SambaSettings(String host, String share, String folder, String domain, String username, String password) {
        this.host = cleanHost(host);
        this.share = cleanText(share);
        this.folder = cleanFolder(folder);
        this.domain = cleanText(domain);
        this.username = cleanText(username);
        this.password = password == null ? "" : password;
    }

    static SambaSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new SambaSettings(
                prefs.getString(HOST, ""),
                prefs.getString(SHARE, ""),
                prefs.getString(FOLDER, ""),
                prefs.getString(DOMAIN, ""),
                prefs.getString(USERNAME, ""),
                prefs.getString(PASSWORD, "")
        );
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(HOST, host)
                .putString(SHARE, share)
                .putString(FOLDER, folder)
                .putString(DOMAIN, domain)
                .putString(USERNAME, username)
                .putString(PASSWORD, password)
                .apply();
    }

    boolean isConfigured() {
        return !TextUtils.isEmpty(host) && !TextUtils.isEmpty(share);
    }

    String displayName() {
        if (!isConfigured()) {
            return "No Samba folder set";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(host).append("/").append(share);
        if (!TextUtils.isEmpty(folder)) {
            builder.append("/").append(folder);
        }
        return builder.toString();
    }

    String identityKey() {
        return (host + "|" + share + "|" + folder + "|" + domain + "|" + username).toLowerCase();
    }

    String directoryUrl() {
        StringBuilder builder = rootUrlBuilder();
        appendFolder(builder);
        if (builder.charAt(builder.length() - 1) != '/') {
            builder.append("/");
        }
        return builder.toString();
    }

    String fileUrl(String fileName) {
        return childUrl(fileName);
    }

    String childUrl(String relativePath) {
        StringBuilder builder = rootUrlBuilder();
        appendFolder(builder);
        appendRelativePath(builder, relativePath);
        return builder.toString();
    }

    private StringBuilder rootUrlBuilder() {
        StringBuilder builder = new StringBuilder("smb://");
        builder.append(host).append("/").append(encodeSegment(share)).append("/");
        return builder;
    }

    private void appendFolder(StringBuilder builder) {
        appendRelativePath(builder, folder);
    }

    private void appendRelativePath(StringBuilder builder, String relativePath) {
        String cleanPath = cleanFolder(relativePath);
        if (TextUtils.isEmpty(cleanPath)) {
            return;
        }
        String[] parts = cleanPath.split("/");
        for (int index = 0; index < parts.length; index++) {
            String cleanPart = cleanText(parts[index]);
            if (TextUtils.isEmpty(cleanPart)) {
                continue;
            }
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '/') {
                builder.append("/");
            }
            builder.append(encodeSegment(cleanPart));
        }
        if (relativePath != null && relativePath.endsWith("/") && builder.charAt(builder.length() - 1) != '/') {
            builder.append("/");
        }
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanHost(String value) {
        String clean = cleanText(value);
        if (clean.startsWith("smb://")) {
            clean = clean.substring(6);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        int slash = clean.indexOf('/');
        return slash >= 0 ? clean.substring(0, slash) : clean;
    }

    private static String cleanFolder(String value) {
        String clean = cleanText(value).replace("\\", "/");
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static String encodeSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ignored) {
            return value;
        }
    }
}



