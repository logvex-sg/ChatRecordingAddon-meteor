package com.logvex.addon.framework;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Runs an event handler body so a failure inside it cannot reach the game.
 *
 * <p>Meteor posts {@code GameLeftEvent} from inside {@code MinecraftClient.disconnect()}, and the
 * event bus it uses calls listeners without a try/catch. An exception thrown by any listener
 * therefore unwinds out of {@code disconnect()} itself, which leaves the client in a broken state and
 * takes the whole game down instead of returning the player to the title screen. A failure in this
 * addon's own disconnect bookkeeping must never cost the user their game, so every handler on that
 * path runs its body through here and the failure is logged instead.
 *
 * <p>The same reasoning applies to the other lifecycle events: they are posted from inside vanilla
 * methods that are not written to survive a listener throwing.
 */
public final class SafeEventHandler {
    // Its own logger rather than LogClient.LOG: that class initialises Meteor's category list, which
    // touches the Minecraft registries, and this guard has to work even when that has failed.
    private static final Logger LOG = LogUtils.getLogger();

    private SafeEventHandler() {
    }

    /**
     * Runs {@code body}, logging rather than propagating a failure.
     *
     * @param what a short name for the handler, used in the log line so the failing one is findable
     * @param body the handler body
     */
    public static void run(String what, Runnable body) {
        if (body == null) return;
        try {
            body.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Handler '{}' failed; the client continues without it", what, e);
        }
    }

    /**
     * A {@link Runnable} that runs its body through {@link #run}.
     *
     * <p>Lets a handler keep its body at the top level of the method instead of nested inside a
     * lambda, which is what makes the guard cheap to apply to every handler rather than only to the
     * ones that were already wrapped:
     *
     * <pre>{@code
     * @EventHandler
     * private void onTick(TickEvent.Post event) {
     *     SafeEventHandler.guard("AutoEat.onTick", () -> {
     *         ...existing body...
     *     }).run();
     * }
     * }</pre>
     *
     * @param what a short name for the handler, used in the log line
     * @param body the handler body
     */
    public static Runnable guard(String what, Runnable body) {
        return () -> run(what, body);
    }
}
