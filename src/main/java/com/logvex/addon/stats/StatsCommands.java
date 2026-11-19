package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers the chat commands that report live server and player figures.
 *
 * <p>Kept separate from {@link com.logvex.addon.assistant.core.AssistantBrain} so the command table
 * and the wording are testable with no Minecraft types, and so a new figure is one entry here rather
 * than a new branch in the brain. The brain owns the decision record; this class only decides which
 * text answers a verb.
 *
 * <p>Every command is individually switchable. A command that is turned off returns empty, so the
 * assistant falls through to its other sources (memory commands, the local AI) exactly as if the
 * verb had never existed.
 */
public final class StatsCommands {
    /** Which commands may answer. */
    public record Options(
        boolean enabled,
        String ping,
        String tps,
        String stats,
        String coords,
        String health,
        String fps,
        String server,
        String time,
        String players,
        String uptime,
        String world,
        String speed,
        String who,
        ServerStats.Detail detail,
        String separator
    ) {
        public static final Options DEFAULTS = new Options(
            true, "ping", "tps", "stats", "coords", "health", "fps", "server", "time",
            "players", "uptime", "world", "speed", "who", ServerStats.Detail.BRIEF, " | ");

        /** The form without the player-name list, for callers that predate it. */
        public Options(boolean enabled, String ping, String tps, String stats, String coords,
                       String health, String fps, String server, String time, String players,
                       String uptime, String world, String speed,
                       ServerStats.Detail detail, String separator) {
            this(enabled, ping, tps, stats, coords, health, fps, server, time, players, uptime,
                world, speed, "who", detail, separator);
        }

        /**
         * The form for callers that set the verbs they expose and let the rest keep their defaults.
         *
         * <p>Only {@code who} is settable in the module, so naming it here avoids repeating the four
         * default verbs at the call site, where a typo would silently rename a command.
         */
        public Options(boolean enabled, String ping, String tps, String stats, String coords,
                       String health, String fps, String server, String time, String who,
                       ServerStats.Detail detail, String separator) {
            this(enabled, ping, tps, stats, coords, health, fps, server, time,
                "players", "uptime", "world", "speed", who, detail, separator);
        }

        /** The nine-field form, for callers that predate the extra figures. */
        public Options(boolean enabled, String ping, String tps, String stats, String coords,
                       String health, String fps, String server, String time,
                       ServerStats.Detail detail, String separator) {
            this(enabled, ping, tps, stats, coords, health, fps, server, time,
                "players", "uptime", "world", "speed", "who", detail, separator);
        }

        public Options {
            if (ping == null || ping.isBlank()) ping = "ping";
            if (tps == null || tps.isBlank()) tps = "tps";
            if (stats == null || stats.isBlank()) stats = "stats";
            if (coords == null || coords.isBlank()) coords = "coords";
            if (health == null || health.isBlank()) health = "health";
            if (fps == null || fps.isBlank()) fps = "fps";
            if (server == null || server.isBlank()) server = "server";
            if (time == null || time.isBlank()) time = "time";
            if (players == null || players.isBlank()) players = "players";
            if (uptime == null || uptime.isBlank()) uptime = "uptime";
            if (world == null || world.isBlank()) world = "world";
            if (speed == null || speed.isBlank()) speed = "speed";
            if (who == null || who.isBlank()) who = "who";
            if (detail == null) detail = ServerStats.Detail.BRIEF;
            if (separator == null) separator = " | ";
        }

        /**
         * The configured verb for each figure, keyed by canonical command name.
         *
         * <p>This is what lets a user rename {@code ping} to {@code latency} without losing the
         * permission rule that governs {@code ping}: the renamed verb is an alias, and aliases are
         * resolved to the canonical name before any permission check.
         */
        public java.util.Map<String, String> verbs() {
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            map.put("ping", ping);
            map.put("tps", tps);
            map.put("stats", stats);
            map.put("coords", coords);
            map.put("health", health);
            map.put("fps", fps);
            map.put("server", server);
            map.put("time", time);
            map.put("players", players);
            map.put("uptime", uptime);
            map.put("world", world);
            map.put("speed", speed);
            map.put("who", who);
            return java.util.Map.copyOf(map);
        }
    }

