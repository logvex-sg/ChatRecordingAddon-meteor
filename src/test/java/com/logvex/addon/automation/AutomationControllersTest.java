package com.logvex.addon.automation;

import com.logvex.addon.automation.core.AntiAfkController;
import com.logvex.addon.automation.core.ArmorGuardController;
import com.logvex.addon.automation.core.AutoEatController;
import com.logvex.addon.automation.core.AutoLogController;
import com.logvex.addon.automation.core.AutoReplenishController;
import com.logvex.addon.automation.core.AutoToolController;
import com.logvex.addon.automation.core.SafeWalkController;
import com.logvex.addon.automation.core.SprintController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Minecraft-free automation controllers. Each controller only decides; the
 * Minecraft layer performs the action, so every rule here is testable without a client.
 */
class AutomationControllersTest {

    // ------------------------------------------------------------------ auto eat

    @Test
    @DisplayName("Auto Eat only eats when hungry and holding food")
    void autoEat() {
        AutoEatController controller = new AutoEatController(14, 0);
        controller.setEnabled(true);
        assertFalse(controller.shouldEat(20, 20, List.of("minecraft:bread")));
        assertTrue(controller.shouldEat(14, 20, List.of("minecraft:bread")));
        assertFalse(controller.shouldEat(10, 20, List.of()));
    }

    @Test
    @DisplayName("Auto Eat prefers the highest-nutrition held food")
    void autoEatChoosesBestFood() {
        AutoEatController controller = new AutoEatController(14, 0);
        controller.setEnabled(true);
        String choice = controller.chooseFood(List.of("minecraft:cookie", "minecraft:golden_carrot"),
            id -> id.endsWith("golden_carrot") ? 6 : 2);
        assertEquals("minecraft:golden_carrot", choice);
    }

    @Test
    @DisplayName("Auto Eat respects its cooldown")
    void autoEatCooldown() {
        AutoEatController controller = new AutoEatController(14, 0);
        controller.markEaten(1000);
        assertFalse(controller.cooldownElapsed(1500, 1000));
        assertTrue(controller.cooldownElapsed(2000, 1000));
    }

    // ------------------------------------------------------------------ auto tool

    @Test
    @DisplayName("Auto Tool picks the highest-scoring slot and keeps the current one when it is best")
    void autoTool() {
        AutoToolController controller = new AutoToolController();
        controller.setEnabled(true);
        List<AutoToolController.Candidate> candidates = List.of(
            new AutoToolController.Candidate(0, "minecraft:hand", 0),
            new AutoToolController.Candidate(3, "minecraft:diamond_pickaxe", 10),
            new AutoToolController.Candidate(5, "minecraft:iron_pickaxe", 7));
        assertEquals(3, controller.chooseSlot(0, candidates, 0));
        assertEquals(3, controller.chooseSlot(3, candidates, 0));
    }

    @Test
    @DisplayName("Auto Tool does not swap below the minimum score")
    void autoToolMinScore() {
        AutoToolController controller = new AutoToolController();
        controller.setEnabled(true);
        List<AutoToolController.Candidate> candidates = List.of(
            new AutoToolController.Candidate(2, "minecraft:stick", 0));
        assertEquals(4, controller.chooseSlot(4, candidates, 5));
    }

    @Test
    @DisplayName("Auto Tool remembers the slot to swap back to")
    void autoToolSwapBack() {
        AutoToolController controller = new AutoToolController();
        controller.setEnabled(true);
        controller.setSwapBack(true);
        controller.rememberSwap(2);
        assertEquals(2, controller.previousSlot());
    }

    // ------------------------------------------------------------------ auto sprint

    @Test
    @DisplayName("Auto Sprint only sprints while moving forward and not sneaking")
    void autoSprint() {
        SprintController controller = new SprintController(true, 6);
        controller.setEnabled(true);
        assertTrue(controller.shouldSprint(true, 20, false, false));
        assertFalse(controller.shouldSprint(false, 20, false, false));
        assertFalse(controller.shouldSprint(true, 20, true, false));
        assertFalse(controller.shouldSprint(true, 20, false, true));
    }

    @Test
    @DisplayName("Auto Sprint respects the hunger threshold")
    void autoSprintHunger() {
        SprintController controller = new SprintController(true, 6);
        controller.setEnabled(true);
        assertFalse(controller.shouldSprint(true, 6, false, false));
        assertTrue(controller.shouldSprint(true, 7, false, false));
    }

    // ------------------------------------------------------------------ safe walk

    @Test
    @DisplayName("Safe Walk flags a step over a drop but not ordinary walking")
    void safeWalk() {
        SafeWalkController controller = new SafeWalkController(1, false);
        controller.setEnabled(true);
        assertTrue(controller.wouldFall(true, false, 8, true, true));
        assertFalse(controller.wouldFall(true, true, 0, true, true), "solid ground ahead is safe");
        assertFalse(controller.wouldFall(true, false, 1, true, true), "a one-block drop is safe");
        assertFalse(controller.wouldFall(false, false, 8, true, true), "no ground below means airborne");
        assertFalse(controller.wouldFall(true, false, 8, false, true), "not on the ground");
        assertFalse(controller.wouldFall(true, false, 8, true, false), "not moving");
    }

    // ------------------------------------------------------------------ armor guard

