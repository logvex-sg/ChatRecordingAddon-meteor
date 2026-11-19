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
        register("player", ctx -> Messages.player(ctx.player()));
        register("message", ctx -> ctx.message() == null ? "" : ctx.message());
        register("time", ctx -> Messages.clock(ctx.now()));
        register("date", ctx -> Messages.dateTime(ctx.now()));
        register("server", ctx -> ctx.server() == null ? "" : ctx.server());
        register("memory", ctx ->
            ctx.memory() == null ? "0" : String.valueOf(ctx.memory().facts().size()));

        // Server and connection figures.
        register("tps", ctx -> ctx.stats().tps() <= 0f
            ? "n/a" : ServerStats.formatTps(ctx.stats().tps()));
        register("mspt", ctx -> ctx.stats().mspt() < 0f
            ? "n/a" : String.format(Locale.ROOT, "%.1f", ctx.stats().mspt()));
        register("ping", ctx -> ctx.stats().latency() < 0
            ? "n/a" : ctx.stats().latency() + " ms");
        register("players", ctx -> String.valueOf(ctx.stats().players()));
        register("uptime", ctx -> ServerStats.formatUptime(ctx.stats().uptimeMs()));
        register("verdict", ctx -> ctx.stats().verdict());

        // World figures.
        register("dimension", ctx -> orQuestion(ctx.stats().dimension()));
        register("world", ctx -> orQuestion(ctx.stats().world()));
        register("biome", ctx -> orQuestion(ctx.stats().biome()));
        register("worldtime", ctx -> ServerStats.formatWorldTime(ctx.stats().worldTime()));
        register("facing", ctx -> orQuestion(ctx.stats().facing()));

        // Player figures.
        register("health", ctx -> String.format(Locale.ROOT, "%.1f/%.0f",
            ctx.stats().health(), ctx.stats().maxHealth()));
        register("hunger", ctx -> String.valueOf(ctx.stats().food()));
        register("x", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().x()));
        register("y", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().y()));
        register("z", ctx -> String.format(Locale.ROOT, "%.0f", ctx.stats().z()));
        register("coords", ctx -> String.format(Locale.ROOT, "%.0f, %.0f, %.0f",
            ctx.stats().x(), ctx.stats().y(), ctx.stats().z()));
        register("speed", ctx -> String.format(Locale.ROOT, "%.2f", ctx.stats().speed()));

        // Client figures.
        register("fps", ctx -> String.valueOf(ctx.stats().fps()));

        // Memory families.
        registerFamily("fact:", (argument, ctx) -> {
            if (ctx.memory() == null) return "";
            return ctx.memory().fact(argument).map(AssistantMemory.Fact::value).orElse("");
        });
        registerFamily("count:", (argument, ctx) -> {
            if (ctx.memory() == null) return "0";
            return String.valueOf(ctx.memory().counter(argument));
        });
    }

    private static String orQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }
}
