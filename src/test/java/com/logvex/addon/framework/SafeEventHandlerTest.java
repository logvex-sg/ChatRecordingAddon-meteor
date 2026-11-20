package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that keeps a failing event handler from reaching the game.
 *
 * <p>Meteor posts {@code GameLeftEvent} from inside {@code MinecraftClient.disconnect()}, and the
 * event bus invokes listeners without a try/catch. So an exception in a disconnect handler does not
 * merely lose that handler's work: it unwinds out of {@code disconnect()} and the client dies
 * instead of returning to the title screen. These tests pin the two halves of the contract — a
 * failure is contained, and the handler still runs when it is healthy.
 */
class SafeEventHandlerTest {
    @Test
    @DisplayName("a throwing body is contained instead of propagating to the caller")
    void containsFailure() {
        assertDoesNotThrow(() -> SafeEventHandler.run("test", () -> {
            throw new IllegalStateException("boom");
        }));
    }

    @Test
    @DisplayName("a failure does not stop later handlers from running")
    void failureDoesNotStopLaterHandlers() {
        AtomicBoolean laterRan = new AtomicBoolean(false);

        SafeEventHandler.run("first", () -> {
            throw new IllegalStateException("boom");
        });
        SafeEventHandler.run("second", () -> laterRan.set(true));

        assertTrue(laterRan.get(), "a failed handler must not cancel the ones after it");
    }

    @Test
    @DisplayName("a healthy body runs exactly once")
    void runsHealthyBodyOnce() {
        List<String> calls = new ArrayList<>();

        SafeEventHandler.run("test", () -> calls.add("ran"));

        assertEquals(List.of("ran"), calls);
    }

    @Test
    @DisplayName("a null body is ignored rather than throwing")
    void ignoresNullBody() {
        assertDoesNotThrow(() -> SafeEventHandler.run("test", null));
    }
}
