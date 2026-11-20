package com.logvex.addon.assistant.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The facts a fresh install starts with.
 *
 * <p>The assistant is more useful when it already knows the handful of things every player looks up:
 * how to ask it something, what the prefix is, and how its own memory works. Without this the first
 * question a new user types gets "I do not understand", which reads as broken rather than unconfigured.
 *
 * <p>These are ordinary facts in the ordinary store, so {@code !forget} removes them, {@code !remember}
 * overwrites them, and {@code !facts} lists them. Nothing here is privileged or re-added after a
 * delete: a starter value the user has removed must stay removed, which is why seeding happens only
 * when the store has never held anything.
 */
public final class StarterKnowledge {

    /**
     * One seeded fact.
     *
     * @param key   the lookup key, lower case
     * @param value the text the assistant can answer with
     */
    public record Entry(String key, String value) {
    }

    /**
     * The shipped facts, in the order {@code !facts} lists them.
     *
     * <p>Deliberately short and factual. A long seed would bury the user's own facts and make the
     * memory look pre-filled rather than theirs.
     */
    public static List<Entry> entries() {
        return List.of(
            new Entry("assistant", "A local chat assistant: triggers answer keywords, memory stores "
                + "facts, and the prefix commands report live server figures. It runs entirely in "
                + "the client."),
            new Entry("prefix", "! by default. Type !help for the command list."),
            new Entry("help", "!help lists commands. !about shows what the assistant is doing. "
                + "!status shows the server."),
            new Entry("memory", "!remember key = value stores a fact, !fact key reads one back, "
                + "!facts lists them, !forget key removes one."),
            new Entry("status", "!status gives a server overview. !ping, !tps, !coords and !who "
                + "answer one figure each."),
            new Entry("macros", "A macro is a shortcut you type that becomes a command, such as "
                + "!tpa <player> => /tpa {player}. !macros lists them."),
            new Entry("privacy", "Answers that describe you (position, gear, effects) are shown "
                + "locally and never sent to chat."),
            new Entry("server", "Join a world and !status reads the server name, tick rate, "
                + "latency and player count.")
        );
    }

    /** The shipped facts as a key-to-value map, for tests and for the preview in the settings screen. */
    public static Map<String, String> asMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry entry : entries()) out.put(entry.key(), entry.value());
        return Map.copyOf(out);
    }

    private StarterKnowledge() {
    }
}
