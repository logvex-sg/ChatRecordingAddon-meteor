package com.logvex.addon.automation;

import com.logvex.addon.automation.core.CreativeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the creative acquisition state machine with fake ports. The fake inventory only changes
 * when a give is confirmed, mirroring how a real server behaves, so a success can never come from a
 * client-only prediction.
 */
class CreativeControllerTest {

    private CreativeFakes.FakeEnv env() {
        return new CreativeFakes.FakeEnv();
    }

    @Test
    @DisplayName("A single item is acquired once the server confirms the give")
    void singleItem() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:quartz_pillar", 32)));
        controller.start();

        for (int i = 0; i < 20 && !controller.finished(); i++) controller.tick();

        assertTrue(controller.finished());
        assertEquals(1, controller.completedCount());
        assertEquals(0, controller.failedCount());
        assertEquals(32, env.countOf("minecraft:quartz_pillar"));
    }

    @Test
    @DisplayName("Quantities larger than one stack are acquired across several gives")
    void largerThanOneStack() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 200)));
        controller.start();

        for (int i = 0; i < 200 && !controller.finished(); i++) controller.tick();

        assertTrue(controller.finished());
        assertEquals(200, env.countOf("minecraft:stone"));
        assertEquals(0, controller.failedCount());
    }

    @Test
    @DisplayName("Existing partial quantities are topped up, not duplicated")
    void existingPartialQuantity() {
        CreativeFakes.FakeEnv env = env();
        env.put(0, "minecraft:stone", 40);
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 64)));
        controller.start();

        for (int i = 0; i < 50 && !controller.finished(); i++) controller.tick();

        assertEquals(64, env.countOf("minecraft:stone"));
        assertTrue(controller.finished());
    }

    @Test
    @DisplayName("A give that is never acknowledged is not reported as success")
    void unconfirmedGiveIsNotSuccess() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        port.autoConfirm = false;
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 8)));
        controller.start();

        for (int i = 0; i < 10; i++) controller.tick();

        assertFalse(controller.finished());
        assertEquals(0, env.countOf("minecraft:stone"));
    }

    @Test
    @DisplayName("A rejected give fails the item after the retry budget")
    void rejectedGiveFails() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        port.rejectAll = true;
        CreativeController controller = new CreativeController(port, env, 2);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 8)));
        controller.start();

        for (int i = 0; i < 20 && !controller.finished(); i++) controller.tick();

        assertTrue(controller.finished());
        assertEquals(1, controller.failedCount());
        assertEquals(0, env.countOf("minecraft:stone"));
        assertEquals("Rejected by test.", controller.items().get(0).failure());
    }

    @Test
    @DisplayName("An unknown item id is reported failed rather than retried forever")
    void unknownItemFails() {
        CreativeFakes.FakeEnv env = env();
        env.known.put("minecraft:not_a_real_item", false);
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:not_a_real_item", 4)));
        controller.start();

        for (int i = 0; i < 20 && !controller.finished(); i++) controller.tick();

        assertTrue(controller.finished());
        assertEquals(1, controller.failedCount());
    }

    @Test
    @DisplayName("Without creative mode the workflow does not fabricate items")
    void noCreativeDoesNotFabricate() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        port.creative = false;
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 4)));
        controller.start();

        for (int i = 0; i < 10; i++) controller.tick();

        assertFalse(controller.finished());
        assertEquals(0, env.countOf("minecraft:stone"));
        assertNotNull(controller.lastError());
    }

    @Test
    @DisplayName("A full inventory waits instead of failing")
    void fullInventoryWaits() {
        CreativeFakes.FakeEnv env = env();
        env.fill();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:diamond", 1)));
        controller.start();

        for (int i = 0; i < 10; i++) controller.tick();

        assertFalse(controller.finished(), "the item stays pending while there is no space");
        assertEquals(0, controller.failedCount(), "waiting for space is not a failure");
        assertEquals(0, env.countOf("minecraft:diamond"));
        assertNotNull(controller.lastError());
    }

    @Test
    @DisplayName("Progress reflects the confirmed fraction of the request")
    void progress() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(new CreativeController.Request("minecraft:stone", 128)));
        controller.start();

        for (int i = 0; i < 4; i++) controller.tick();

        assertTrue(controller.progress() > 0.0);
        assertTrue(controller.progress() <= 1.0);
    }

    @Test
    @DisplayName("Ordering is preserved across several materials")
    void orderingPreserved() {
        CreativeFakes.FakeEnv env = env();
        CreativeFakes.FakePort port = new CreativeFakes.FakePort(env);
        CreativeController controller = new CreativeController(port, env, 3);
        controller.setRequests(List.of(
            new CreativeController.Request("minecraft:first", 1),
            new CreativeController.Request("minecraft:second", 1),
            new CreativeController.Request("minecraft:third", 1)));
        controller.start();

        for (int i = 0; i < 60 && !controller.finished(); i++) controller.tick();

        assertEquals("minecraft:first", controller.items().get(0).itemId());
        assertEquals("minecraft:second", controller.items().get(1).itemId());
        assertEquals("minecraft:third", controller.items().get(2).itemId());
        assertTrue(controller.finished());
    }
}