    @Test
    @DisplayName("Armor Guard classifies slots without confusing missing armour with zero durability")
    void armorGuard() {
        ArmorGuardController controller = new ArmorGuardController(25, 10);
        assertEquals(ArmorGuardController.PieceState.NOT_APPLICABLE, controller.classify(0, 0));
        assertEquals(ArmorGuardController.PieceState.OK, controller.classify(100, 200));
        assertEquals(ArmorGuardController.PieceState.WARNING, controller.classify(40, 200));
        assertEquals(ArmorGuardController.PieceState.CRITICAL, controller.classify(10, 200));
    }

    @Test
    @DisplayName("Armor Guard keeps the warning threshold above the critical one")
    void armorGuardThresholds() {
        ArmorGuardController controller = new ArmorGuardController(5, 10);
        assertTrue(controller.warnPercent() > controller.criticalPercent());
    }

    @Test
    @DisplayName("Armor Guard reports the most urgent piece")
    void armorGuardMostUrgent() {
        ArmorGuardController controller = new ArmorGuardController(25, 10);
        controller.setEnabled(true);
        List<ArmorGuardController.Piece> pieces = List.of(
            new ArmorGuardController.Piece("Helmet", "minecraft:iron_helmet", 100, 200,
                ArmorGuardController.PieceState.WARNING),
            new ArmorGuardController.Piece("Boots", "minecraft:iron_boots", 5, 200,
                ArmorGuardController.PieceState.CRITICAL));
        assertTrue(controller.anyAtRisk(pieces));
        assertEquals("Boots", controller.mostUrgent(pieces).slotName());
    }

    // ------------------------------------------------------------------ anti afk

    @Test
    @DisplayName("Anti AFK hands out actions on a schedule and only when enabled")
    void antiAfkSchedule() {
        AntiAfkController controller = new AntiAfkController(1000, 1000, new Random(1));
        assertNull(controller.next(0), "not enabled");
        controller.setEnabled(true);
        assertNull(controller.next(0), "first call arms the schedule");
        assertNull(controller.next(500), "not yet due");
        assertEquals(AntiAfkController.Action.LOOK_AROUND, controller.next(1000));
        assertEquals(AntiAfkController.Action.JUMP, controller.next(2000));
    }

    @Test
    @DisplayName("Anti AFK only uses the actions the user enabled")
    void antiAfkActionSet() {
        AntiAfkController controller = new AntiAfkController(1000, 1000, new Random(1));
        controller.setEnabled(true);
        controller.setActions(List.of(AntiAfkController.Action.SWING));
        assertEquals(List.of(AntiAfkController.Action.SWING), controller.actions());
        controller.arm(0);
        assertEquals(AntiAfkController.Action.SWING, controller.next(1000));
    }

    // ------------------------------------------------------------------ auto replenish

    @Test
    @DisplayName("Auto Replenish plans a move only when the hotbar slot is wrong")
    void autoReplenish() {
        AutoReplenishController controller = new AutoReplenishController();
        controller.setEnabled(true);
        controller.setRefillFromInventory(true);

        Map<Integer, String> desired = Map.of(0, "minecraft:diamond_pickaxe");
        Map<Integer, String> hotbar = Map.of(0, "minecraft:stone");
        Map<Integer, String> inventory = Map.of(0, "minecraft:stone", 12, "minecraft:diamond_pickaxe");

        List<AutoReplenishController.Assignment> plan = controller.plan(desired, hotbar, inventory);
        assertEquals(1, plan.size());
        assertEquals(0, plan.get(0).hotbarSlot());
        assertEquals(12, plan.get(0).sourceSlot());
    }

    @Test
    @DisplayName("Auto Replenish does nothing when the hotbar already holds the item")
    void autoReplenishNoop() {
        AutoReplenishController controller = new AutoReplenishController();
        controller.setEnabled(true);
        Map<Integer, String> desired = Map.of(0, "minecraft:diamond_pickaxe");
        Map<Integer, String> hotbar = Map.of(0, "minecraft:diamond_pickaxe");
        assertTrue(controller.plan(desired, hotbar, Map.of()).isEmpty());
    }

    // ------------------------------------------------------------------ auto log

    @Test
    @DisplayName("Auto Log fires the first matching rule and honours its grace period")
    void autoLog() {
        AutoLogController controller = new AutoLogController(6f, 2, 10_000);
        controller.setEnabled(true);
        controller.onJoin(0);
        assertEquals(AutoLogController.Reason.NONE, controller.evaluate(5_000, 1f, 20, 5, false, false));
        assertEquals(AutoLogController.Reason.LOW_HEALTH,
            controller.evaluate(11_000, 1f, 20, 5, false, false));
    }

    @Test
    @DisplayName("Auto Log reports a full inventory, hostile mob and nearby player")
    void autoLogRules() {
        AutoLogController controller = new AutoLogController(0f, 0, 0);
        controller.setEnabled(true);
        controller.setLogOnFullInventory(true);
        controller.setLogOnHostile(true, 8);
        controller.setLogOnPlayer(true, 16);
        controller.onJoin(0);
        assertEquals(AutoLogController.Reason.FULL_INVENTORY,
            controller.evaluate(1, 20f, 20, 0, false, false));
        assertEquals(AutoLogController.Reason.HOSTILE_NEARBY,
            controller.evaluate(1, 20f, 20, 5, true, false));
        assertEquals(AutoLogController.Reason.PLAYER_NEARBY,
            controller.evaluate(1, 20f, 20, 5, false, true));
    }

    @Test
    @DisplayName("Auto Log does nothing while disabled")
    void autoLogDisabled() {
        AutoLogController controller = new AutoLogController(20f, 20, 0);
        controller.onJoin(0);
        assertEquals(AutoLogController.Reason.NONE, controller.evaluate(1, 0f, 0, 0, true, true));
    }
}
