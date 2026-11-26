package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionDispatch;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line the companion app sends must take the same path as one typed into chat.
 *
 * <p>Meteor only posts {@link SendMessageEvent} from its {@code sendChatMessage} hook, so a line sent
 * straight to {@code sendChatCommand} reaches no listener. That is exactly how a shortcut typed in
 * the app used to be sent verbatim, without its macro expanding. These tests drive a real
 * {@link EventBus} with real listeners, so they fail if the event is skipped again.
 */
class CompanionDispatchTest {
    /** Records what a listener was offered, so a test can assert the event really fired. */
    public static class Recorder {
        final List<String> seen = new ArrayList<>();

        @EventHandler
        private void onSend(SendMessageEvent event) {
            seen.add(event.message);
        }
    }

    /** Stands in for a macro: claims the line and sends a rewritten command itself. */
    public static class Macro {
        final List<String> sent = new ArrayList<>();

        @EventHandler
        private void onSend(SendMessageEvent event) {
            if (!event.message.startsWith("!tpa ")) return;
            String player = event.message.substring("!tpa ".length()).trim();
            event.cancel();
            sent.add("/tpa " + player);
        }
    }

    private EventBus bus;
    private Recorder recorder;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        recorder = new Recorder();
        // The real client registers this factory at startup; a listener method is private, so the
        // lookup has to be made with full privilege or Orbit cannot build its lambda.
        bus.registerLambdaFactory("com.logvex.addon",
            (lookupInMethod, klass) -> MethodHandles.privateLookupIn(klass, MethodHandles.lookup()));
        bus.subscribe(recorder);
    }

    @Test
    @DisplayName("a command is offered to the listeners and then sent as a command")
    void commandIsOffered() {
        CompanionDispatch.Outcome outcome = CompanionDispatch.command(bus, "/tpa Steve");

        assertEquals(List.of("/tpa Steve"), recorder.seen, "the listener never saw the command");
        assertFalse(outcome.handled(), "nothing claimed it, so it must be sent as written");
        assertEquals("/tpa Steve", outcome.text());
    }

    @Test
    @DisplayName("a macro that claims the line stops it being sent again")
    void macroConsumesTheLine() {
        Macro macro = new Macro();
        bus.subscribe(macro);

        CompanionDispatch.Outcome outcome = CompanionDispatch.command(bus, "!tpa Steve");

        assertEquals(List.of("/tpa Steve"), macro.sent, "the macro did not expand the shortcut");
        assertTrue(outcome.handled(), "a claimed line must not also be sent verbatim");
        assertEquals("", outcome.text());
    }



    @Test
    @DisplayName("an empty line is never posted")
    void emptyIsDropped() {
        assertTrue(CompanionDispatch.command(bus, "   ").handled());
        assertTrue(recorder.seen.isEmpty(), "an empty line must not reach a listener");
    }

    @Test
    @DisplayName("a missing bus still yields the line, so a send is not silently lost")
    void nullBusFallsBackToTheText() {
        assertEquals("/spawn", CompanionDispatch.command(null, "/spawn").text());
    }

    @Test
    @DisplayName("a macro shortcut typed in the app expands instead of reaching the server")
    void shortcutIsNotForcedIntoACommand() {
        Macro macro = new Macro();
        bus.subscribe(macro);

        CompanionDispatch.Outcome outcome = CompanionDispatch.command(bus, "!tpa Steve");

        assertEquals(List.of("/tpa Steve"), macro.sent);
        assertTrue(outcome.handled());
    }
}
