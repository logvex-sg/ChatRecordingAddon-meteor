package com.logvex.addon.world;

import com.logvex.addon.world.core.CountIndex;
import com.logvex.addon.world.core.DiscoveryLog;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.core.VisitedChunks;
import com.logvex.addon.world.core.WorldDatabase;
import com.logvex.addon.world.core.WorldFormat;
import com.logvex.addon.world.core.WorldIntelligence;
import com.logvex.addon.world.core.WorldPoint;
import com.logvex.addon.world.core.WorldProfile;
import com.logvex.addon.world.core.WorldStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Minecraft-free world-intelligence core: dedupe, bounds, partitioning, persistence, search. */
class WorldIntelligenceTest {

    @Test
    @DisplayName("recording the same coordinate twice updates one entry and counts visits")
    void sameCoordinateUpdatesOneEntry() {
        WorldDatabase db = new WorldDatabase();
        WorldPoint first = db.record("minecraft:overworld", 10, 64, 10, "Base", LocationCategory.BASE, 100);
        WorldPoint again = db.record("minecraft:overworld", 10, 64, 10, "Base", LocationCategory.BASE, 200);

        assertSame(first, again, "the same coordinate must be one entry");
        assertEquals(1, db.size());
        assertEquals(2, first.visits());
        assertEquals(100, first.firstSeen());
        assertEquals(200, first.lastSeen());
    }

    @Test
    @DisplayName("a nearby same-named point merges instead of duplicating")
    void nearbySameNameMerges() {
        WorldDatabase db = new WorldDatabase();
        db.record("minecraft:overworld", 0, 64, 0, "Village", LocationCategory.STRUCTURE, 1);
        db.record("minecraft:overworld", 2, 64, 1, "Village", LocationCategory.STRUCTURE, 2);

        assertEquals(1, db.size(), "a point within the dedupe radius with the same name merges");
        assertEquals(2, db.all().iterator().next().visits());
    }

    @Test
    @DisplayName("a far point with the same name stays separate")
    void farPointStaysSeparate() {
        WorldDatabase db = new WorldDatabase();
        db.record("minecraft:overworld", 0, 64, 0, "Village", LocationCategory.STRUCTURE, 1);
        db.record("minecraft:overworld", 500, 64, 500, "Village", LocationCategory.STRUCTURE, 2);

        assertEquals(2, db.size());
    }

    @Test
    @DisplayName("the database is bounded and evicts the least recently seen")
    void databaseIsBounded() {
        WorldDatabase db = new WorldDatabase();
        for (int i = 0; i < WorldDatabase.MAX_POINTS + 10; i++) {
            db.record("d", i * 100, 64, 0, "", LocationCategory.OTHER, i + 1);
        }
        assertEquals(WorldDatabase.MAX_POINTS, db.size());
    }

    @Test
    @DisplayName("search matches name, category and note, and filters by category")
    void searchMatchesAndFilters() {
        WorldDatabase db = new WorldDatabase();
        db.record("d", 0, 64, 0, "Home Base", LocationCategory.BASE, 1);
        db.record("d", 100, 64, 0, "Village", LocationCategory.STRUCTURE, 2);
        db.record("d", 200, 64, 0, "Mine", LocationCategory.RESOURCE, 3).note("diamonds here");

        assertEquals(1, db.search("home", null, null).size());
        assertEquals(1, db.search("diamond", null, null).size(), "note is searchable");
        assertEquals(1, db.search("", LocationCategory.BASE, null).size());
        assertEquals(1, db.search("", LocationCategory.STRUCTURE, null).size());
        assertEquals(3, db.search("", null, null).size());
    }

    @Test
    @DisplayName("nearest returns points of the same dimension, closest first")
    void nearestIsClosestFirst() {
        WorldDatabase db = new WorldDatabase();
        db.record("d", 100, 64, 0, "far", LocationCategory.OTHER, 1);
        db.record("d", 10, 64, 0, "near", LocationCategory.OTHER, 2);
        db.record("other", 0, 64, 0, "elsewhere", LocationCategory.OTHER, 3);

        List<WorldPoint> nearest = db.nearest("d", 0, 64, 0, 5);
        assertEquals(2, nearest.size(), "only the same dimension is considered");
        assertEquals("near", nearest.get(0).name());
    }

    @Test
    @DisplayName("profiles keep unrelated worlds apart")
    void profilesArePartitioned() {
        WorldIntelligence intel = new WorldIntelligence();
        intel.enterWorld("play.example.com", "world", "Server A", 1);
        intel.recordLocation("minecraft:overworld", 0, 64, 0, "A base", LocationCategory.BASE, 1);

        intel.enterWorld("other.example.com", "world", "Server B", 2);
        assertTrue(intel.search("A base", null, null).isEmpty(), "server B must not see server A's data");
        intel.recordLocation("minecraft:overworld", 0, 64, 0, "B base", LocationCategory.BASE, 2);

        intel.enterWorld("play.example.com", "world", "Server A", 3);
        assertEquals(1, intel.search("A base", null, null).size());
        assertEquals(2, intel.store().size());
    }

    @Test
    @DisplayName("singleplayer worlds are keyed by world name, not shared")
    void singleplayerWorldsAreSeparate() {
        String a = WorldProfile.buildKey(null, "My World");
        String b = WorldProfile.buildKey(null, "Another World");
        assertFalse(a.equals(b));
        assertTrue(a.startsWith("singleplayer|"));
    }

