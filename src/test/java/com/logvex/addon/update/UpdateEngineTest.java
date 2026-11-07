package com.logvex.addon.update;

import com.logvex.addon.update.core.AssetFetcher;
import com.logvex.addon.update.core.DownloadResult;
import com.logvex.addon.update.core.ReleaseAsset;
import com.logvex.addon.update.core.ReleaseInfo;
import com.logvex.addon.update.core.ReleaseSource;
import com.logvex.addon.update.core.UpdateChannel;
import com.logvex.addon.update.core.UpdateEngine;
import com.logvex.addon.update.core.UpdateSnapshot;
import com.logvex.addon.update.core.UpdateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The update engine driven by fakes, so no test ever touches the network. */
class UpdateEngineTest {

    private static ReleaseInfo release(String tag, boolean preRelease) {
        return new ReleaseInfo(tag, "Log Client " + tag, "notes", preRelease, "https://x", "2026-11-01",
            List.of(new ReleaseAsset("log-client.jar", "https://x/a.jar", 2048, "abc")));
    }

    private static final class FakeSource implements ReleaseSource {
        final List<ReleaseInfo> releases;
        RuntimeException failure;

        FakeSource(List<ReleaseInfo> releases) {
            this.releases = releases;
        }

        @Override
        public List<ReleaseInfo> fetchReleases(boolean includePreReleases) {
            if (failure != null) throw failure;
            return releases;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    private static final class FakeFetcher implements AssetFetcher {
        boolean fail;
        Path written;

        @Override
        public DownloadResult download(ReleaseAsset asset, Path directory, ProgressListener listener) {
            if (fail) return DownloadResult.failed("network down");
            try {
                Files.createDirectories(directory);
                written = directory.resolve("log-client.jar");
                Files.writeString(written, "jar-bytes");
                if (listener != null) listener.onProgress(0.5, 4, 8);
                if (listener != null) listener.onProgress(1.0, 8, 8);
                return DownloadResult.ok(written, "deadbeef", 8);
            } catch (Exception e) {
                return DownloadResult.failed(e.getMessage());
            }
        }
    }

    private static UpdateEngine engine(ReleaseSource source, AssetFetcher fetcher, Path dir) {
        return new UpdateEngine(source, fetcher, dir, "13.0.0", UpdateChannel.STABLE);
    }

    @Test
    @DisplayName("a newer release is reported as available with its metadata")
    void findsUpdate() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        UpdateEngine engine = engine(new FakeSource(List.of(release("v13.1.0", false))), new FakeFetcher(), dir);
        try {
            UpdateSnapshot snapshot = engine.checkBlocking();
            assertEquals(UpdateState.AVAILABLE, snapshot.state());
            assertEquals("13.1.0", snapshot.latestVersion());
            assertTrue(snapshot.hasUpdate());
            assertTrue(snapshot.checksumAvailable());
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("no newer release reports up to date")
    void upToDate() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        UpdateEngine engine = engine(new FakeSource(List.of(release("v12.9.0", false))), new FakeFetcher(), dir);
        try {
            assertEquals(UpdateState.UP_TO_DATE, engine.checkBlocking().state());
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a source failure is reported as FAILED with a message, never a throw")
    void sourceFailure() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        FakeSource source = new FakeSource(List.of());
        source.failure = new IllegalStateException("rate limited");
        UpdateEngine engine = engine(source, new FakeFetcher(), dir);
        try {
            UpdateSnapshot snapshot = engine.checkBlocking();
            assertEquals(UpdateState.FAILED, snapshot.state());
            assertTrue(snapshot.error().contains("rate limited"));
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a successful download moves the engine to READY and keeps the file")
    void downloadsAndBecomesReady() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        FakeFetcher fetcher = new FakeFetcher();
        UpdateEngine engine = engine(new FakeSource(List.of(release("v13.1.0", false))), fetcher, dir);
        try {
            engine.checkBlocking();
            engine.downloadAsync();
            engine.awaitIdle();
            UpdateSnapshot snapshot = engine.snapshot();
            assertEquals(UpdateState.READY, snapshot.state());
            assertTrue(snapshot.isReady());
            assertNotNull(snapshot.downloadedFilePath());
            assertTrue(Files.exists(fetcher.written));
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a failed download is reported and leaves no READY state")
    void downloadFailure() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.fail = true;
        UpdateEngine engine = engine(new FakeSource(List.of(release("v13.1.0", false))), fetcher, dir);
        try {
            engine.checkBlocking();
            engine.downloadAsync();
            engine.awaitIdle();
            assertEquals(UpdateState.FAILED, engine.snapshot().state());
            assertTrue(engine.snapshot().error().contains("network down"));
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a check already in flight is not started twice")
    void noDuplicateChecks() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReleaseSource slow = new ReleaseSource() {
            @Override
            public List<ReleaseInfo> fetchReleases(boolean includePreReleases) {
                calls.incrementAndGet();
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return List.of(release("v13.1.0", false));
            }

            @Override
            public String describe() {
                return "slow";
            }
        };
        UpdateEngine engine = engine(slow, new FakeFetcher(), dir);
        try {
            engine.checkAsync();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            engine.checkAsync();
            engine.checkAsync();
            release.countDown();
            engine.awaitIdle();
            assertEquals(1, calls.get(), "a second check while one is in flight must be ignored");
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the listener is told about every state change")
    void listenerNotified() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        UpdateEngine engine = engine(new FakeSource(List.of(release("v13.1.0", false))), new FakeFetcher(), dir);
        AtomicInteger changes = new AtomicInteger();
        engine.setListener(snapshot -> changes.incrementAndGet());
        try {
            engine.checkBlocking();
            assertTrue(changes.get() >= 2, "checking then available should each notify");
        } finally {
            engine.shutdown();
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("shutdown is idempotent and stops accepting work")
    void shutdownIsSafe() throws Exception {
        Path dir = Files.createTempDirectory("logclient-update");
        UpdateEngine engine = engine(new FakeSource(List.of()), new FakeFetcher(), dir);
        engine.shutdown();
        engine.shutdown();
        engine.checkAsync();
        assertFalse(engine.snapshot().state() == UpdateState.CHECKING);
        deleteRecursively(dir);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        }
    }
}
