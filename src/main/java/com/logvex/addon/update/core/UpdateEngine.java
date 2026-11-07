package com.logvex.addon.update.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The update engine: picks the right release, downloads and verifies it, and keeps a snapshot.
 *
 * <p>It is Minecraft-free and network-free at heart: a {@link ReleaseSource} supplies releases and a
 * {@link Downloader} fetches the file, both of which are replaced by fakes in tests. All I/O runs on
 * a single daemon worker, so a check or a download never touches the game thread; the snapshot is
 * volatile and can be read from any thread.
 *
 * <p>It never installs anything. A verified download is left on disk and the user is told where it
 * is, which is what "never execute downloaded code automatically" requires.
 */
public final class UpdateEngine {
    /** The callback the GUI/HUD use to notice a change without polling. */
    public interface Listener {
        void onUpdateChanged(UpdateSnapshot snapshot);
    }

    private final ReleaseSource source;
    private final AssetFetcher downloader;
    private final Path downloadDirectory;
    private final String installedVersion;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-update");
        thread.setDaemon(true);
        return thread;
    });

    private volatile UpdateSnapshot snapshot;
    private volatile Listener listener;
    private volatile boolean closed;
    /** The release the last check selected, so a download uses the real asset URL. */
    private volatile ReleaseInfo pendingRelease;

    public UpdateEngine(ReleaseSource source, AssetFetcher downloader, Path downloadDirectory,
                        String installedVersion, UpdateChannel channel) {
        this.source = source;
        this.downloader = downloader;
        this.downloadDirectory = downloadDirectory;
        this.installedVersion = installedVersion;
        this.snapshot = UpdateSnapshot.initial(installedVersion, channel);
    }

    public UpdateSnapshot snapshot() {
        return snapshot;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Switches the channel; the next check honours it. */
    public void setChannel(UpdateChannel channel) {
        publish(copy(snapshot).channel(channel).build());
    }

    /** Runs a check in the background. A check already in flight is not started twice. */
    public void checkAsync() {
        if (closed) return;
        UpdateState current = snapshot.state();
        if (current == UpdateState.CHECKING || current == UpdateState.DOWNLOADING) return;
        publish(copy(snapshot).state(UpdateState.CHECKING).error("").build());
        worker.submit(this::runCheck);
    }

    /** Downloads the update the last check found. Does nothing when none is available. */
    public void downloadAsync() {
        if (closed) return;
        UpdateSnapshot current = snapshot;
        if (!current.hasUpdate() || current.state() == UpdateState.DOWNLOADING
            || current.state() == UpdateState.READY) {
            return;
        }
        publish(copy(current).state(UpdateState.DOWNLOADING).downloadProgress(0).error("").build());
        worker.submit(() -> runDownload(current));
    }

    /** Blocks until queued work finishes. Used only by tests and shutdown. */
    public void awaitIdle() {
        try {
            worker.submit(() -> {
            }).get();
        } catch (Exception ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        closed = true;
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ work

    private void runCheck() {
        try {
            List<ReleaseInfo> releases = source.fetchReleases(snapshot.channel() == UpdateChannel.BETA);
            ReleaseInfo best = selectBest(releases, snapshot.channel());
            long now = System.currentTimeMillis();

            if (best == null || !best.isNewerThan(Version.parse(installedVersion))) {
                publish(copy(snapshot).state(UpdateState.UP_TO_DATE).lastCheckedEpochMs(now)
                    .latestVersion(installedVersion).error("").build());
                return;
            }

            ReleaseAsset jar = best.primaryJar();
            pendingRelease = best;
            publish(new UpdateSnapshot(
                UpdateState.AVAILABLE,
                snapshot.channel(),
                installedVersion,
                best.version().toString(),
                best.name(),
                best.body(),
                best.htmlUrl(),
                best.publishedAt(),
                best.preRelease(),
                jar == null ? 0 : jar.size(),
                jar == null ? "" : jar.name(),
                jar != null && jar.hasChecksum(),
                0,
                "",
                jar == null ? "This release has no jar asset to download." : "",
                now));
        } catch (Exception e) {
            publish(copy(snapshot).state(UpdateState.FAILED)
                .error(describe(e))
                .lastCheckedEpochMs(System.currentTimeMillis())
                .build());
        }
    }

    private void runDownload(UpdateSnapshot found) {
        ReleaseInfo release = pendingRelease;
        ReleaseAsset asset = release == null ? null : release.primaryJar();
        if (asset == null) {
            publish(copy(found).state(UpdateState.FAILED)
                .error("This release has no jar asset to download.").build());
            return;
        }

        DownloadResult result = downloader.download(asset, downloadDirectory, (fraction, read, total) ->
            publish(copy(snapshot).state(UpdateState.DOWNLOADING).downloadProgress(fraction < 0 ? 0 : fraction).build()));

        if (!result.success()) {
            publish(copy(found).state(UpdateState.FAILED).error(result.error()).build());
            return;
        }

        publish(copy(found).state(UpdateState.READY)
            .downloadProgress(1)
            .downloadedPath(result.file() == null ? "" : result.file().toString())
            .assetSize(result.bytes())
            .error("")
            .build());
    }

    /**
     * Picks the newest acceptable release. A stable channel ignores pre-releases; a beta channel
     * accepts both, but a newer stable always wins over an older beta.
     */
    public static ReleaseInfo selectBest(List<ReleaseInfo> releases, UpdateChannel channel) {
        ReleaseInfo best = null;
        for (ReleaseInfo release : releases) {
            if (release == null) continue;
            if (!channel.accepts(release.preRelease())) continue;
            if (best == null || release.version().isNewerThan(best.version())) best = release;
        }
        return best;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "Update check failed (" + e.getClass().getSimpleName() + ").";
        return "Update check failed: " + message;
    }

    private void publish(UpdateSnapshot next) {
        this.snapshot = next;
        Listener l = this.listener;
        if (l != null) {
            try {
                l.onUpdateChanged(next);
            } catch (RuntimeException ignored) {
                // A misbehaving listener must never break the worker.
            }
        }
    }

    private static SnapshotBuilder copy(UpdateSnapshot s) {
        return new SnapshotBuilder(s);
    }

    /** A small mutable builder so a new snapshot is built without a wall of positional arguments. */
    private static final class SnapshotBuilder {
        private UpdateState state;
        private UpdateChannel channel;
        private String installedVersion;
        private String latestVersion;
        private String releaseName;
        private String releaseNotes;
        private String releaseUrl;
        private String publishedAt;
        private boolean preRelease;
        private long assetSize;
        private String assetName;
        private boolean checksumAvailable;
        private double downloadProgress;
        private String downloadedPath;
        private String error;
        private long lastCheckedEpochMs;

        SnapshotBuilder(UpdateSnapshot s) {
            this.state = s.state();
            this.channel = s.channel();
            this.installedVersion = s.installedVersion();
            this.latestVersion = s.latestVersion();
            this.releaseName = s.releaseName();
            this.releaseNotes = s.releaseNotes();
            this.releaseUrl = s.releaseUrl();
            this.publishedAt = s.publishedAt();
            this.preRelease = s.preRelease();
            this.assetSize = s.assetSize();
            this.assetName = s.assetName();
            this.checksumAvailable = s.checksumAvailable();
            this.downloadProgress = s.downloadProgress();
            this.downloadedPath = s.downloadedPath();
            this.error = s.error();
            this.lastCheckedEpochMs = s.lastCheckedEpochMs();
        }

        SnapshotBuilder state(UpdateState v) { this.state = v; return this; }
        SnapshotBuilder channel(UpdateChannel v) { this.channel = v; return this; }
        SnapshotBuilder latestVersion(String v) { this.latestVersion = v; return this; }
        SnapshotBuilder error(String v) { this.error = v; return this; }
        SnapshotBuilder downloadProgress(double v) { this.downloadProgress = v; return this; }
        SnapshotBuilder downloadedPath(String v) { this.downloadedPath = v; return this; }
        SnapshotBuilder assetSize(long v) { this.assetSize = v; return this; }
        SnapshotBuilder lastCheckedEpochMs(long v) { this.lastCheckedEpochMs = v; return this; }

        UpdateSnapshot build() {
            return new UpdateSnapshot(state, channel, installedVersion, latestVersion, releaseName,
                releaseNotes, releaseUrl, publishedAt, preRelease, assetSize, assetName,
                checksumAvailable, downloadProgress, downloadedPath, error, lastCheckedEpochMs);
        }
    }

    /** Convenience for callers that just want to run a check and read the result synchronously. */
    public UpdateSnapshot checkBlocking() {
        publish(copy(snapshot).state(UpdateState.CHECKING).error("").build());
        runCheck();
        return snapshot;
    }
}
