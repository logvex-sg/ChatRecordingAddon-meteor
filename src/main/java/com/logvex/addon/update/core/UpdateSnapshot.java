package com.logvex.addon.update.core;

import java.nio.file.Path;
import java.time.Instant;

/**
 * An immutable snapshot of the update system, safe to read from the GUI, the HUD and the web console.
 *
 * <p>Every field comes from a real check or download; a value that is not yet known is null or empty
 * rather than a placeholder, so the interface can honestly show "not checked" instead of a fake
 * number.
 */
public record UpdateSnapshot(
    UpdateState state,
    UpdateChannel channel,
    String installedVersion,
    String latestVersion,
    String releaseName,
    String releaseNotes,
    String releaseUrl,
    String publishedAt,
    boolean preRelease,
    long assetSize,
    String assetName,
    boolean checksumAvailable,
    double downloadProgress,
    String downloadedPath,
    String error,
    long lastCheckedEpochMs
) {
    public static UpdateSnapshot initial(String installed, UpdateChannel channel) {
        return new UpdateSnapshot(UpdateState.IDLE, channel, installed, "", "", "", "", "", false,
            0, "", false, 0, "", "", 0);
    }

    /** True when an update is available and not yet downloaded. */
    public boolean hasUpdate() {
        return state == UpdateState.AVAILABLE || state == UpdateState.DOWNLOADING
            || state == UpdateState.READY;
    }

    public boolean isReady() {
        return state == UpdateState.READY;
    }

    /** A short, human status line for the GUI and chat. */
    public String statusLine() {
        return switch (state) {
            case IDLE -> "Not checked yet.";
            case CHECKING -> "Checking for updates...";
            case UP_TO_DATE -> "You are on the latest version (" + installedVersion + ").";
            case AVAILABLE -> "Update available: " + latestVersion + " (you have " + installedVersion + ").";
            case DOWNLOADING -> "Downloading " + latestVersion + " - "
                + Math.round(downloadProgress * 100) + "%.";
            case READY -> "Update " + latestVersion + " downloaded and verified; ready to install.";
            case FAILED -> error == null || error.isBlank() ? "Update check failed." : error;
        };
    }

    /** The last check time as a readable string, or "never". */
    public String lastCheckedText() {
        if (lastCheckedEpochMs <= 0) return "never";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(lastCheckedEpochMs));
    }

    public Path downloadedFilePath() {
        return downloadedPath == null || downloadedPath.isBlank() ? null : Path.of(downloadedPath);
    }
}
