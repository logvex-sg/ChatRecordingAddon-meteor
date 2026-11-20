package com.logvex.addon.assistant.core;

import com.logvex.addon.common.Messages;
import com.logvex.addon.stats.ServerStats;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The table of placeholders a reply template can use.
 *
 * <p>A placeholder is a name plus a function from the {@link ResponseTemplate.Context} to its text.
 * Registering one is the whole extension mechanism: nothing else in the expansion path knows the
 * names, so a new figure is a single {@code register} call and a new placeholder cannot break the
 * parser. The built-ins are installed on {@link #standard()}; {@link #copy()} gives a registry a
 * caller can extend without touching the shared one.
 *
 * <p>Two forms are supported: an exact name ({@code {tps}}) and a family with an argument
 * ({@code {fact:key}}). A token that matches neither is left in the output verbatim, so a literal
 * brace in a reply survives and a typo is visible rather than silently blanked.
 *
 * <p>Every value here is derived from what the client already knows, so a reply is accurate in
 * single-player and on a remote server alike; a figure the client cannot observe resolves to
 * {@code n/a} rather than a fabricated number.
 */
public final class Placeholders {
    /** Produces the text for one placeholder occurrence. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param argument the text after the family's colon, empty for an exact name
         * @param context  the data the reply was authored against
         * @return the replacement, or null to leave the token unchanged
         */
        String resolve(String argument, ResponseTemplate.Context context);
    }

    private final Map<String, Resolver> exact = new LinkedHashMap<>();
    private final Map<String, Resolver> families = new LinkedHashMap<>();
    /** Which group each exact name belongs to, so a reference list can be shown in sections. */
    private final Map<String, String> groups = new LinkedHashMap<>();

    private Placeholders() {
    }

    /** A registry with the built-in placeholders installed. */
    public static Placeholders standard() {
        Placeholders registry = new Placeholders();
        registry.installDefaults();
        return registry;
    }

    /** An independent copy, for a caller that wants to add placeholders without mutating the shared one. */
    public Placeholders copy() {
        Placeholders clone = new Placeholders();
        clone.exact.putAll(exact);
        clone.families.putAll(families);
        clone.groups.putAll(groups);
        return clone;
    }

    /** Registers an exact name such as {@code tps}. Re-registering a name replaces it. */
    public Placeholders register(String name, Function<ResponseTemplate.Context, String> resolver) {
        if (name == null || name.isBlank()) return this;
        String key = normalize(name);
        exact.put(key, (argument, context) -> resolver.apply(context));
        return this;
    }

    /**
     * Registers a family such as {@code fact:} whose argument is the text after the colon.
     *
     * @param prefix the family name including the colon, e.g. {@code "fact:"}
     */
    public Placeholders registerFamily(String prefix, Resolver resolver) {
        if (prefix == null || prefix.isBlank()) return this;
        String key = normalize(prefix);
        families.put(key, resolver);
        return this;
    }

    /** The names this registry answers exactly, in registration order. */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(exact.keySet());
    }

    /** The group a name belongs to, or {@code Other} when it was registered without one. */
    public String group(String name) {
        String group = groups.get(normalize(name));
        return group == null ? "Other" : group;
    }

    /**
     * The exact names split into their groups, in registration order.
     *
     * <p>A flat list of every placeholder is unreadable in chat once the count passes a handful, so
     * the reference is shown one section at a time.
     */
    public Map<String, java.util.List<String>> byGroup() {
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        for (String name : exact.keySet()) {
            out.computeIfAbsent(group(name), ignored -> new java.util.ArrayList<>()).add(name);
        }
        return out;
    }

    /** The families this registry answers, in registration order. */
    public java.util.Set<String> families() {
        return java.util.Collections.unmodifiableSet(families.keySet());
    }

    /**
     * Replaces every known placeholder in {@code template}.
     *
     * @return the expanded text; unknown tokens are copied through unchanged
     */
    public String expand(String template, ResponseTemplate.Context context) {
        if (template == null || template.isEmpty()) return "";
        if (context == null) return template;

        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            String token = template.substring(i + 1, close).trim();
            String replacement = resolve(token, context);
            if (replacement == null) {
                // Unknown placeholder: keep it verbatim so nothing is lost.
                out.append(template, i, close + 1);
            } else {
                out.append(replacement);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * True when {@code template} names a figure that describes the player personally.
     *
     * <p>A template is authored once but can be spoken for anyone in chat, so a reply built from
     * "where am i" would answer with the <em>operator's</em> coordinates when a stranger types it.
     * The command path refuses such an answer to a remote sender; a trigger has no such check of its
     * own, so it asks this question instead of keeping a second list of sensitive words that could
     * drift from the first.
     *
     * <p>Position, vitals, inventory and the client's own memory are private: they say where the
     * operator is, what they carry, or what this client has stored. Server-wide figures (tps, ping,
     * player count) are not, because everyone can see them anyway.
     */
    public static boolean mentionsPrivateFigure(String template) {
        if (template == null || template.isEmpty()) return false;
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) return false;
            int close = template.indexOf('}', open + 1);
            if (close < 0) return false;
            String token = normalize(template.substring(open + 1, close));
            String family = token.contains(":") ? token.substring(0, token.indexOf(':')) : token;
            if (PRIVATE_FIGURES.contains(token) || PRIVATE_FAMILIES.contains(family)) return true;
            i = close + 1;
        }
        return false;
    }

    /**
     * Placeholders that read the local player or this client's own store.
     *
     * <p>Deliberately a list of names rather than a prefix rule: {@code {ping}} is the speaker's own
     * latency and is safe to answer anyone, while {@code {coords}} is not, and a prefix test would
     * have to treat them the same.
     *
     * <p>The set mirrors the {@code sensitive} flag the command catalogue already puts on its own
     * entries — position, gear, inventory, vitals, target — and adds the assistant's own memory,
     * which is the operator's private notes. Coarse world facts ({@code {biome}}, {@code {dimension}})
     * stay public, matching {@code !world}, because knowing the biome says nothing about where in it
     * the operator is standing.
     */
    private static final java.util.Set<String> PRIVATE_FIGURES = java.util.Set.of(
        "coords", "x", "y", "z", "facing", "location", "target",
        "health", "maxhealth", "absorption", "saturation", "food", "air", "armor", "totems",
        "held", "offhand", "effects", "gamemode", "level", "xp", "speed",
        "factcount", "fact", "memory"
    );

    /** Families whose argument names a stored fact, so the value is the operator's own note. */
    private static final java.util.Set<String> PRIVATE_FAMILIES = java.util.Set.of("fact");

    /** The replacement for one token, or null when no placeholder claims it. */
    public String resolve(String token, ResponseTemplate.Context context) {
        if (token == null || token.isEmpty()) return null;
        String key = normalize(token);

        Resolver direct = exact.get(key);
        if (direct != null) return direct.resolve("", context);

        for (Map.Entry<String, Resolver> entry : families.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                String argument = token.substring(entry.getKey().length()).trim();
                return entry.getValue().resolve(argument, context);
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ built-ins

    private void installDefaults() {
        grouped("Player", "player", ctx -> Messages.player(ctx.player()));
        grouped("Player", "message", ctx -> ctx.message() == null ? "" : ctx.message());
        grouped("Time", "time", ctx -> Messages.clock(ctx.now()));
        grouped("Calendar", "date", ctx -> Messages.dateTime(ctx.now()));
        grouped("Server", "server", ctx -> ctx.server() == null ? "" : ctx.server());
        grouped("Assistant", "memory", ctx ->
            ctx.memory() == null ? "0" : String.valueOf(ctx.memory().facts().size()));

        grouped("Performance", "tps", ctx -> ctx.stats().tps() <= 0f
            ? "n/a" : ServerStats.formatTps(ctx.stats().tps()));
        grouped("Performance", "mspt", ctx -> ctx.stats().mspt() < 0f
            ? "n/a" : String.format(Locale.ROOT, "%.1f", ctx.stats().mspt()));
        grouped("Performance", "ping", ctx -> ctx.stats().latency() < 0
            ? "n/a" : ctx.stats().latency() + " ms");
        grouped("Server", "players", ctx -> String.valueOf(ctx.stats().players()));
        grouped("Client", "uptime", ctx -> ServerStats.formatUptime(ctx.stats().uptimeMs()));
        grouped("Performance", "verdict", ctx -> ctx.stats().verdict());

        grouped("World", "dimension", ctx -> orQuestion(ctx.stats().dimension()));
        grouped("World", "world", ctx -> orQuestion(ctx.stats().world()));
        grouped("World", "biome", ctx -> orQuestion(ctx.stats().biome()));
        grouped("Time", "worldtime", ctx -> ServerStats.formatWorldTime(ctx.stats().worldTime()));
        grouped("World", "facing", ctx -> orQuestion(ctx.stats().facing()));

        grouped("Vitals", "health", ctx -> String.format(Locale.ROOT, "%.1f/%.0f",
            ctx.stats().health(), ctx.stats().maxHealth()));
        grouped("Vitals", "hunger", ctx -> String.valueOf(ctx.stats().food()));
        grouped("Position", "x", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().x()));
        grouped("Position", "y", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().y()));
        grouped("Position", "z", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().z()));
        grouped("Position", "coords", ctx -> String.format(Locale.ROOT, "%.0f, %.0f, %.0f",
            ctx.stats().x(), ctx.stats().y(), ctx.stats().z()));
        grouped("Position", "speed", ctx -> String.format(Locale.ROOT, "%.2f", ctx.stats().speed()));

        grouped("World", "weather", ctx -> orQuestion(ctx.stats().weather()));

        grouped("Client", "fps", ctx -> String.valueOf(ctx.stats().fps()));

        installServerPlaceholders();
        installPerformancePlaceholders();
        installPlayerPlaceholders();
        installWorldPlaceholders();
        installClientPlaceholders();

        // The assistant's own identity and memory size. Both identity values come from the
        // config the caller passed in; an empty value means there was no config, so each falls
        // back to something still readable rather than rendering blank.
        grouped("Assistant", "name", ctx -> ctx.assistant().isBlank() ? "the assistant" : ctx.assistant());
        grouped("Assistant", "prefix", ctx -> ctx.prefix().isBlank() ? "!" : ctx.prefix());
        grouped("Assistant", "factcount", ctx ->
            ctx.memory() == null ? "0" : String.valueOf(ctx.memory().facts().size()));

        // Families take an argument ({fact:key}) rather than a fixed name.
        registerFamily("fact:", (argument, ctx) -> {
            if (ctx.memory() == null) return "";
            return ctx.memory().fact(argument).map(AssistantMemory.Fact::value).orElse("");
        });
        registerFamily("count:", (argument, ctx) -> {
            if (ctx.memory() == null) return "0";
            return String.valueOf(ctx.memory().counter(argument));
        });
    }

