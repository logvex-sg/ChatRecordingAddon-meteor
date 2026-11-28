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
 *   <li>{@code {fps}} / {@code {verdict}} / {@code {weather}}</li>
 *   <li>{@code {name}} / {@code {prefix}} — the assistant's own name and command prefix</li>
 *   <li>{@code {fact:key}} / {@code {count:name}} / {@code {memory}} / {@code {factcount}}</li>
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

    /**
     * The data a template can interpolate.
     *
     * @param requesterPing  the speaker's round-trip latency, or a negative value when not known
     * @param requesterKnown true when the reply is about a specific asker, so {@code {ping}} may
     *                       only answer from {@code requesterPing} and reports {@code n/a} when
     *                       that is unknown. False means nobody in particular asked — a preview or
     *                       a join greeting — and this client's own reading is the only one it
     *                       could be about.
     */
    public record Context(String player, String message, String server, AssistantMemory memory,
                          long now, ServerStats stats, String assistant, String prefix,
                          int requesterPing, boolean requesterKnown) {
        /**
         * {@code {name}} and {@code {prefix}} need the assistant's own identity, which a reply
         * cannot read from anywhere else, so the caller that owns the config passes them in.
         */
        public Context {
            if (stats == null) stats = ServerStats.OFFLINE;
            if (assistant == null) assistant = "";
            if (prefix == null) prefix = "";
        }

        /**
         * A context with no particular asker, so {@code {ping}} falls back to this client.
         *
         * <p>Kept as an overload so a caller that is not answering a specific player — a preview,
         * a join greeting, a unit test — does not have to say so.
         */
        public Context(String player, String message, String server, AssistantMemory memory,
                       long now, ServerStats stats, String assistant, String prefix) {
            this(player, message, server, memory, now, stats, assistant, prefix, -1, false);
        }

        /** A context with no live figures; every stats placeholder then resolves to {@code n/a}. */
        public Context(String player, String message, String server, AssistantMemory memory, long now) {
            this(player, message, server, memory, now, ServerStats.OFFLINE);
        }

        public Context(String player, String message, String server, AssistantMemory memory,
                       long now, ServerStats stats) {
            this(player, message, server, memory, now, stats, "", "");
        }

        public static Context of(String player, String message, AssistantMemory memory, long now) {
            return new Context(player, message, "", memory, now, ServerStats.OFFLINE);
        }

        public static Context of(String player, String message, AssistantMemory memory, long now,
                                 ServerStats stats) {
            return new Context(player, message, "", memory, now, stats);
        }

        /**
         * The same context attributed to the speaker, so {@code {ping}} and {@code {verdict}}
         * answer about whoever asked instead of about this client.
         *
         * @param latency the speaker's round-trip time, or a negative value when it could not be read
         */
        public Context forRequester(int latency) {
            return new Context(player, message, server, memory, now, stats, assistant, prefix,
                latency, true);
        }

        /** The latency to report for {@code {ping}}, or a negative value for {@code n/a}. */
        public int attributedLatency() {
            if (requesterPing >= 0) return requesterPing;
            return requesterKnown ? -1 : stats.latency();
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
