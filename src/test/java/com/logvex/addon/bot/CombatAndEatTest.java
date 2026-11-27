package com.logvex.addon.bot;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotConfig;
import com.logvex.addon.bot.core.BotController;
import com.logvex.addon.bot.core.BotListener;
import com.logvex.addon.bot.core.BotState;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.EntityRef;
import com.logvex.addon.bot.core.GoalManager;
import com.logvex.addon.bot.core.TaskPlanner;
import com.logvex.addon.bot.core.SafetyController;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.exec.CombatExecutor;
import com.logvex.addon.bot.exec.EatExecutor;
import com.logvex.addon.bot.exec.StandardExecutors;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combat and auto-eat: the two behaviours that make the bot survive on its own instead of pausing
 * and waiting for a player.
 */
class CombatAndEatTest {
    private static EntityRef hostile(int id, double distance) {
        return new EntityRef(id, "minecraft:zombie", new BlockPos3(1, 64, 0), distance, true);
    }

    // ------------------------------------------------------------------ threat safety

    @Test
    void threatNearbyPausesTheBot() {
        BotFakes.Harness h = new BotFakes.Harness();
        SafetyController safety = new SafetyController(h.config);

        assertEquals(SafetyController.Stop.THREAT,
            safety.evaluate(h.player, h.world, h.inventory.snapshot(), true));
    }

    @Test
    void threatStopRespectsItsOwnToggle() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.config = BotConfig.builder().stopOnThreat(false).build();
        SafetyController safety = new SafetyController(h.config);

