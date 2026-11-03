package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.core.CheckpointStore;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.RecoveryManager;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.WorldComparison;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checkpoint integrity and safe-resume behaviour. */
class RecoveryManagerTest {
    private final NewFakes.FakeBlockWorld world = new NewFakes.FakeBlockWorld();
    private final NewFakes.FakeTraits traits = new NewFakes.FakeTraits();
    private final NewFakes.FakeCheckpointStore store = new NewFakes.FakeCheckpointStore();

    private Schematic schematic(Map<BlockPos3, String> blocks) {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3, String> e : blocks.entrySet()) {
            states.put(e.getKey(), BlockStateSpec.parse(e.getValue()));
        }
        return new Schematic("build", SchematicFormat.SPONGE, 4, 4, 4, states);
    }

    private Checkpoint checkpointFor(Schematic s, BlockPos3 origin, Map<BlockPos3, String> completed) {
        return new Checkpoint(Checkpoint.CURRENT_VERSION, s.name(), Checkpoint.fingerprint(s), origin,
            1000, 1000, completed, Map.of(), s.blockCount(), completed.size());
    }

    @Test
    @DisplayName("a checkpoint for a different schematic is refused")
    void refusesDifferentSchematic() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        Checkpoint wrong = new Checkpoint(Checkpoint.CURRENT_VERSION, "build", "different-fingerprint",
            new BlockPos3(0, 0, 0), 0, 0, Map.of(), Map.of(), 1, 0);
        store.save(wrong);

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertFalse(plan.canResume());
        assertTrue(plan.reason().contains("different schematic"));
    }

    @Test
    @DisplayName("a checkpoint made at a different origin is refused")
    void refusesDifferentOrigin() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        store.save(checkpointFor(s, new BlockPos3(100, 0, 0), Map.of()));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertFalse(plan.canResume());
        assertTrue(plan.reason().contains("origin"));
    }

    @Test
    @DisplayName("an unsupported checkpoint version is refused")
    void refusesBadVersion() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        store.save(new Checkpoint(999, s.name(), Checkpoint.fingerprint(s), new BlockPos3(0, 0, 0),
            0, 0, Map.of(), Map.of(), 1, 0));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertFalse(plan.canResume());
        assertTrue(plan.reason().contains("version"));
    }

    @Test
    @DisplayName("a checkpointed position that no longer matches is rebuilt")
    void invalidatesStaleCheckpoint() {
        Schematic s = schematic(Map.of(
            new BlockPos3(0, 0, 0), "minecraft:stone",
            new BlockPos3(1, 0, 0), "minecraft:stone"));
        // The checkpoint claims both were built, but the world only has the first.
        Map<BlockPos3, String> done = new LinkedHashMap<>();
        done.put(new BlockPos3(0, 0, 0), "minecraft:stone");
        done.put(new BlockPos3(1, 0, 0), "minecraft:stone");
        store.save(checkpointFor(s, new BlockPos3(0, 0, 0), done));

        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:stone"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertTrue(plan.canResume());
        assertEquals(1, plan.verifiedComplete());
        assertEquals(1, plan.invalidated(), "the missing position must be rebuilt");
        assertEquals(1, plan.pending().size());
        assertEquals(new BlockPos3(1, 0, 0), plan.pending().get(0).worldPos());
        assertEquals(PlacementState.PENDING, plan.pending().get(0).state());
    }

    @Test
    @DisplayName("a fully intact checkpoint resumes with nothing left to do")
    void resumeWithNothingPending() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        store.save(checkpointFor(s, new BlockPos3(0, 0, 0),
            Map.of(new BlockPos3(0, 0, 0), "minecraft:stone")));
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:stone"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertTrue(plan.canResume());
        assertEquals(1, plan.verifiedComplete());
        assertFalse(plan.hasPendingWork());
    }

    @Test
    @DisplayName("no checkpoint means no resume is offered")
    void noCheckpoint() {
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        RecoveryManager.Plan plan = new RecoveryManager(store).planResume(s, new BlockPos3(0, 0, 0), comparison);

        assertFalse(plan.canResume());
        assertTrue(plan.reason().contains("no checkpoint"));
    }

    @Test
    @DisplayName("the fingerprint is stable and geometry-sensitive")
    void fingerprintStable() {
        Schematic a = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        Schematic b = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        Schematic c = schematic(Map.of(
            new BlockPos3(0, 0, 0), "minecraft:stone",
            new BlockPos3(1, 0, 0), "minecraft:stone"));

        assertEquals(Checkpoint.fingerprint(a), Checkpoint.fingerprint(b));
        assertFalse(Checkpoint.fingerprint(a).equals(Checkpoint.fingerprint(c)));
    }

    @Test
    @DisplayName("a store that fails to save does not corrupt state")
    void storeFailurePropagates() {
        store.failSaves = true;
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));
        RecoveryManager manager = new RecoveryManager(store);

        // save() must report the failure rather than silently swallowing it.
        try {
            manager.save(s, new BlockPos3(0, 0, 0), new com.logvex.addon.schematicdropper.core.PlacementEngine(
                new NewFakes.FakePlacementEnv(), new NewFakes.FakePlacementAction(new NewFakes.FakePlacementEnv()),
                new NewFakes.RecordingListener()), 1000);
            assertTrue(false, "expected the store failure to surface");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
