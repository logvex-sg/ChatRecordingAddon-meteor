package com.logvex.addon.companion.core;

import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.orbit.IEventBus;

/**
 * Turns a line the companion app asked to send into the line that should actually go to the server.
 *
 * <p>Meteor only posts {@link SendMessageEvent} from its {@code sendChatMessage} hook, so a line sent
 * straight through {@code sendChatCommand} is invisible to every listener. That is why a shortcut
 * typed in the app used to be sent verbatim: a macro such as {@code !tpa <player> => /tpa {player}}
 * never expanded, and an addon command never ran. Posting the event here reproduces what the game does
 * for typed input, so the app and the keyboard take the same path.
 *
 * <p>The line keeps the shape the player gave it: a leading slash means a command, anything else is
 * chat. That matters because a macro shortcut is chat text, and prefixing it with a slash would stop
 * it matching.
 *
 * <p>Only a command needs this: a chat line already goes in through {@code sendChatMessage}, which
 * Meteor hooks, so posting it here as well would offer the same line to every listener twice.
 *
 * <p>The class is Minecraft-free apart from the event type, and takes the bus as a parameter, so the
 * behaviour is unit-testable without a client.
 */
public final class CompanionDispatch {
    private CompanionDispatch() {
    }

    /**
     * What should happen to a line after the listeners have seen it.
     *
     * @param handled true when a listener took the line and sent something of its own
     * @param text    the line still to send, trimmed, keeping any leading slash
     */
    public record Outcome(boolean handled, String text) {
        public static Outcome consumed() {
            return new Outcome(true, "");
        }

        public static Outcome send(String text) {
            return new Outcome(false, text == null ? "" : text.trim());
        }
    }

    /**
     * Offers a line from the app's command box to the listeners.
     *
     * <p>The line is offered exactly as typed, because it may be a macro shortcut rather than a
     * server command; adding a slash would stop the shortcut matching.
     */
    public static Outcome command(IEventBus bus, String command) {
        return dispatch(bus, command);
    }

    private static Outcome dispatch(IEventBus bus, String line) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty()) return Outcome.consumed();
        if (bus == null) return Outcome.send(text);

        SendMessageEvent event = bus.post(SendMessageEvent.get(text));
        if (event == null) return Outcome.send(text);
        if (event.isCancelled()) return Outcome.consumed();
        String out = event.message == null ? text : event.message.trim();
        return out.isEmpty() ? Outcome.consumed() : Outcome.send(out);
    }
}
