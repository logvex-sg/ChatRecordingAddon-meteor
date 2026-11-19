package com.logvex.addon.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A snapshot of everything the client can observe about the server and the local player.
 *
 * <p>Only figures the client can actually measure are here: the tick rate derived from incoming
 * keep-alive traffic, the round-trip latency from the player list, the visible player count, the
 * session clock, the world the player is standing in and the player's own state. There is no
 * server-side CPU or memory figure, because a client cannot see one — reporting a guess as fact
 * would be worse than omitting it.
 *
 * <p>The type is a plain record with no Minecraft dependency so the formatting, the thresholds and
 * the health verdicts are unit-testable without a game.
 */
public record ServerStats(
    boolean inWorld,
    String server,
    float tps,
    int latency,
    int players,
    int maxPlayers,
    long uptimeMs,
    String dimension,
    String world,
    String biome,
    long worldTime,
    float health,
    float maxHealth,
    int food,
    double x,
    double y,
    double z,
    String facing,
    double speed,
    int fps,
    long usedMemoryMb,
    long maxMemoryMb
) {
    /** Tick rate a healthy server runs at. */
    public static final float TARGET_TPS = 20.0f;

    /** Below this the server is visibly lagging. */
    public static final float LAG_TPS = 15.0f;

    /** Not connected: every measurement is absent rather than zero, so callers can omit them. */
    public static final ServerStats OFFLINE = new ServerStats(
        false, "", 0f, -1, 0, 0, 0L, "", "", "", 0L, 0f, 0f, 0, 0, 0, 0, "", 0.0, 0, 0L, 0L);

    public ServerStats {
        if (server == null) server = "";
        if (dimension == null) dimension = "";
        if (world == null) world = "";
        if (biome == null) biome = "";
        if (facing == null) facing = "";
        if (tps < 0f) tps = 0f;
        if (tps > TARGET_TPS) tps = TARGET_TPS;
        if (health < 0f) health = 0f;
        if (maxHealth < 0f) maxHealth = 0f;
        if (food < 0) food = 0;
        if (players < 0) players = 0;
        if (maxPlayers < 0) maxPlayers = 0;
        if (speed < 0.0) speed = 0.0;
        if (fps < 0) fps = 0;
        if (uptimeMs < 0L) uptimeMs = 0L;
    }

    /** True when the tick rate is low enough that the server is visibly lagging. */
    public boolean lagging() {
        return inWorld && tps > 0f && tps < LAG_TPS;
    }

    /** Milliseconds per tick implied by the measured tick rate, or -1 when unknown. */
    public float mspt() {
        if (tps <= 0f) return -1f;
        return 1000f / tps;
    }

    /** Latency as a bar out of five, the way the vanilla player list shows it. -1 when unknown. */
    public int latencyBars() {
        if (latency < 0) return -1;
        if (latency < 75) return 5;
        if (latency < 150) return 4;
        if (latency < 300) return 3;
        if (latency < 600) return 2;
        return 1;
    }

    /** A one-word verdict for the connection and the server's tick rate. */
    public String verdict() {
        if (!inWorld) return "offline";
        if (tps > 0f && tps < LAG_TPS) return "lagging";
        if (latency < 0) return "unknown";
        if (latency > 400) return "poor";
        if (latency > 200) return "fair";
        return "good";
    }

    /**
     * The individual measurements as label/value pairs, in display order.
     *
     * <p>Used by the {@code !stats} command and the webhook card, so both show the same figures in
     * the same order.
     */
    public List<Line> lines(Detail detail) {
        Detail d = detail == null ? Detail.FULL : detail;
        List<Line> out = new ArrayList<>(14);
        if (!inWorld) {
            out.add(new Line("Status", "not connected"));
            return out;
        }
        out.add(new Line("Server", server.isBlank() ? "single-player" : server));
        out.add(new Line("TPS", formatTps(tps)));
        out.add(new Line("MSPT", mspt() < 0f ? "n/a" : String.format(Locale.ROOT, "%.1f ms", mspt())));
        out.add(new Line("Ping", latency < 0 ? "n/a" : latency + " ms"));
        if (d == Detail.FULL) {
            out.add(new Line("Players",
                maxPlayers > 0 ? players + "/" + maxPlayers : String.valueOf(players)));
        }
        out.add(new Line("Uptime", formatUptime(uptimeMs)));
        if (d == Detail.FULL) {
            out.add(new Line("Dimension", dimension.isBlank() ? "?" : dimension));
            out.add(new Line("World", world.isBlank() ? "?" : world));
            out.add(new Line("Biome", biome.isBlank() ? "?" : biome));
            out.add(new Line("World time", formatWorldTime(worldTime)));
            out.add(new Line("Health", String.format(Locale.ROOT, "%.1f/%.0f", health, maxHealth)));
            out.add(new Line("Food", String.valueOf(food)));
            out.add(new Line("Position", String.format(Locale.ROOT, "%.0f, %.0f, %.0f", x, y, z)));
            out.add(new Line("Facing", facing.isBlank() ? "?" : facing));
            out.add(new Line("Speed", String.format(Locale.ROOT, "%.2f b/s", speed)));
            out.add(new Line("FPS", String.valueOf(fps)));
            out.add(new Line("Memory", maxMemoryMb > 0
                ? usedMemoryMb + "/" + maxMemoryMb + " MB" : usedMemoryMb + " MB"));
        }
        return out;
    }

    /** Renders {@link #lines} as one line of {@code label value} pairs separated by {@code separator}. */
    public String join(Detail detail, String separator) {
        List<Line> lines = lines(detail);
        StringBuilder out = new StringBuilder(lines.size() * 16);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(separator);
            Line line = lines.get(i);
            out.append(line.label()).append(' ').append(line.value());
        }
        return out.toString();
    }

    /** One label/value pair. */
    public record Line(String label, String value) {
    }

    /** How much of {@link #lines} to include. */
    public enum Detail {
        /** Connection, tick rate and latency only — the short form for chat. */
        BRIEF,
        /** Everything the client can measure. */
        FULL
    }

    /** Formats a tick rate the way players read it: one decimal, never above 20. */
    public static String formatTps(float tps) {
        float clamped = Math.min(TARGET_TPS, Math.max(0f, tps));
        return String.format(Locale.ROOT, "%.1f", clamped);
    }

    /** A compact duration such as {@code 2h 14m} or {@code 47s}. */
    public static String formatUptime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) return hours + "h " + minutes + "m";
        if (minutes > 0L) return minutes + "m " + (seconds % 60L) + "s";
        return seconds + "s";
    }

    /** The in-game clock as a 24-hour time, from the world's tick-of-day. */
    public static String formatWorldTime(long worldTime) {
        long dayTicks = Math.floorMod(worldTime, 24000L);
        long totalMinutes = (dayTicks * 1440L / 24000L + 6L * 60L) % 1440L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        boolean day = dayTicks < 12300L || dayTicks > 23850L;
        return String.format(Locale.ROOT, "%02d:%02d %s", hours, minutes, day ? "day" : "night");
    }

    /**
     * The compass direction a yaw points at.
     *
     * <p>Minecraft yaw is 0 facing south and increases clockwise seen from above, so south is
     * {@code [315,45)}, west {@code [45,135)}, north {@code [135,225)} and east the rest.
     */
    public static String facingName(float yaw) {
        float normalized = yaw % 360f;
        if (normalized < 0f) normalized += 360f;
        if (normalized >= 315f || normalized < 45f) return "south";
        if (normalized < 135f) return "west";
        if (normalized < 225f) return "north";
        return "east";
    }
}
