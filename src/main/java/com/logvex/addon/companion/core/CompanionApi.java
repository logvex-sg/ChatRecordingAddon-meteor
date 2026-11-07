package com.logvex.addon.companion.core;

import java.util.List;
import java.util.Map;

/**
 * The operations the companion app can ask a client to perform.
 *
 * <p>The Minecraft layer implements this; the router only calls it. Keeping it an interface means the
 * whole HTTP surface can be tested against a fake, and it makes the boundary explicit: everything the
 * app can do is a method here, so there is no way for a request to reach into the game by accident.
 *
 * <p>Every method is expected to be called on the client thread. The router marshals calls onto the
 * game thread before invoking them, so an implementation never has to worry about threading.
 */
public interface CompanionApi {

    /** The current picture of the client. Cheap enough to call on every request. */
    CompanionSnapshot snapshot();

    /** Starts, resumes or unpauses the bot. */
    ActionResult startBot();

    /** Pauses the bot, keeping its queue. */
    ActionResult pauseBot();

    /** Resumes a paused bot. */
    ActionResult resumeBot();

    /** Stops the current task but keeps the queue. */
    ActionResult stopBot();

    /** The immediate, queue-clearing stop. */
    ActionResult emergencyStopBot();

    /**
     * Queues a bot objective expressed in the addon's own objective grammar, for example
     * {@code mine oak_log 64} or {@code goto 120 64 -30}.
     */
    ActionResult enqueueTask(String objective);

    /** Cancels one queued task by id. */
    ActionResult cancelTask(String taskId);

    /** Enables or disables one module by its addon name. */
    ActionResult setModule(String name, boolean active);

    /** Sends a chat line as the player. Refused when the client is not in a world. */
    ActionResult sendChat(String message);

    /** Sends a command line (leading slash optional). Refused when not in a world. */
    ActionResult sendCommand(String command);

    /** Queues a chat message to be shown to the player locally, prefixed with the addon tag. */
    ActionResult notify(String message);

    /** Runs one of the named, allow-listed chat-log queries. */
    Map<String, Object> query(String name, Map<String, String> params);

    /** The names of the queries this client supports, for the app to build its UI from. */
    List<String> queries();

    /** A value describing the outcome of an action, so the app can report it in plain language. */
    record ActionResult(boolean ok, String message) {
        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }
}
