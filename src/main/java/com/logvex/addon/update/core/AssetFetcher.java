package com.logvex.addon.update.core;

import java.nio.file.Path;

/**
 * Fetches a release asset to disk.
 *
 * <p>Behind an interface so the {@link UpdateEngine} can be tested with a fake that never touches the
 * network, and so a different transport could be swapped in without changing the engine.
 */
public interface AssetFetcher {
    /** Receives progress as a fraction in {@code [0,1]} (or -1 when the total size is unknown). */
    interface ProgressListener {
        void onProgress(double fraction, long bytesRead, long totalBytes);
    }

    /** Downloads {@code asset} into {@code destinationDirectory}, returning the outcome. */
    DownloadResult download(ReleaseAsset asset, Path destinationDirectory, ProgressListener listener);
}