    private StatsCommands() {
    }

    /**
     * The answer to {@code body} (the text after the command prefix), or empty when no stats command
     * matches or the matching one is switched off.
     */
    public static Optional<String> answer(String body, ServerStats stats, Options options) {
        return answer(body, stats, options, -1, "");
    }

    /**
     * The answer to {@code body}, with the requester's own ping.
     *
     * <p>{@code ping} is about the person who asked, not about this client: the tab list carries
     * every visible player's latency, so the assistant can answer "your ping" for whoever spoke.
     * When that figure is not available the command declines rather than reporting someone else's
     * number as if it were theirs.
     *
     * @param requesterPing the requester's latency in milliseconds, or a negative value when unknown
     * @param requester     the requester's name, for the wording; may be blank
     */
    public static Optional<String> answer(String body, ServerStats stats, Options options,
                                          int requesterPing, String requester) {
        if (body == null || body.isBlank()) return Optional.empty();
        Options opts = options == null ? Options.DEFAULTS : options;
        if (!opts.enabled()) return Optional.empty();
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;

        String verb = body.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (matches(verb, opts.ping())) {
            return Optional.of(ping(s, requesterPing, requester));
        }
        if (matches(verb, opts.tps())) return Optional.of(tps(s));
        if (matches(verb, opts.stats())) return Optional.of(stats(s, opts));
        if (matches(verb, opts.coords())) return Optional.of(coords(s));
        if (matches(verb, opts.health())) return Optional.of(health(s));
        if (matches(verb, opts.fps())) return Optional.of(fps(s));
        if (matches(verb, opts.server())) return Optional.of(server(s));
        if (matches(verb, opts.time())) return Optional.of(time(s));
        if (matches(verb, opts.players())) return Optional.of(players(s));
        if (matches(verb, opts.who())) return Optional.of(who(s));
        if (matches(verb, opts.uptime())) return Optional.of(uptime(s));
        if (matches(verb, opts.world())) return Optional.of(world(s));
        if (matches(verb, opts.speed())) return Optional.of(speed(s));
        return Optional.empty();
    }

    /** The verbs this class can answer, for the assistant's help text. */
    public static String usage(Options options) {
        Options o = options == null ? Options.DEFAULTS : options;
        return o.ping() + ", " + o.tps() + ", " + o.stats() + ", " + o.coords() + ", "
            + o.health() + ", " + o.fps() + ", " + o.server() + ", " + o.time() + ", "
            + o.players() + ", " + o.uptime() + ", " + o.world() + ", " + o.speed();
    }

    private static boolean matches(String verb, String name) {
        return verb.equalsIgnoreCase(name.trim());
    }

    /** Latency and a plain-language verdict, for the local client. */
    public static String ping(ServerStats s) {
        return ping(s, -1, "");
    }

    /**
     * The requester's latency and a plain-language verdict.
     *
     * <p>Falls back to the local client's own figure only when the requester <em>is</em> the local
     * player. For anyone else an unknown figure is reported as unknown: attributing this client's
     * latency to another player would be a fabricated measurement.
     */
    public static String ping(ServerStats s, int requesterPing, String requester) {
        if (!s.inWorld()) return "Not connected.";
        int latency = requesterPing;
        if (latency < 0 && (requester == null || requester.isBlank())) {
            // No identity given: the caller is the local client, whose own figure is on the stats.
            latency = s.latency();
        }
        if (latency < 0) {
            String who = requester == null || requester.isBlank() ? "Your" : requester + "'s";
            return who + " ping is not available yet.";
        }
        String verdict = verdictFor(latency);
        String who = requester == null || requester.isBlank() ? "Your" : requester + "'s";
        return who + " ping is " + latency + " ms (" + verdict + ").";
    }

    /** The verdict for one latency reading, on the same thresholds the stats table uses. */
    private static String verdictFor(int latency) {
        if (latency < 0) return "unknown";
        if (latency > 400) return "poor";
        if (latency > 200) return "fair";
        return "good";
    }