    @Test
    @DisplayName("nearestKnown answers the 'do I already know this?' question")
    void nearestKnownFindsNearbyCategory() {
        WorldIntelligence intel = new WorldIntelligence();
        intel.enterWorld("s", "w", "s", 1);
        intel.recordLocation("d", 30, 64, 0, "Base", LocationCategory.BASE, 1);

        assertNotNull(intel.nearestKnown("d", 0, 64, 0, LocationCategory.BASE, 50));
        assertNull(intel.nearestKnown("d", 0, 64, 0, LocationCategory.BASE, 10),
            "nothing within 10 blocks");
        assertNull(intel.nearestKnown("d", 0, 64, 0, LocationCategory.STRUCTURE, 50),
            "category must match");
    }

    @Test
    @DisplayName("chunks are recorded once and the set is bounded")
    void chunksRecordedOnce() {
        VisitedChunks chunks = new VisitedChunks();
        assertTrue(chunks.visit("d", 0, 0, 1), "first visit");
        assertFalse(chunks.visit("d", 0, 0, 2), "revisit is not new");
        assertEquals(1, chunks.size());
        assertTrue(chunks.visited("d", 0, 0));

        for (int i = 0; i < VisitedChunks.MAX_CHUNKS + 5; i++) {
            chunks.visit("d", i, 0, i + 10);
        }
        assertEquals(VisitedChunks.MAX_CHUNKS, chunks.size());
    }

    @Test
    @DisplayName("count index tracks counts and is bounded")
    void countIndexTracks() {
        CountIndex index = new CountIndex();
        index.observe("plains", 0, 64, 0, 1);
        index.observe("plains", 1, 64, 0, 2);
        index.observe("desert", 2, 64, 0, 3);
        assertEquals(2, index.count("plains"));
        assertEquals(1, index.count("desert"));
        assertEquals(0, index.count("missing"));
        assertEquals("plains", index.top().get(0).key());
    }

    @Test
    @DisplayName("discovery log keeps newest first and is bounded")
    void discoveryLogIsBounded() {
        DiscoveryLog log = new DiscoveryLog();
        for (int i = 0; i < DiscoveryLog.MAX_EVENTS + 5; i++) {
            log.add(DiscoveryLog.Kind.LOCATION, "d", i, 64, 0, "p" + i, i + 1);
        }
        assertEquals(DiscoveryLog.MAX_EVENTS, log.size());
        assertEquals("p" + (DiscoveryLog.MAX_EVENTS + 4), log.recent(1).get(0).label(),
            "the newest discovery is first");
    }

    @Test
    @DisplayName("the database round-trips through JSON without losing data")
    void roundTripsThroughJson() {
        WorldIntelligence original = new WorldIntelligence();
        original.enterWorld("server", "world", "Server", 1);
        original.recordLocation("minecraft:overworld", 1, 2, 3, "Base", LocationCategory.BASE, 10);
        original.recordBiome("minecraft:overworld", 1, 2, 3, "minecraft:plains", 10);
        original.recordResource("minecraft:overworld", 5, 6, 7, "minecraft:diamond_ore", 11);
        original.recordChunk("minecraft:overworld", 0, 0, 12);

        Map<String, Object> exported = original.exportMap();

        WorldIntelligence restored = new WorldIntelligence();
        restored.importMap(exported);
        restored.enterWorld("server", "world", "Server", 20);

        assertEquals(1, restored.search("Base", null, null).size());
        assertEquals(1, restored.current().biomes().count("minecraft:plains"));
        assertEquals(1, restored.current().resources().count("minecraft:diamond_ore"));
        assertTrue(restored.current().chunks().visited("minecraft:overworld", 0, 0));
    }

    @Test
    @DisplayName("a file written by the store loads back identically")
    void storeRoundTripsThroughDisk(@org.junit.jupiter.api.io.TempDir Path dir) {
        WorldIntelligence original = new WorldIntelligence();
        original.enterWorld("server", "world", "Server", 1);
        original.recordLocation("d", 4, 5, 6, "Chest", LocationCategory.RESOURCE, 9);

        assertTrue(WorldStore.save(dir, original));

        WorldIntelligence loaded = new WorldIntelligence();
        WorldStore.load(dir, loaded);
        loaded.enterWorld("server", "world", "Server", 10);
        assertEquals(1, loaded.search("Chest", null, null).size());
    }

    @Test
    @DisplayName("a corrupt store file is ignored rather than fatal")
    void corruptStoreIsIgnored(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(WorldStore.fileIn(dir), "{not valid json");
        WorldIntelligence loaded = new WorldIntelligence();
        WorldStore.load(dir, loaded);
        assertEquals(0, loaded.store().size());
    }

    @Test
    @DisplayName("formatting reads the way a user expects")
    void formattingIsReadable() {
        assertEquals("(1, 2, 3)", WorldFormat.coords(1, 2, 3));
        assertEquals("1.0 block", WorldFormat.distance(1.0));
        assertEquals("12.4 blocks", WorldFormat.distance(12.35));
        assertEquals("Overworld", WorldFormat.dimension("minecraft:overworld"));
        assertEquals("Diamond Ore", WorldFormat.itemName("minecraft:diamond_ore"));
        assertEquals("just now", WorldFormat.relativeTime(1000));
        assertEquals("3 minutes ago", WorldFormat.relativeTime(3 * 60_000L));
        assertEquals("1 hour ago", WorldFormat.relativeTime(60 * 60_000L));
        assertEquals("2 days ago", WorldFormat.relativeTime(2 * 24 * 60 * 60_000L));
    }
}
