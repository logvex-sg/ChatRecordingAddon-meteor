package com.logvex.addon.update.core;

/** The result of a download: the saved file, or a reason it failed. */
public record DownloadResult(boolean success, java.nio.file.Path file, String sha256,
                             long bytes, String error) {
    public static DownloadResult ok(java.nio.file.Path file, String sha256, long bytes) {
        return new DownloadResult(true, file, sha256, bytes, null);
    }

    public static DownloadResult failed(String error) {
        return new DownloadResult(false, null, "", 0, error == null ? "download failed" : error);
    }
}
