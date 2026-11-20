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
        grouped("Time", "date", ctx -> Messages.dateTime(ctx.now()));
        grouped("Server", "server", ctx -> ctx.server() == null ? "" : ctx.server());
        grouped("Server", "memory", ctx ->
            ctx.memory() == null ? "0" : String.valueOf(ctx.memory().facts().size()));

        grouped("Server", "tps", ctx -> ctx.stats().tps() <= 0f
            ? "n/a" : ServerStats.formatTps(ctx.stats().tps()));
        grouped("Server", "mspt", ctx -> ctx.stats().mspt() < 0f
            ? "n/a" : String.format(Locale.ROOT, "%.1f", ctx.stats().mspt()));
        grouped("Server", "ping", ctx -> ctx.stats().latency() < 0
            ? "n/a" : ctx.stats().latency() + " ms");
        grouped("Server", "players", ctx -> String.valueOf(ctx.stats().players()));
        grouped("Server", "uptime", ctx -> ServerStats.formatUptime(ctx.stats().uptimeMs()));
        grouped("Server", "verdict", ctx -> ctx.stats().verdict());

        grouped("World", "dimension", ctx -> orQuestion(ctx.stats().dimension()));
        grouped("World", "world", ctx -> orQuestion(ctx.stats().world()));
        grouped("World", "biome", ctx -> orQuestion(ctx.stats().biome()));
        grouped("World", "worldtime", ctx -> ServerStats.formatWorldTime(ctx.stats().worldTime()));
        grouped("World", "facing", ctx -> orQuestion(ctx.stats().facing()));

        grouped("Position", "health", ctx -> String.format(Locale.ROOT, "%.1f/%.0f",
            ctx.stats().health(), ctx.stats().maxHealth()));
        grouped("Position", "hunger", ctx -> String.valueOf(ctx.stats().food()));
        grouped("Position", "x", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().x()));
        grouped("Position", "y", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().y()));
        grouped("Position", "z", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().z()));
        grouped("Position", "coords", ctx -> String.format(Locale.ROOT, "%.0f, %.0f, %.0f",
            ctx.stats().x(), ctx.stats().y(), ctx.stats().z()));
        grouped("Position", "speed", ctx -> String.format(Locale.ROOT, "%.2f", ctx.stats().speed()));

        grouped("World", "weather", ctx -> orQuestion(ctx.stats().weather()));

        grouped("Client", "fps", ctx -> String.valueOf(ctx.stats().fps()));

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

    /** Registers {@code name} and records the section it belongs to for the reference list. */
    private void grouped(String group, String name, Function<ResponseTemplate.Context, String> resolver) {
        register(name, resolver);
        groups.put(normalize(name), group);
    }

    private static String orQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }
}
