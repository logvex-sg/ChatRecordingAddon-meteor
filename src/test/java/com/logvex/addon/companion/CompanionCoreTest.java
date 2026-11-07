package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionJson;
import com.logvex.addon.companion.core.CompanionRateLimiter;
import com.logvex.addon.companion.core.CompanionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The companion's small building blocks: the event bus, the rate limiter and the client-thread seam. */
class CompanionCoreTest {

    // ------------------------------------------------------------------ event bus

    @Test
    @DisplayName("subscribers receive published events and the ring is bounded")
    void publishAndBound() {
        CompanionEventBus bus = new CompanionEventBus(3);
        List<String> seen = new ArrayList<>();
        bus.subscribe(event -> {
            seen.add(event.type());
            return true;
        }, false);

        bus.publish("chat", "{}");
        bus.publish("bot", "{}");
        bus.publish("task", "{}");
        bus.publish("module", "{}");

        assertEquals(List.of("chat", "bot", "task", "module"), seen);
        assertEquals(3, bus.recent(10).size(), "the ring keeps only the newest events");
        assertEquals("bot", bus.recent(10).get(0).type());
    }

    @Test
    @DisplayName("a new subscriber is replayed the backlog in order")
    void backlogReplay() {
        CompanionEventBus bus = new CompanionEventBus(10);
        bus.publish("chat", "{\"n\":1}");
        bus.publish("chat", "{\"n\":2}");

        List<String> seen = new ArrayList<>();
        bus.subscribe(event -> {
            seen.add(event.json());
            return true;
        }, true);

        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), seen);
    }

    @Test
    @DisplayName("a subscriber that returns false is dropped, and one that throws is isolated")
    void brokenSubscriberIsolated() {
        CompanionEventBus bus = new CompanionEventBus(10);
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe(event -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, false);
        List<String> healthy = new ArrayList<>();
        bus.subscribe(event -> {
            healthy.add(event.type());
            return true;
        }, false);

        bus.publish("chat", "{}");
        bus.publish("chat", "{}");

        assertEquals(1, calls.get(), "a throwing subscriber is unsubscribed after the first failure");
        assertEquals(2, healthy.size(), "the healthy subscriber still receives every event");
    }

    @Test
    @DisplayName("event ids increase monotonically, so a client can resume from its last id")
    void idsAreMonotonic() {
        CompanionEventBus bus = new CompanionEventBus(5);
        long first = bus.publish("chat", "{}").id();
        long second = bus.publish("chat", "{}").id();
        assertTrue(second > first);
        assertEquals(second, bus.lastEventId());
    }

    // ------------------------------------------------------------------ rate limiter

    @Test
    @DisplayName("the rate limiter allows a burst then throttles")
    void rateLimiterThrottles() {
        CompanionRateLimiter limiter = new CompanionRateLimiter(60);
        int allowed = 0;
        for (int i = 0; i < 200; i++) {
            if (limiter.allow("1.2.3.4")) allowed++;
        }
        assertTrue(allowed >= 60, "a full bucket is allowed");
        assertTrue(allowed < 200, "a sustained flood is throttled");
    }

    @Test
    @DisplayName("rate limit buckets are per client and prunable")
    void rateLimiterPerClient() {
        CompanionRateLimiter limiter = new CompanionRateLimiter(2);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"), "the first client is exhausted");
        assertTrue(limiter.allow("b"), "a second client has its own bucket");
        assertEquals(2, limiter.trackedClients());
        limiter.prune();
        assertEquals(2, limiter.trackedClients(), "recent buckets are not pruned");
    }

    // ------------------------------------------------------------------ client thread seam

    @Test
    @DisplayName("the direct client thread runs the task inline and returns its value")
    void directThread() {
        CompanionClientThread direct = CompanionClientThread.direct();
        assertEquals("hello", direct.invoke(() -> "hello", 1000));
    }

    @Test
    @DisplayName("the client thread times out rather than hanging forever")
    void timeoutSurfaces() {
        CompanionClientThread stalled = (task, timeoutMs) -> {
            throw new CompanionClientThread.CompanionTimeoutException("timed out");
        };
        assertThrows(CompanionClientThread.CompanionTimeoutException.class,
            () -> stalled.invoke(() -> "never", 10));
    }

    @Test
    @DisplayName("the scheduler-backed client thread runs the task on the scheduler")
    void scheduledThread() throws Exception {
        List<Runnable> scheduled = new ArrayList<>();
        CompanionClientThread thread = CompanionClientThread.of(scheduled::add);
        // Run the submitted task on another thread, as a game tick loop would.
        Thread pump = new Thread(() -> {
            try {
                while (scheduled.isEmpty()) Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            scheduled.get(0).run();
        });
        pump.setDaemon(true);
        pump.start();

        assertEquals(7, (int) thread.invoke(() -> 7, 2000));
    }

    // ------------------------------------------------------------------ bundled app

    @Test
    @DisplayName("the bundled app is present, self-contained and talks to the documented endpoints")
    void appResourceIsSane() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/companion/app.html")) {
            assertNotNull(in, "companion/app.html must ship in the jar");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("/api/v1/snapshot"), "the app must call the snapshot endpoint");
            assertTrue(html.contains("/api/v1/events"), "the app must open the live event stream");
            assertTrue(html.contains("/api/v1/bot/"), "the app must expose the bot controls");
            assertTrue(html.contains("Authorization"), "the app must send the token");
            // Self-contained: no external scripts or stylesheets, so it works offline.
            assertFalse(html.contains("http://cdn."), "the app must not pull remote assets");
            assertFalse(html.contains("https://cdn."), "the app must not pull remote assets");
            assertTrue(html.contains("<script>"), "the app's script must be inline");
        }
    }

    @Test
    @DisplayName("a stalled publisher does not block on a subscriber")
    void subscriberNeverBlocksPublisher() throws Exception {
        CompanionEventBus bus = new CompanionEventBus(10);
        CountDownLatch release = new CountDownLatch(1);
        bus.subscribe(event -> {
            // A subscriber that takes a moment must not be able to stall the game thread's publish.
            release.countDown();
            return true;
        }, false);
        long before = System.nanoTime();
        bus.publish("chat", "{}");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue(release.await(1, TimeUnit.SECONDS));
        assertTrue(elapsedMs < 500, "publish must return promptly");
    }

    // ------------------------------------------------------------------ app contract

    @Test
    @DisplayName("the snapshot's JSON keys are exactly the ones the bundled app reads")
    void snapshotKeysMatchTheApp() {
        String json = CompanionJson.write(CompanionSnapshot.empty("node", "Log LogClientContext", "12.0.0"));
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        for (String key : List.of("node", "modules", "tasks", "players", "chat", "stats", "storage",
            "bot", "capabilities")) {
            assertTrue(root.has(key), "the app reads snapshot." + key);
        }
        com.google.gson.JsonObject node = root.getAsJsonObject("node");
        for (String key : List.of("id", "name", "addonVersion", "minecraftVersion", "playerName",
            "serverAddress", "inWorld", "singleplayer", "gameMode", "uptimeMs", "nowEpochMs")) {
            assertTrue(node.has(key), "the app reads node." + key);
        }
        com.google.gson.JsonObject stats = root.getAsJsonObject("stats");
        for (String key : List.of("messages", "players", "joins", "leaves", "deaths",
            "sessionStartEpochMs")) {
            assertTrue(stats.has(key), "the app reads stats." + key);
        }
        com.google.gson.JsonObject storage = root.getAsJsonObject("storage");
        for (String key : List.of("segments", "bytes", "sessions", "oldestEpochMs", "newestEpochMs")) {
            assertTrue(storage.has(key), "the app reads storage." + key);
        }
        com.google.gson.JsonObject bot = root.getAsJsonObject("bot");
        for (String key : List.of("state", "activeTask", "queued", "completed", "failed", "paused",
            "recentEvents")) {
            assertTrue(bot.has(key), "the app reads bot." + key);
        }
    }
}