// ------------------------------------------------------------------ added groups

    /**
     * Server-side figures: the ones only the server can answer.
     *
     * <p>A value the client was never told stays {@code n/a}. The maximum player count and the
     * server brand are the usual ones: the client protocol does not carry them, so a proxy that
     * strips the brand or a server that hides its slots simply has nothing to report.
     */
    private void installServerPlaceholders() {
        grouped("Server", "playercount", ctx -> String.valueOf(ctx.stats().players()));
        grouped("Server", "maxplayers", ctx -> ctx.stats().hasMaxPlayers()
            ? String.valueOf(ctx.stats().maxPlayers()) : "n/a");
        grouped("Server", "serverstatus", ctx -> ctx.stats().inWorld()
            ? (ctx.stats().singleplayer() ? "single-player" : "connected") : "not connected");
        grouped("Server", "serverversion", ctx -> orQuestion(ctx.stats().serverVersion()));
        grouped("Server", "serverbrand", ctx -> orQuestion(ctx.stats().serverBrand()));
        grouped("Server", "serveruptime", ctx -> ctx.stats().serverUptimeMs() < 0L
            ? "n/a" : ServerStats.formatUptime(ctx.stats().serverUptimeMs()));
    }

    /**
     * Performance figures: the tick rate, the frame rate and the heap.
     *
     * <p>The tick-rate averages come from samples this client took while connected, so a window
     * that has not been observed yet reports {@code n/a} rather than the current rate.
     */
    private void installPerformancePlaceholders() {
        grouped("Performance", "tps1", ctx -> averageTps(ctx.stats().tps1()));
        grouped("Performance", "tps5", ctx -> averageTps(ctx.stats().tps5()));
        grouped("Performance", "tps15", ctx -> averageTps(ctx.stats().tps15()));
        grouped("Memory", "memoryused", ctx -> String.valueOf(ctx.stats().usedMemoryMb()));
        grouped("Memory", "memoryfree", ctx -> ctx.stats().freeMemoryMb() < 0L
            ? "n/a" : String.valueOf(ctx.stats().freeMemoryMb()));
        grouped("Memory", "memorymax", ctx -> String.valueOf(ctx.stats().maxMemoryMb()));
        grouped("Memory", "memorypercent", ctx -> {
            long max = ctx.stats().maxMemoryMb();
            if (max <= 0L) return "n/a";
            long used = ctx.stats().usedMemoryMb();
            return String.format(Locale.ROOT, "%.0f", 100.0 * used / max);
        });
        // A bar makes a share readable at a glance, which is the whole point of a block reply.
        // A figure the client cannot measure yields an empty bar rather than a misleading full one.
        grouped("Meters", "memorybar", ctx -> bar(ctx.stats().usedMemoryMb(),
            ctx.stats().maxMemoryMb()));
        grouped("Meters", "healthbar", ctx -> bar(ctx.stats().player().health(),
            ctx.stats().player().maxHealth()));
        grouped("Meters", "hungerbar", ctx -> bar(ctx.stats().player().food(), 20));
        grouped("Meters", "tpsbar", ctx -> {
            double tps = ctx.stats().tps5();
            return tps < 0 ? ReplyFormat.bar(0, 10) : ReplyFormat.bar(tps / 20.0, 10);
        });
    }

    /** A bar for {@code value} out of {@code max}, or an empty bar when {@code max} is unusable. */
    private static String bar(double value, double max) {
        if (max <= 0 || value < 0) return ReplyFormat.bar(0, 10);
        return ReplyFormat.bar(value / max, 10);
    }

    /** Player figures, all read from the local player's own entity. */
    private void installPlayerPlaceholders() {
        grouped("Vitals", "maxhealth", ctx ->
            String.format(Locale.ROOT, "%.0f", ctx.stats().player().maxHealth()));
        grouped("Vitals", "absorption", ctx ->
            String.format(Locale.ROOT, "%.1f", ctx.stats().player().absorption()));
        grouped("Vitals", "saturation", ctx ->
            String.format(Locale.ROOT, "%.1f", ctx.stats().player().saturation()));
        grouped("Vitals", "armor", ctx -> String.valueOf(ctx.stats().armorPoints()));
        grouped("Inventory", "level", ctx -> String.valueOf(ctx.stats().player().level()));
        grouped("Inventory", "xp", ctx ->
            String.format(Locale.ROOT, "%.0f%%", 100.0 * ctx.stats().player().xpProgress()));
        grouped("Inventory", "gamemode", ctx -> orQuestion(ctx.stats().player().gamemodeName()));
        grouped("Inventory", "held", ctx -> orQuestion(ctx.stats().player().heldItem()));
        grouped("Inventory", "offhand", ctx -> orQuestion(ctx.stats().player().offhandItem()));
        grouped("Inventory", "effects", ctx -> ctx.stats().player().effects().isEmpty()
            ? "none" : String.join(", ", ctx.stats().player().effects()));
        grouped("Inventory", "totems", ctx -> String.valueOf(ctx.stats().player().totems()));
        grouped("Vitals", "food", ctx -> String.valueOf(ctx.stats().player().food()));
        grouped("Vitals", "air", ctx -> String.valueOf(ctx.stats().player().air()));
    }

    /** World figures: the clock, the day count and the difficulty. */
    private void installWorldPlaceholders() {
        grouped("Time", "day", ctx -> String.valueOf(ctx.stats().day()));
        grouped("Time", "daytime", ctx -> ctx.stats().dayLabel());
        grouped("World", "difficulty", ctx -> orQuestion(ctx.stats().difficulty()));
    }

    /** Figures about this client rather than the server. */
    private void installClientPlaceholders() {
        grouped("Client", "clientversion", ctx -> orQuestion(ctx.stats().clientVersion()));
        grouped("Client", "fabricversion", ctx -> orQuestion(ctx.stats().fabricVersion()));
        grouped("Client", "modcount", ctx -> ctx.stats().modCount() <= 0
            ? "n/a" : String.valueOf(ctx.stats().modCount()));
        grouped("Client", "sessiontime", ctx -> ctx.stats().sessionStartMs() <= 0L
            ? ServerStats.formatUptime(ctx.stats().uptimeMs())
            : ServerStats.formatUptime(Math.max(0L, ctx.now() - ctx.stats().sessionStartMs())));
        grouped("Calendar", "timestamp", ctx -> Messages.clock(ctx.now()));
        grouped("Calendar", "weekday", ctx ->
            zoned(ctx.now()).getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,
                Locale.ENGLISH));
        grouped("Calendar", "month", ctx ->
            zoned(ctx.now()).getMonth().getDisplayName(java.time.format.TextStyle.FULL,
                Locale.ENGLISH));
        grouped("Calendar", "year", ctx -> String.valueOf(zoned(ctx.now()).getYear()));
    }

    private static String averageTps(float value) {
        return value <= 0f ? "n/a" : ServerStats.formatTps(value);
    }

    /** The clock the date placeholders format, in the machine's own zone. */
    private static java.time.ZonedDateTime zoned(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault());
    }

    /** Registers {@code name} and records the section it belongs to for the reference list. */
    private void grouped(String group, String name, Function<ResponseTemplate.Context, String> resolver) {
        register(name, resolver);
        groups.put(normalize(name), group);
    }

    private static String orQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }
}
