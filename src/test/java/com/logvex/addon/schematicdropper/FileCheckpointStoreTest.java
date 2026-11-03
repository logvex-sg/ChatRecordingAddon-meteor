package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.mc.FileCheckpointStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On-disk checkpoint storage: round-trip, listing and recovery from corrupt files. */
class FileCheckpointStoreTest {
    @TempDir
    Path directory;

    private Checkpoint checkpoint(String name, long updatedAt) {
        return new Checkpoint(Checkpoint.CURRENT_VERSION, name, "fingerprint", new BlockPos3(10, 64, -5),
            1000L, updatedAt, Map.of(new BlockPos3(10, 64, -5), "minecraft:stone"),
            Map.of(new BlockPos3(11, 64, -5), "no support"), 100, 42);
    }

    @Test
    @DisplayName("a checkpoint survives a save and load with every field intact")
    void roundTrips() {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        store.save(checkpoint("house", 5000L));

        Optional<Checkpoint> loaded = store.load("house");
        assertTrue(loaded.isPresent());
        Checkpoint c = loaded.get();
        assertEquals(Checkpoint.CURRENT_VERSION, c.version());
        assertEquals("house", c.schematicName());
        assertEquals("fingerprint", c.schematicFingerprint());
        assertEquals(new BlockPos3(10, 64, -5), c.origin());
        assertEquals(100, c.totalBlocks());
        assertEquals(42, c.materialsPlaced());
        assertEquals("minecraft:stone", c.completed().get(new BlockPos3(10, 64, -5)));
        assertEquals("no support", c.failed().get(new BlockPos3(11, 64, -5)));
    }

    @Test
    @DisplayName("listing returns every stored checkpoint, newest first")
    void listsNewestFirst() {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        store.save(checkpoint("older", 1000L));
        store.save(checkpoint("newer", 9000L));

        List<Checkpoint> all = store.list();
        assertEquals(2, all.size());
        assertEquals("newer", all.get(0).schematicName());
        assertEquals("older", all.get(1).schematicName());
    }

    @Test
    @DisplayName("a corrupt checkpoint file is treated as absent instead of crashing the load")
    void recoversFromCorruptFile() throws IOException {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("broken.json"), "{ this is not json", StandardCharsets.UTF_8);

        assertFalse(store.load("broken").isPresent());
        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("deleting removes only the requested checkpoint")
    void deleteRemovesOne() {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        store.save(checkpoint("keep", 1L));
        store.save(checkpoint("drop", 2L));

        store.delete("drop");

        assertTrue(store.load("keep").isPresent());
        assertFalse(store.load("drop").isPresent());
        assertEquals(1, store.list().size());
    }

    @Test
    @DisplayName("names with path characters cannot escape the checkpoint directory")
    void sanitisesNames() {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        store.save(checkpoint("../escape", 1L));

        assertTrue(store.load("../escape").isPresent());
        assertEquals(1, store.list().size());
        assertTrue(Files.isRegularFile(directory.resolve(".._escape.json")));
    }
}
