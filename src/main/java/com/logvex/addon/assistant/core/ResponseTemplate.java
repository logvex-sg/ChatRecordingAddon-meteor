package com.logvex.addon.assistant.core;

import com.logvex.addon.stats.ServerStats;

/**
 * Expands the placeholders in a reply template.
 *
 * <p>The names and their behaviour live in {@link Placeholders}, so this class is only the stable
 * entry point: {@link #expand} keeps its signature and delegates to the shared registry. Add a
 * placeholder by registering it on the registry rather than by editing this class.
 *
 * <p>Supported placeholders (see {@link Placeholders#standard()} for the authoritative list):
 * <ul>
 *   <li>{@code {player}} — the sender of the triggering message</li>
 *   <li>{@code {message}} — the triggering message text</li>
 *   <li>{@code {time}} / {@code {date}} — the current clock time and full timestamp</li>
 *   <li>{@code {server}} — the current server name</li>
 *   <li>{@code {tps}} / {@code {mspt}} / {@code {ping}} / {@code {players}} / {@code {uptime}}</li>
 *   <li>{@code {dimension}} / {@code {world}} / {@code {biome}} / {@code {worldtime}} / {@code {facing}}</li>
 *   <li>{@code {health}} / {@code {hunger}} / {@code {x}} / {@code {y}} / {@code {z}} / {@code {coords}} / {@code {speed}}</li>
 *   <li>{@code {fps}} / {@code {verdict}}</li>
 *   <li>{@code {fact:key}} / {@code {count:name}} / {@code {memory}}</li>
 * </ul>
 *
 * <p>A token no placeholder claims is left untouched, so a literal {@code {}} or an unknown name
 * never throws and never silently disappears.
 */
public final class ResponseTemplate {
    private static final Placeholders REGISTRY = Placeholders.standard();

    private ResponseTemplate() {
    }

    /** The shared registry, so a caller can add a placeholder for the whole assistant. */
    public static Placeholders registry() {
        return REGISTRY;
    }

    /** The data a template can interpolate. */
    public record Context(String player, String message, String server, AssistantMemory memory,
                          long now, ServerStats stats) {
        public Context {
            if (stats == null) stats = ServerStats.OFFLINE;
        }

        /** A context with no live figures; every stats placeholder then resolves to {@code n/a}. */
        public Context(String player, String message, String server, AssistantMemory memory, long now) {
            this(player, message, server, memory, now, ServerStats.OFFLINE);
        }

        public static Context of(String player, String message, AssistantMemory memory, long now) {
            return new Context(player, message, "", memory, now, ServerStats.OFFLINE);
        }

        public static Context of(String player, String message, AssistantMemory memory, long now,
                                 ServerStats stats) {
            return new Context(player, message, "", memory, now, stats);
        }
    }

    /** Expands {@code template} against {@code context}. A null template yields an empty string. */
    public static String expand(String template, Context context) {
        return REGISTRY.expand(template, context);
    }

    /** The replacement for one token, or null when no placeholder claims it. */
    public static String resolve(String token, Context context) {
        return REGISTRY.resolve(token, context);
    }
}
