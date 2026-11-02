package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.LogSegmentMeta;
import com.logvex.addon.chatlog.core.LogStore;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStoreTest {

    private static ChatEntry entry(long ts, String sender, String content) {
        return new ChatEntry(ts, MessageType.PUBLIC_CHAT, sender, content, "mc.example.com", "s1");
    }

    private LogStore store(Path root) throws IOException {
        LogStore store = new LogStore(root);
        store.setFlushThreshold(5);
        store.setCompressionLevel(6);
        store.load();
        return store;
    }

    @Test
    void appendFlushAndReadBack(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        for (int i = 0; i < 12; i++) store.append(entry(1000 + i, "Notch", "message " + i));
        store.flush();

        assertEquals(12, store.totalEntries());
        assertTrue(store.segmentCount() >= 2);

        List<ChatEntry> all = new ArrayList<>();
        store.forEach(e -> {
            all.add(e);
            return true;
        });
        assertEquals(12, all.size());
        assertEquals("message 0", all.get(0).content());
        assertEquals("message 11", all.get(11).content());
    }

    @Test
    void indexSurvivesReload(@TempDir Path root) throws IOException {
        LogStore first = store(root);
        for (int i = 0; i < 12; i++) first.append(entry(1000 + i, "Notch", "message " + i));
        first.flush();

        LogStore second = store(root);
        assertEquals(12, second.totalEntries());
        assertEquals(first.segmentCount(), second.segmentCount());
    }

    @Test
    void indexIsRebuiltWhenMissing(@TempDir Path root) throws IOException {
        LogStore first = store(root);
        for (int i = 0; i < 6; i++) first.append(entry(1000 + i, "Notch", "message " + i));
        first.flush();

        Files.deleteIfExists(root.resolve("index.json"));

        LogStore rebuilt = store(root);
        assertEquals(6, rebuilt.totalEntries());
    }

    @Test
    void searchFiltersByTextPlayerAndType(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "selling diamonds"));
        store.append(entry(2000, "Alex", "buying diamonds"));
        store.append(new ChatEntry(3000, MessageType.JOIN, "Steve", "Steve joined the game", "s", "s1"));
        store.flush();

        assertEquals(2, store.query(LogQuery.builder().text("diamond").build(), 100).size());
        assertEquals(1, store.query(LogQuery.builder().player("alex").build(), 100).size());
        assertEquals(1, store.query(LogQuery.builder().types(java.util.EnumSet.of(MessageType.JOIN)).build(), 100).size());
        assertEquals(3, store.query(LogQuery.all(), 100).size());
    }

    @Test
    void queryReturnsNewestFirst(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "first"));
        store.append(entry(2000, "Notch", "second"));
        store.append(entry(3000, "Notch", "third"));
        store.flush();

        List<ChatEntry> results = store.query(LogQuery.all(), 10);
        assertEquals("third", results.get(0).content());
        assertEquals("first", results.get(2).content());
    }

    @Test
    void queryHonoursLimit(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        for (int i = 0; i < 20; i++) store.append(entry(1000 + i, "Notch", "m" + i));
        store.flush();
        assertEquals(5, store.query(LogQuery.all(), 5).size());
    }

    @Test
    void cleanupByCountRemovesOldest(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        for (int i = 0; i < 30; i++) store.append(entry(1000 + i * 1000L, "Notch", "m" + i));
        store.flush();

        int before = store.segmentCount();
        assertTrue(before > 1);
        store.cleanupByCount(1);
        assertEquals(1, store.segmentCount());
    }

    @Test
    void cleanupNeverEmptiesTheStore(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "only"));
        store.flush();

        store.cleanupByCount(0);
        store.cleanupBySize(0);
        store.cleanupByAge(0, System.currentTimeMillis());
        assertEquals(1, store.segmentCount());
        assertEquals(1, store.totalEntries());
    }

    @Test
    void cleanupByAgeRemovesExpired(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "old"));
        store.flush();
        store.append(entry(10_000_000_000L, "Notch", "new"));
        store.flush();

        long now = 10_000_000_000L;
        store.cleanupByAge(1000, now);
        assertEquals(1, store.segmentCount());
        assertEquals("new", store.query(LogQuery.all(), 10).get(0).content());
    }

    @Test
    void deleteSegmentRemovesFile(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "m"));
        store.flush();

        LogSegmentMeta meta = store.segments().get(0);
        assertTrue(Files.exists(root.resolve(meta.fileName())));
        assertTrue(store.deleteSegment(meta.fileName()));
        assertFalse(Files.exists(root.resolve(meta.fileName())));
        assertEquals(0, store.segmentCount());
    }

    @Test
    void corruptedSegmentDoesNotBreakTheWholeQuery(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        store.append(entry(1000, "Notch", "good one"));
        store.flush();
        store.append(entry(2000, "Notch", "good two"));
        store.flush();

        LogSegmentMeta oldest = store.segments().get(0);
        byte[] data = Files.readAllBytes(root.resolve(oldest.fileName()));
        data[data.length - 2] ^= 0x33;
        Files.write(root.resolve(oldest.fileName()), data);

        List<ChatEntry> results = store.query(LogQuery.all(), 10);
        assertTrue(results.size() >= 1, "the readable segment should still be returned");
    }

    @Test
    void deleteAllClearsEverything(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        for (int i = 0; i < 10; i++) store.append(entry(1000 + i, "Notch", "m" + i));
        store.flush();

        assertTrue(store.deleteAll() > 0);
        assertEquals(0, store.segmentCount());
        assertEquals(0, store.totalEntries());
    }

    @Test
    void compressedSizeIsSmallerThanUncompressed(@TempDir Path root) throws IOException {
        LogStore store = store(root);
        for (int i = 0; i < 200; i++) store.append(entry(1000 + i, "Notch", "a fairly repetitive chat message body"));
        store.flush();

        assertTrue(store.totalCompressedBytes() < store.totalUncompressedBytes());
    }
}