        // The mob is there, but the user asked not to care about mobs.
        assertEquals(SafetyController.Stop.NONE,
            safety.evaluate(h.player, h.world, h.inventory.snapshot(), true));
    }

    @Test
    void threatOutranksHunger() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.food = 1;
        SafetyController safety = new SafetyController(h.config);

        assertEquals(SafetyController.Stop.THREAT,
            safety.evaluate(h.player, h.world, h.inventory.snapshot(), true));
    }

    // ------------------------------------------------------------------ auto-eat policy

    @Test
    void shouldEatOnlyWhenHungryAndCarryingFood() {
        BotFakes.Harness h = new BotFakes.Harness();
        SafetyController safety = new SafetyController(h.config);

        assertFalse(safety.shouldEat(h.player, true), "full hunger needs no meal");

        h.player.food = 10;
        assertTrue(safety.shouldEat(h.player, true));
        assertFalse(safety.shouldEat(h.player, false), "nothing to eat means no meal");
    }

    @Test
    void autoEatHonoursItsToggle() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.config = BotConfig.builder().autoEat(false).build();
        h.player.food = 2;

        assertFalse(new SafetyController(h.config).shouldEat(h.player, true));
    }

    // ------------------------------------------------------------------ eat executor

    @Test
    void eatExecutorFinishesOnceFoodLevelRises() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.food = 8;
        h.knowledge.foods.add("minecraft:bread");
        h.inventory.items.put("minecraft:bread", 3);

        EatExecutor executor = new EatExecutor();
        BotTask task = new BotTask(TaskType.EAT, "Eat").required(1);
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.CONTINUE, executor.step(task, h.context()));
        assertTrue(h.actions.calls.contains("eat"));

        // The server applied the meal: food is back above the eat-at threshold (14 by default).
        h.player.food = 16;
        assertEquals(TaskOutcome.DONE, executor.step(task, h.context()));
    }

    @Test
    void eatExecutorFailsWithoutFood() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.food = 4;

        EatExecutor executor = new EatExecutor();
        BotTask task = new BotTask(TaskType.EAT, "Eat").required(1);
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()));
    }

    @Test
    void eatExecutorGivesUpWhenFoodNeverRises() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.food = 4;
        h.knowledge.foods.add("minecraft:bread");
        h.inventory.items.put("minecraft:bread", 1);

        EatExecutor executor = new EatExecutor();
        BotTask task = new BotTask(TaskType.EAT, "Eat").required(1);
        executor.begin(task, h.context());

        // Food never moves: the bite budget must run out rather than looping forever.
        TaskOutcome last = TaskOutcome.CONTINUE;
        for (int i = 0; i < 20 && last == TaskOutcome.CONTINUE; i++) {
            last = executor.step(task, h.context());
        }
        assertEquals(TaskOutcome.FAILED, last);
    }

    // ------------------------------------------------------------------ combat executor

    @Test
    void combatFailsWhenNothingHostileIsNear() {
        BotFakes.Harness h = new BotFakes.Harness();
        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Clear the area").required(1);
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()));
    }

    @Test
    void combatClosesDistanceBeforeSwinging() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(hostile(7, 8.0));

        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Fight").required(1);
        executor.begin(task, h.context());
        executor.step(task, h.context());

        // Out of reach: the bot navigates instead of attacking the air.
        assertTrue(h.nav.running);
        assertFalse(h.actions.calls.stream().anyMatch(c -> c.startsWith("attack")));
    }

    @Test
    void combatAttacksInMeleeRange() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(hostile(9, 1.5));

        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Fight").required(1);
        executor.begin(task, h.context());
        executor.step(task, h.context());

        assertTrue(h.actions.calls.contains("attack:9"));
    }

    @Test
    void combatCompletesWhenHostilesAreGone() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(hostile(3, 1.5));

        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Fight").required(1);
        executor.begin(task, h.context());
        executor.step(task, h.context());

        // The mob died: it is no longer in the scan.
        h.world.entityRefs.clear();
        assertEquals(TaskOutcome.DONE, executor.step(task, h.context()));
    }

    @Test
    void combatWithdrawsWhenHealthIsLow() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(hostile(3, 1.5));
        h.player.health = 3;

        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Fight").required(1);
        executor.begin(task, h.context());

        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()));
    }

    @Test
    void combatCountsOnlyHostiles() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.entityRefs.add(new EntityRef(1, "minecraft:cow", new BlockPos3(1, 64, 0), 1.0, false));

        CombatExecutor executor = new CombatExecutor();
        BotTask task = new BotTask(TaskType.COMBAT, "Fight").required(1);
        executor.begin(task, h.context());

        // A cow is not a threat, so there is nothing to fight.
        assertEquals(TaskOutcome.FAILED, executor.step(task, h.context()));
    }

    // ------------------------------------------------------------------ controller wiring

    @Test
    void controllerPausesWhenAHostileAppears() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        BotController controller = new BotController(StandardExecutors.create(), new BotListener() {
        }, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());

        controller.addGoal("chop 4 oak_log", "woodcut", "minecraft:oak_log", 4);
        controller.start();
        for (int i = 0; i < 3; i++) controller.tick();
        assertTrue(controller.state().isActive(), "the bot should be working before the mob appears");

        // This is the bug the feature fixes: the flag now comes from the world, not a constant.
        h.world.hostileNearby = true;
        controller.tick();

        assertEquals(BotState.PAUSED, controller.state());
    }

    @Test
    void controllerDoesNotPauseOnThreatWhenDisabled() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.config = BotConfig.builder().stopOnThreat(false).build();
        h.world.blocks.put("minecraft:oak_log", List.of(new BlockPos3(1, 64, 0)));
        h.world.hostileNearby = true;

        BotController controller = new BotController(StandardExecutors.create(), new BotListener() {
        }, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        controller.addGoal("chop 4 oak_log", "woodcut", "minecraft:oak_log", 4);
        controller.start();
        controller.tick();

        assertTrue(controller.state().isActive());
    }

    @Test
    void controllerQueuesAMealWhenHungry() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.player.food = 6;
        h.knowledge.foods.add("minecraft:bread");
        h.inventory.items.put("minecraft:bread", 2);

        BotController controller = new BotController(StandardExecutors.create(), new BotListener() {
        }, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        controller.addGoal("chop 4 oak_log", "woodcut", "minecraft:oak_log", 4);
        controller.start();
        controller.tick();

        assertTrue(controller.queue().hasType(TaskType.EAT), "a hungry bot should feed itself");
    }

    @Test
    void controllerDoesNotQueueAMealWhenFed() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.knowledge.foods.add("minecraft:bread");
        h.inventory.items.put("minecraft:bread", 2);

        BotController controller = new BotController(StandardExecutors.create(), new BotListener() {
        }, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        controller.start();
        controller.tick();

        assertFalse(controller.queue().hasType(TaskType.EAT));
    }

    @Test
    void controllerDoesNotQueueAMealWhileThreatened() {
        BotFakes.Harness h = new BotFakes.Harness();
        h.knowledge.foods.add("minecraft:bread");
        h.inventory.items.put("minecraft:bread", 2);
        h.player.food = 6;
        h.world.hostileNearby = true;

        BotController controller = new BotController(StandardExecutors.create(), new BotListener() {
        }, h.clock);
        controller.setConfig(h.config);
        controller.setContext(h.context());
        controller.start();
        controller.tick();

        assertFalse(controller.queue().hasType(TaskType.EAT),
            "a meal queued next to a mob is a meal the bot cannot eat");
    }

    // ------------------------------------------------------------------ planning
    @Test
    void combatIsReachableFromNaturalWords() {
        assertEquals(TaskType.COMBAT, TaskPlanner.typeFor("fight"));
        assertEquals(TaskType.COMBAT, TaskPlanner.typeFor("attack"));
        assertEquals(TaskType.COMBAT, TaskPlanner.typeFor("defend"));
    }

    @Test
    void combatGoalPlansWithoutAnItem() {
        BotFakes.Harness h = new BotFakes.Harness();
        List<BotTask> planned = TaskPlanner.plan(
            new GoalManager.Goal(1, "fight the mobs", "combat", "", 2), h.context());

        assertEquals(1, planned.size());
        assertEquals(TaskType.COMBAT, planned.get(0).type());
        assertEquals(2, planned.get(0).required());
    }
}
