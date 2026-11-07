package com.logvex.addon.update.core;

/**
 * One file attached to a GitHub release.
 *
 * @param name               the file name, e.g. {@code log-client-13.1.0.jar}
 * @param browserDownloadUrl the download URL
 * @param size               the size in bytes as reported by GitHub, or -1 when unknown
 * @param sha256             the SHA-256 digest when GitHub exposes it, otherwise empty
 */
public record ReleaseAsset(String name, String browserDownloadUrl, long size, String sha256) {
    public ReleaseAsset {
        name = name == null ? "" : name;
        browserDownloadUrl = browserDownloadUrl == null ? "" : browserDownloadUrl;
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** True when a checksum is available to verify the download against. */
    public boolean hasChecksum() {
        return !sha256.isEmpty();
    }

    /** A compact "name (size)" label for the GUI. */
    public String label() {
        if (size <= 0) return name;
        return name + " (" + com.logvex.addon.common.Messages.bytes(size) + ")";
    }
}
