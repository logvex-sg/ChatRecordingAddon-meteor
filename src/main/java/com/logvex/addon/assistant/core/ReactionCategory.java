package com.logvex.addon.assistant.core;

import java.util.List;
import java.util.Locale;

/**
 * The kinds of automatic reaction the assistant can make, and whether each is on.
 *
 * <p>The reported bug was that the assistant answered {@code bye}, {@code gtg} and {@code cya}
 * without anyone asking it to. The cause was structural rather than a stray line of code: every
 * shipped rule was enabled, so merely having the module on made the assistant a participant in
 * ordinary conversation. Per-rule switches existed, but they were the wrong size — turning off
 * "goodbye" left "see you later", "good game" and twenty others still answering, and a new install
 * had no way to say "answer commands only".
 *
 * <p>So reactions are grouped, and the group decides the default. Everything conversational
 * ({@link #GREETINGS}, {@link #FAREWELLS}, {@link #COURTESIES}, {@link #PRAISE}, {@link #SOCIAL},
 * {@link #IDENTITY}) is <b>off</b> until asked for. {@link #COMMANDS} is on, because a prefixed
 * command such as {@code !tps} is an explicit request rather than chatter. {@link #CUSTOM} is on,
 * because a rule the operator wrote by hand is itself the explicit instruction.
 *
 * <p>That gives the guarantee the user asked for: with a fresh install, the assistant answers
 * {@code !tps} and nothing else. No conversational reply is possible until a category or an
 * individual rule is switched on.
 */
public enum ReactionCategory {
    GREETINGS("greetings", "Greetings", false,
        "Hello, good morning, good night and welcome back."),
    FAREWELLS("farewells", "Farewells", false,
        "Goodbye, bye, cya, gtg, see you later."),
    COURTESIES("courtesies", "Courtesies", false,
        "Thank you, sorry, no problem, you are welcome."),
    PRAISE("praise", "Praise and games", false,
        "Good bot, good game, gg, well played, congratulations."),
    SOCIAL("social", "Small talk", false,
        "Happy birthday, good luck, how are you, nice to meet you, laughing out loud."),
    IDENTITY("identity", "Questions about the assistant", false,
        "Who are you, what is your name, are you a bot, do you read chat."),
    FIGURES("figures", "Live figures asked in prose", false,
        "Where am i, what is my ping, what is the weather, what is the tps."),
    COMMANDS("commands", "Prefixed commands", true,
        "Command-shaped rules such as !tps, !status and !ping. An explicit request, so on by default."),
    CUSTOM("custom", "Your own rules", true,
        "Rules you wrote yourself. On by default, because writing one is the instruction to run it.");

    private final String id;
    private final String label;
    private final boolean defaultEnabled;
    private final String description;

    ReactionCategory(String id, String label, boolean defaultEnabled, String description) {
        this.id = id;
        this.label = label;
        this.defaultEnabled = defaultEnabled;
        this.description = description;
    }

    /** The stored form, stable across versions because it is written into saved rules. */
    public String id() {
        return id;
    }

    /** The heading shown in the Meteor GUI and the companion dashboard. */
    public String label() {
        return label;
    }

    /** One line explaining what the category covers. */
    public String description() {
        return description;
    }

    /** Whether a fresh install has this category on. */
    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** True for the categories that answer ordinary conversation rather than an explicit request. */
    public boolean conversational() {
        return this != COMMANDS && this != CUSTOM;
    }

    /** Parses a stored id, falling back to {@link #CUSTOM} for anything unrecognised. */
    public static ReactionCategory parse(String value) {
        return parse(value, CUSTOM);
    }

    /** Parses a stored id, falling back to {@code fallback} for anything unrecognised. */
    public static ReactionCategory parse(String value, ReactionCategory fallback) {
        if (value == null || value.isBlank()) return fallback;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (ReactionCategory category : values()) {
            if (category.id.equals(needle) || category.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return category;
            }
        }
        return fallback;
    }

    /**
     * Resolves a typed category name exactly, or null when there is no such category.
     *
     * <p>Separate from {@link #parse} because the two callers want opposite failure behaviour. A
     * stored id that no longer resolves has to fall back, or a rule saved by an older build would be
     * dropped. A name the user typed in a command must not fall back: {@code ai reactions off typo}
     * silently switching off {@link #CUSTOM} is worse than saying the name was wrong.
     */
    public static ReactionCategory exact(String value) {
        if (value == null || value.isBlank()) return null;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (ReactionCategory category : values()) {
            if (category.id.equals(needle) || category.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return category;
            }
        }
        return null;
    }

    /** Every category id, comma separated, for an error message. */
    public static String idList() {
        StringBuilder out = new StringBuilder();
        for (ReactionCategory category : values()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(category.id);
        }
        return out.toString();
    }

    /** Every category, in the order the settings screens list them. */
    public static List<ReactionCategory> all() {
        return List.of(values());
    }

    /** The categories a fresh install has on. */
    public static java.util.Set<ReactionCategory> defaultsEnabled() {
        java.util.EnumSet<ReactionCategory> out = java.util.EnumSet.noneOf(ReactionCategory.class);
        for (ReactionCategory category : values()) {
            if (category.defaultEnabled) out.add(category);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /**
     * The category a shipped pattern belongs to.
     *
     * <p>Used when a saved rule predates this enum: a config written by an older build stores rules
     * without a category, and reading them all as {@link #CUSTOM} would keep every courtesy
     * answering and quietly defeat the fix for exactly the installs that already exist. Looking the
     * pattern up in the shipped table instead means an upgrade inherits the new, silent default,
     * while a pattern that is not shipped stays {@link #CUSTOM} and keeps working.
     *
     * @param pattern the rule's pattern, or null
     */
    public static ReactionCategory ofShippedPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return CUSTOM;
        TriggerRule shipped = TriggerRule.shippedRuleFor(pattern);
        return shipped == null ? CUSTOM : shipped.category();
    }

    /**
     * True while {@link TriggerRule#preconfigured()} is building its table.
     *
     * <p>A shipped rule's compact constructor asks {@link #ofShippedPattern} which category it
     * belongs to, and answering that needs the shipped table — which is the table being built. The
     * flag breaks the cycle: during the build a rule keeps the category it was given (or
     * {@link #CUSTOM} for a caller that omitted it), and only a rule loaded from a config looks the
     * pattern up afterwards.
     */
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Marks the shipped table as under construction for the duration of {@code build}. */
    static <T> T duringShippedBuild(java.util.function.Supplier<T> build) {
        Boolean previous = BUILDING.get();
        BUILDING.set(Boolean.TRUE);
        try {
            return build.get();
        } finally {
            BUILDING.set(previous);
        }
    }

    /** True when a shipped rule is being constructed right now. */
    static boolean buildingShippedTable() {
        return Boolean.TRUE.equals(BUILDING.get());
    }
}
