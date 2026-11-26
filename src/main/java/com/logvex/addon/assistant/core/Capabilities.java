package com.logvex.addon.assistant.core;

import com.logvex.addon.stats.ServerStats;

import java.util.ArrayList;
import java.util.List;

/**
 * What the assistant can and cannot do right now.
 *
 * <p>An assistant that answers a question it cannot know is worse than one that says it cannot.
 * Before this existed the only honest answers were in the status renderer, which printed
 * {@code n/a} per figure; there was no way to ask the whole question, so the assistant would happily
 * offer TPS while disconnected and claim a report had been filed when no webhook was configured.
 *
 * <p>This is derived from live state, never hardcoded: each ability is checked against the thing it
 * actually needs. That means the answer changes as the player connects or configures a webhook —
 * which is the point.
 *
 * <p>Deliberately a plain value object with no Minecraft types, so the rules can be tested without
 * a game.
 */
public record Capabilities(
    boolean connected,
    boolean inWorld,
    boolean memoryEnabled,
    boolean triggersEnabled,
    boolean reportsEnabled,
    boolean permissionsEnabled,
    int triggers,
    int facts
) {

    /** One thing the assistant can do, or cannot, with the reason. */
    public record Ability(String name, boolean available, String detail) {
        public Ability {
            if (name == null) name = "";
            if (detail == null) detail = "";
        }
    }

    /** Nothing is available: not connected, nothing configured. */
    public static final Capabilities NONE =
        new Capabilities(false, false, false, false, false, false, 0, 0);

    /**
     * Derives the capabilities from live figures and configuration.
     *
     * @param stats        the current snapshot, or null when unavailable
     * @param config       the assistant settings, or null for the defaults
     * @param reportsReady     whether the reports channel is configured and allowed
     * @param permissionsReady whether the permission gate is switched on
     * @param triggers         how many trigger rules are armed
     * @param facts            how many remembered facts exist
     */
    public static Capabilities of(ServerStats stats, AssistantConfig config, boolean reportsReady,
                                  boolean permissionsReady, int triggers, int facts) {
        AssistantConfig cfg = config == null ? AssistantConfig.DEFAULTS : config;
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
        return new Capabilities(
            s.inWorld(),
            s.inWorld(),
            cfg.memoryCommands(),
            cfg.respondToTriggers(),
            reportsReady,
            permissionsReady,
            Math.max(0, triggers),
            Math.max(0, facts));
    }

    /** Every ability, in the order worth showing. */
    public List<Ability> abilities() {
        List<Ability> out = new ArrayList<>(10);
        out.add(new Ability("Triggers", triggersEnabled && triggers > 0,
            !triggersEnabled ? "switched off"
                : triggers == 0 ? "no rules configured" : triggers + " rules"));
        out.add(new Ability("Memory", memoryEnabled,
            memoryEnabled ? facts + " facts" : "switched off"));
        out.add(new Ability("Reports", reportsEnabled,
            reportsEnabled ? "posting to your reports channel"
                : "no reports channel configured"));
        out.add(new Ability("Permissions", permissionsEnabled,
            permissionsEnabled ? "checking every command" : "off: anyone may ask"));
        // The live figures are what a player most often assumes are known, so each is listed with
        // the thing that decides it rather than a blanket "stats".
        out.add(new Ability("Server figures", connected,
            connected ? "measuring TPS and latency" : "not connected"));
        out.add(new Ability("Player list", inWorld,
            inWorld ? "reading who is online" : "not connected"));
        out.add(new Ability("World position", inWorld,
            inWorld ? "reporting coords and biome" : "not in a world"));
        return out;
    }

    /** The abilities that are available right now. */
    public List<Ability> available() {
        return abilities().stream().filter(Ability::available).toList();
    }

    /** The abilities that are not available, with the reason. */
    public List<Ability> unavailable() {
        return abilities().stream().filter(a -> !a.available()).toList();
    }

    /** True when nothing at all is available, so the assistant should stay quiet. */
    public boolean nothing() {
        return available().isEmpty();
    }

    /**
     * A one-line summary, for a chat reply.
     *
     * <p>Names what it can do and, when something is off, what it cannot: "3 of 8" alone does not
     * tell the user which part is missing.
     */
    public String summary() {
        List<Ability> can = available();
        List<Ability> cannot = unavailable();
        if (can.isEmpty()) return "Nothing available right now.";
        String yes = can.stream().map(Ability::name).reduce((a, b) -> a + ", " + b).orElse("");
        if (cannot.isEmpty()) return "Available: " + yes + ".";
        String no = cannot.stream().map(Ability::name).reduce((a, b) -> a + ", " + b).orElse("");
        return "Available: " + yes + ". Not available: " + no + ".";
    }
}