    /** The server's tick rate, with a warning when it is behind. */
    public static String tps(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        if (s.tps() <= 0f) return "TPS is not available yet.";
        String base = "TPS " + ServerStats.formatTps(s.tps()) + " / 20";
        if (s.lagging()) return base + " (lagging).";
        return base + ".";
    }

    /** The full set of figures, in the requested detail. */
    public static String stats(ServerStats s, Options options) {
        if (!s.inWorld()) return "Not connected.";
        Options o = options == null ? Options.DEFAULTS : options;
        return s.join(o.detail(), o.separator()) + ".";
    }

    /** The local player's position, rounded to whole blocks. */
    public static String coords(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return String.format(Locale.ROOT, "XYZ %.0f %.0f %.0f (%s).",
            s.x(), s.y(), s.z(), s.dimension().isBlank() ? "?" : s.dimension());
    }

    /** The local player's health and hunger. */
    public static String health(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return String.format(Locale.ROOT, "Health %.1f/%.0f, food %d.",
            s.health(), s.maxHealth(), s.food());
    }

    /** The client's own frame rate. */
    public static String fps(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return s.fps() + " FPS.";
    }

    /** The server name and the session length. */
    public static String server(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        String name = s.server().isBlank() ? "single-player" : s.server();
        return name + ", up " + ServerStats.formatUptime(s.uptimeMs()) + ".";
    }

    /** The in-game clock. */
    public static String time(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        return "World time " + ServerStats.formatWorldTime(s.worldTime()) + ".";
    }

    /**
     * How many players the client can see.
     *
     * <p>The client only knows the tab list, so this is the visible count and the wording says so.
     * Reporting it as the server's total would be inventing a figure the client does not have.
     */
    public static String players(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        int visible = s.players();
        if (visible <= 0) return "No players visible in the tab list.";
        return visible + (visible == 1 ? " player" : " players") + " visible.";
    }

    /**
     * The names of the players the client can see, or a sentence saying why there are none.
     *
     * <p>Names come from the tab list, so a player who has never spoken is still listed. On a server
     * that hides players from the tab list this shows fewer names than are really online; the wording
     * says "visible" so the figure is never read as the server's own count.
     */
    public static String who(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        List<String> online = s.onlinePlayers();
        if (online.isEmpty()) {
            int count = s.players();
            if (count > 0) {
                return count + (count == 1 ? " player is" : " players are")
                    + " online, but the server hides their names.";
            }
            return "No players visible in the tab list.";
        }
        return online.size() + (online.size() == 1 ? " player" : " players") + ": "
            + String.join(", ", online) + ".";
    }

    /**
     * The visible players grouped into lines that fit {@code width}.
     *
     * <p>Returned as a list rather than one string so the caller can put them in an embed field and
     * have each line stay inside the block's border.
     */
    public static List<String> whoLines(ServerStats s, int width) {
        if (!s.inWorld()) return List.of("Not connected.");
        List<String> online = s.onlinePlayers();
        if (online.isEmpty()) return List.of(who(s));
        int limit = Math.max(16, width);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String name : online) {
            if (line.length() > 0 && line.length() + 2 + name.length() > limit) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(", ");
            line.append(name);
        }
        if (line.length() > 0) out.add(line.toString());
        return List.copyOf(out);
    }

    /** How long this session has been connected. */
    public static String uptime(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        long uptime = s.uptimeMs();
        if (uptime <= 0) return "Session length is not available yet.";
        return "Session up " + ServerStats.formatUptime(uptime) + ".";
    }

    /** The dimension and world the player is in. */
    public static String world(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        String dimension = s.dimension().isBlank() ? "?" : s.dimension();
        String world = s.world().isBlank() ? "?" : s.world();
        return "World " + world + " (" + dimension + ").";
    }

    /** The local player's horizontal movement speed. */
    public static String speed(ServerStats s) {
        if (!s.inWorld()) return "Not connected.";
        if (s.speed() <= 0d) return "Not moving.";
        return String.format(Locale.ROOT, "Speed %.2f blocks/s.", s.speed());
    }
}
