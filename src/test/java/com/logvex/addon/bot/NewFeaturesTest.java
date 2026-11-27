package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.EntityRef;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskPlanner;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.exec.FarmingExecutor;
import com.logvex.addon.bot.exec.HuntingExecutor;
import com.logvex.addon.bot.exec.InventoryExecutor;
import com.logvex.addon.bot.exec.LightingExecutor;
import com.logvex.addon.bot.exec.TidyExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lighting, hunting and tidy tasks, plus the two executors that used to do nothing.
 *
 * <p>Most of these assert that a feature is *absent* under the wrong conditions — no torch, no prey,
 * no junk — because a task that reports success it did not achieve is worse than one that fails
 * loudly.
 */
class NewFeaturesTest {

    // ------------------------------------------------------------------ lighting

    @Test
    void lightingPlacesATorchInTheDark() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:torch", 16);
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 63, 0)));
        h.world.darkUntilLit = true;
        h.nav.arrivedNow = true;

        BotTask task = new BotTask(TaskType.LIGHTING, "light the area").required(1);
        LightingExecutor executor = new LightingExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.DONE, executor.step(task, h.context()));
        assertTrue(h.actions.calls.stream().anyMatch(c -> c.startsWith("place:")),
            "a dark spot with a torch in the bag must get a torch");
    }

    @Test
    void lightingFailsWithoutALightSource() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 63, 0)));
        h.world.darkUntilLit = true;

        BotTask task = new BotTask(TaskType.LIGHTING, "light").required(1);
        LightingExecutor executor = new LightingExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()),
            "no torch means the task cannot be done, not that it succeeded");
        assertFalse(h.actions.calls.stream().anyMatch(c -> c.startsWith("place:")),
            "nothing should be placed without a light source");
    }

    @Test
    void lightingDoesNotCountAPlacementThatDidNotLightTheSpot() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:torch", 16);
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 63, 0)));
        // Dark, and the light never rises: the server rejected the placement.
        h.world.darkUntilLit = true;
        h.world.lightStaysDark = true;
        h.nav.arrivedNow = true;

        BotTask task = new BotTask(TaskType.LIGHTING, "light").required(1);
        LightingExecutor executor = new LightingExecutor();
        executor.begin(task, h.context());

        assertNotEquals(TaskOutcome.DONE, executor.step(task, h.context()),
            "a torch that did not raise the light level must not count as placed");
    }

    @Test
    void lightingFinishesWhenTheAreaIsAlreadyBright() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:torch", 16);
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 63, 0)));
        // Light stays at 15, so there is nothing to fix.

        BotTask task = new BotTask(TaskType.LIGHTING, "light").required(1);
        LightingExecutor executor = new LightingExecutor();
        executor.begin(task, h.context());

        assertNotEquals(TaskOutcome.DONE, executor.step(task, h.context()),
            "an already-lit area is not a completed placement");
        assertFalse(h.actions.calls.stream().anyMatch(c -> c.startsWith("place:")),
            "no torch should be wasted on a lit block");
    }

    // ------------------------------------------------------------------ hunting

    @Test
    void huntingAttacksTheNamedPrey() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.creative = false;
        h.player.survival = true;
        h.world.entityRefs.add(new EntityRef(7, "minecraft:cow", new BlockPos3(1, 64, 0), 1.5, false));

        BotTask task = new BotTask(TaskType.HUNTING, "hunt cows")
            .itemId("minecraft:cow").required(1);
        HuntingExecutor executor = new HuntingExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.CONTINUE, executor.step(task, h.context()));
        assertTrue(h.actions.calls.contains("attack:7"), "the cow in reach should be attacked");
    }

    @Test
    void huntingIgnoresOtherSpecies() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(new EntityRef(9, "minecraft:zombie", new BlockPos3(1, 64, 0), 1.5, true));

        BotTask task = new BotTask(TaskType.HUNTING, "hunt cows")
            .itemId("minecraft:cow").required(1);
        HuntingExecutor executor = new HuntingExecutor();
        executor.begin(task, h.context());

        executor.step(task, h.context());
        assertFalse(h.actions.calls.stream().anyMatch(c -> c.startsWith("attack:")),
            "a zombie is not a cow; the hunt must not attack it");
    }

    @Test
    void huntingFailsWhenNoSpeciesWasNamed() {
        BotFakes.Harness h = new BotFakes.Harness();

        BotTask task = new BotTask(TaskType.HUNTING, "hunt").required(1);
        HuntingExecutor executor = new HuntingExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()),
            "a hunt with no target is a planning error, not a success");
    }

    @Test
    void huntingCountsDropsNotDisappearances() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.creative = false;
        h.player.survival = true;
        h.world.entityRefs.add(new EntityRef(7, "minecraft:cow", new BlockPos3(1, 64, 0), 1.5, false));

        BotTask task = new BotTask(TaskType.HUNTING, "hunt cows")
            .itemId("minecraft:cow").required(1);
        HuntingExecutor executor = new HuntingExecutor();
        executor.begin(task, h.context());

        // The mob walks out of range: the scan is empty, but no drop arrived.
        executor.step(task, h.context());
        h.world.entityRefs.clear();
        assertEquals(TaskOutcome.RETRY, executor.step(task, h.context()),
            "a vanished mob is not a kill; only a drop proves one");
    }

    @Test
    void huntingWithdrawsAtLowHealth() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.health = 2;
        h.world.entityRefs.add(new EntityRef(7, "minecraft:cow", new BlockPos3(1, 64, 0), 1.5, false));

        BotTask task = new BotTask(TaskType.HUNTING, "hunt cows")
            .itemId("minecraft:cow").required(1);
        HuntingExecutor executor = new HuntingExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()));
        assertFalse(h.actions.calls.stream().anyMatch(c -> c.startsWith("attack:")),
            "a hurt player must not keep swinging");
    }

    // ------------------------------------------------------------------ tidy

    @Test
    void tidyDropsJunk() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:cobblestone", 64);
        h.inventory.items.put("minecraft:diamond_pickaxe", 1);

        BotTask task = new BotTask(TaskType.TIDY, "clean up").required(1);
        TidyExecutor executor = new TidyExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.CONTINUE, executor.step(task, h.context()));
        assertTrue(h.inventory.dropped.contains("minecraft:cobblestone"),
            "cobblestone is bulk junk and should go");
        assertFalse(h.inventory.dropped.contains("minecraft:diamond_pickaxe"),
            "a pickaxe is never junk");
    }

    @Test
    void tidyKeepsValuablesEvenWhenFull() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:diamond", 64);
        h.inventory.items.put("minecraft:netherite_ingot", 4);

        BotTask task = new BotTask(TaskType.TIDY, "clean up").required(1);
        TidyExecutor executor = new TidyExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()),
            "with nothing droppable the task must report that, not claim success");
        assertTrue(h.inventory.dropped.isEmpty(), "no valuable item may be discarded");
    }

    // ------------------------------------------------------------------ inventory fix

    @Test
    void inventoryReportsTheFreeSlotsItWanted() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.inventory.items.put("minecraft:diamond", 5);

        BotTask task = new BotTask(TaskType.INVENTORY, "make room").required(2);
        InventoryExecutor executor = new InventoryExecutor();
        executor.begin(task, h.context());

        // The fake has 36 slots and one item, so there is already plenty of room.
        assertEquals(TaskOutcome.DONE, executor.step(task, h.context()));
        assertTrue(h.inventory.dropped.isEmpty(), "no item should go when there is already room");
    }

    @Test
    void inventoryDropsJunkToReachTheRequestedSlots() {
        BotFakes.Harness h = new BotFakes.Harness();
        for (int i = 0; i < 40; i++) h.inventory.items.put("minecraft:junk_" + i, 1);
        h.inventory.items.put("minecraft:cobblestone", 64);

        BotTask task = new BotTask(TaskType.INVENTORY, "make room").required(40);
        InventoryExecutor executor = new InventoryExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.CONTINUE, executor.step(task, h.context()));
        assertTrue(h.inventory.dropped.contains("minecraft:cobblestone"),
            "cobblestone is the only droppable item here");
    }

    @Test
    void inventoryRefusesToDropValuablesToMakeRoom() {
        BotFakes.Harness h = new BotFakes.Harness();
        for (int i = 0; i < 40; i++) h.inventory.items.put("minecraft:gem_" + i, 1);

        BotTask task = new BotTask(TaskType.INVENTORY, "make room").required(40);
        InventoryExecutor executor = new InventoryExecutor();
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()),
            "a full bag of valuables is a failure, not a licence to drop them");
        assertTrue(h.inventory.dropped.isEmpty());
    }

    // ------------------------------------------------------------------ farming fix

    @Test
    void farmingResolvesTheSeedFromTheCrop() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.knowledge.crops.put("minecraft:wheat_seeds", "minecraft:wheat");

        BotTask task = new BotTask(TaskType.FARMING, "harvest wheat").itemId("minecraft:wheat");
        new FarmingExecutor().begin(task, h.context());

        assertEquals("minecraft:wheat_seeds", task.parameter("seed", ""),
            "wheat is replanted with wheat_seeds; this used to stay empty so nothing replanted");
    }

    @Test
    void farmingReplantsAfterHarvest() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.knowledge.crops.put("minecraft:wheat_seeds", "minecraft:wheat");
        h.inventory.items.put("minecraft:wheat_seeds", 8);
        h.world.blocks.put("minecraft:wheat", List.of(new BlockPos3(1, 64, 0)));
        h.world.blocks.put("minecraft:farmland", List.of(new BlockPos3(1, 63, 0)));
        h.nav.arrivedNow = true;

        BotTask task = new BotTask(TaskType.FARMING, "harvest wheat").itemId("minecraft:wheat").required(1);
        FarmingExecutor executor = new FarmingExecutor();
        executor.begin(task, h.context());
        executor.step(task, h.context());

        assertTrue(h.actions.calls.stream().anyMatch(c -> c.contains("minecraft:wheat_seeds")),
            "the harvested crop should be replanted when a seed is held");
    }

    @Test
    void farmingDoesNotReplantIntoSomethingOtherThanSoil() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.knowledge.crops.put("minecraft:wheat_seeds", "minecraft:wheat");
        h.inventory.items.put("minecraft:wheat_seeds", 8);
        h.world.blocks.put("minecraft:wheat", List.of(new BlockPos3(1, 64, 0)));
        h.world.blocks.put("minecraft:stone", List.of(new BlockPos3(1, 63, 0)));
        h.nav.arrivedNow = true;

        BotTask task = new BotTask(TaskType.FARMING, "harvest wheat").itemId("minecraft:wheat").required(1);
        FarmingExecutor executor = new FarmingExecutor();
        executor.begin(task, h.context());
        executor.step(task, h.context());

        assertFalse(h.actions.calls.stream().anyMatch(c -> c.contains("minecraft:wheat_seeds")),
            "a seed on stone would be wasted");
    }

    // ------------------------------------------------------------------ planner

    @Test
    void theNewObjectivesAreReachableFromNaturalWords() {
        assertEquals(TaskType.LIGHTING, TaskPlanner.typeFor("light"));
        assertEquals(TaskType.LIGHTING, TaskPlanner.typeFor("torch"));
        assertEquals(TaskType.HUNTING, TaskPlanner.typeFor("hunt"));
        assertEquals(TaskType.TIDY, TaskPlanner.typeFor("tidy"));
        assertEquals(TaskType.TIDY, TaskPlanner.typeFor("clean"));
    }

    @Test
    void huntIsDistinctFromFight() {
        assertEquals(TaskType.COMBAT, TaskPlanner.typeFor("fight"));
        assertEquals(TaskType.HUNTING, TaskPlanner.typeFor("hunt"),
            "hunting a species is a different job from clearing hostiles");
    }

    @Test
    void aHuntGoalPlansAHuntingTaskForTheNamedSpecies() {
        BotFakes.Harness h = new BotFakes.Harness();
        var goal = new com.logvex.addon.bot.core.GoalManager.Goal(
            1, "hunt 8 cows", "hunt", "minecraft:cow", 8);

        var tasks = TaskPlanner.plan(goal, h.context());

        assertEquals(1, tasks.size());
        assertEquals(TaskType.HUNTING, tasks.get(0).type());
        assertEquals("minecraft:cow", tasks.get(0).itemId());
        assertEquals(8, tasks.get(0).required());
    }

    @Test
    void aLightingGoalPlansWithoutNeedingAnItem() {
        BotFakes.Harness h = new BotFakes.Harness();
        var goal = new com.logvex.addon.bot.core.GoalManager.Goal(1, "light up the base", "lighting", "", 6);

        var tasks = TaskPlanner.plan(goal, h.context());

        assertEquals(1, tasks.size());
        assertEquals(TaskType.LIGHTING, tasks.get(0).type());
        assertEquals(6, tasks.get(0).required());
    }

    @Test
    void everyNewTaskTypeHasAnExecutor() {
        var registry = com.logvex.addon.bot.exec.StandardExecutors.create();
        for (TaskType type : List.of(TaskType.LIGHTING, TaskType.HUNTING, TaskType.TIDY)) {
            assertTrue(registry.supports(type), type + " is planned but has no executor");
        }
    }
}
