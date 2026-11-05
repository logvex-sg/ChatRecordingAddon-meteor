package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BotRecovery;
import com.logvex.addon.bot.core.SafetyController;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recovery policy, safety policy and memory migration. */
class BotPolicyTest {
    @Test
    void recoveryRetriesBoundedTimesThenAborts() {
        BotRecovery recovery = new BotRecovery(2, 100);
        assertEquals(BotRecovery.Decision.RETRY, recovery.onFailure(true, 0));
        assertEquals(BotRecovery.Decision.RETRY, recovery.onFailure(true, 0));
        assertEquals(BotRecovery.Decision.ABORT, recovery.onFailure(true, 0));
    }

    @Test
    void anUnrecoverableFailureAbortsImmediately() {
        BotRecovery recovery = new BotRecovery(5, 100);
        assertEquals(BotRecovery.Decision.ABORT, recovery.onFailure(false, 0));
    }

    @Test
    void backoffGrowsWithAttempts() {
        BotRecovery recovery = new BotRecovery(5, 100);
        recovery.onFailure(true, 0);
        long first = recovery.currentBackoff();
        recovery.onFailure(true, 0);
        assertTrue(recovery.currentBackoff() > first);
    }

    @Test
    void safetyStopsOnLowHealthAndFood() {
        BotFakes.Harness h = new BotFakes.Harness();
        SafetyController safety = new SafetyController(h.config);
        var inv = h.inventory.snapshot();

        assertEquals(SafetyController.Stop.NONE, safety.evaluate(h.player, h.world, inv, false));

        h.player.health = 2;
        assertEquals(SafetyController.Stop.LOW_HEALTH, safety.evaluate(h.player, h.world, inv, false));

        h.player.health = 20;
        h.player.food = 1;
        assertEquals(SafetyController.Stop.LOW_FOOD, safety.evaluate(h.player, h.world, inv, false));
    }

    @Test
    void safetyStopsWhenNotInWorld() {
        BotFakes.Harness h = new BotFakes.Harness();
        SafetyController safety = new SafetyController(h.config);
        h.world.canRun = false;
        assertEquals(SafetyController.Stop.DISCONNECTED,
            safety.evaluate(h.player, h.world, h.inventory.snapshot(), false));
    }

    @Test
    void disconnectionIsFatalButLowHealthIsNot() {
        assertTrue(SafetyController.isFatal(SafetyController.Stop.DISCONNECTED));
        assertFalse(SafetyController.isFatal(SafetyController.Stop.LOW_HEALTH));
    }

    @Test
    void memoryRoundTripsThroughItsRawForm() {
        BotMemory memory = new BotMemory();
        memory.putWaypoint(new BotMemory.Waypoint("base", "minecraft:overworld", 10, 64, -5));
        memory.setHome("base");
        memory.recordObjective("gather logs", true, "done");

        Map<String, Object> raw = new HashMap<>();
        raw.put("version", BotMemory.CURRENT_VERSION);
        assertTrue(memory.migrate(raw));
        assertEquals(BotMemory.CURRENT_VERSION, memory.version());
        assertTrue(memory.home().isPresent());
        assertEquals(1, memory.objectives().size());
        assertEquals(1, memory.waypoints().size());
    }

    @Test
    void aNewerMemoryVersionIsRejectedRatherThanCorrupted() {
        BotMemory memory = new BotMemory();
        Map<String, Object> raw = new HashMap<>();
        raw.put("version", BotMemory.CURRENT_VERSION + 1);
        assertFalse(memory.migrate(raw));
    }

    @Test
    void anOldMemoryVersionIsMigratedForward() {
        BotMemory memory = new BotMemory();
        Map<String, Object> raw = new HashMap<>();
        // Version 0 is the implicit shape of the first release; it must load, not be discarded.
        raw.put("version", 0);
        assertTrue(memory.migrate(raw));
        assertEquals(BotMemory.CURRENT_VERSION, memory.version());
    }

    @Test
    void placesAreDeduplicatedByKindAndPosition() {
        BotMemory memory = new BotMemory();
        memory.addPlace(new BotMemory.KnownPlace("chest", "a", "minecraft:overworld", 1, 2, 3));
        memory.addPlace(new BotMemory.KnownPlace("chest", "b", "minecraft:overworld", 1, 2, 3));
        assertEquals(1, memory.places(null).size());
    }
}
