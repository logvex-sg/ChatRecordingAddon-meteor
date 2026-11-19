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
 * <p>Two figures deserve a note because they are the ones a user most often assumes are known:
 * <ul>
 *   <li>{@code maxPlayers} is <em>never</em> transmitted to a client, so it stays 0 unless a caller
 *       supplies it from something like a ping response. Renderers print {@code n/a}, not a guess.</li>
 *   <li>{@code tps} is measured from keep-alive spacing, so it is a client observation of the
 *       server's behaviour rather than a figure the server reported.</li>
 * </ul>
 * {@link #source(Figure)} names which of the two a figure is, so the UI can say so.
 *
 * <p>The type is a plain record with no Minecraft dependency so the formatting, the thresholds and
 * the health verdicts are unit-testable without a game.
 */
public record ServerStats(
    boolean inWorld,
    String server,
    String serverAddress,
    boolean singleplayer,
    float tps,
    int latency,
    int players,
    int maxPlayers,
    long uptimeMs,
    String dimension,
    String world,
    String biome,
    long worldTime,
    long day,
    String weather,
    int fps,
    long usedMemoryMb,
    long maxMemoryMb,
    List<String> onlinePlayers,
    PlayerView player
) {
    /** Tick rate a healthy server runs at. */
    public static final float TARGET_TPS = 20.0f;

    /** Below this the server is visibly lagging. */
    public static final float LAG_TPS = 15.0f;

    /** Not connected: every measurement is absent rather than zero, so callers can omit them. */
    public static final ServerStats OFFLINE = new ServerStats(
        false, "", "", false, 0f, -1, 0, 0, 0L, "", "", "", 0L, 0L, "", 0, 0L, 0L,
        List.of(), PlayerView.EMPTY);

    /**
     * The flat form, for callers that predate {@link PlayerView}.
     *
     * <p>Kept because the player figures used to be fields of this record and are asserted as such
     * in many tests. It builds the view, so there is still exactly one place the data lives.
     */
    public ServerStats(
        boolean inWorld, String server, float tps, int latency, int players, int maxPlayers,
        long uptimeMs, String dimension, String world, String biome, long worldTime,
        float health, float maxHealth, int food, double x, double y, double z, String facing,
        double speed, int fps, long usedMemoryMb, long maxMemoryMb, List<String> onlinePlayers
    ) {
        this(inWorld, server, "", false, tps, latency, players, maxPlayers, uptimeMs, dimension,
            world, biome, worldTime, 0L, "", fps, usedMemoryMb, maxMemoryMb, onlinePlayers,
            new PlayerView(health, maxHealth, 0f, food, 0, "", x, y, z, facing, speed, "", "",
                List.of(), List.of(), "", false));
    }

    /** The flat form without the visible player list. */
    public ServerStats(
        boolean inWorld, String server, float tps, int latency, int players, int maxPlayers,
        long uptimeMs, String dimension, String world, String biome, long worldTime,
        float health, float maxHealth, int food, double x, double y, double z, String facing,
        double speed, int fps, long usedMemoryMb, long maxMemoryMb
    ) {
        this(inWorld, server, tps, latency, players, maxPlayers, uptimeMs, dimension, world, biome,
            worldTime, health, maxHealth, food, x, y, z, facing, speed, fps, usedMemoryMb,
            maxMemoryMb, List.of());
    }

    public ServerStats {
        if (server == null) server = "";
        if (serverAddress == null) serverAddress = "";
        if (dimension == null) dimension = "";
        if (world == null) world = "";
        if (biome == null) biome = "";
        if (weather == null) weather = "";
        if (tps < 0f) tps = 0f;
        if (tps > TARGET_TPS) tps = TARGET_TPS;
        if (players < 0) players = 0;
        if (maxPlayers < 0) maxPlayers = 0;
        if (fps < 0) fps = 0;
        if (uptimeMs < 0L) uptimeMs = 0L;
        if (day < 0L) day = 0L;
        if (onlinePlayers == null) onlinePlayers = List.of();
        onlinePlayers = List.copyOf(onlinePlayers);
        if (player == null) player = PlayerView.EMPTY;
    }

    /** True when there is no world, so every measurement is absent. */
    public boolean offline() {
        return !inWorld;
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

    /** True when the player maximum is genuinely known rather than merely absent. */
    public boolean hasMaxPlayers() {
        return maxPlayers > 0;
    }

    /** The player count as {@code 12/100}, or just {@code 12} when the maximum is not known. */
    public String playersLabel() {
        if (players <= 0 && maxPlayers <= 0) return "n/a";
        return hasMaxPlayers() ? players + "/" + maxPlayers : String.valueOf(players);
    }

    /** The server as the player would name it, falling back to the address then to single-player. */
    public String serverLabel() {
        if (!server.isBlank()) return server;
        if (!serverAddress.isBlank()) return serverAddress;
        return singleplayer ? "single-player" : "n/a";
    }

    /** The weather word, or {@code n/a} when the client has not been told. */
    public String weatherLabel() {
        return weather.isBlank() ? "n/a" : weather;
    }

    /** The world day count, or {@code n/a} when no world time has been observed. */
    public String dayLabel() {
        if (!inWorld || (day <= 0L && worldTime <= 0L)) return "n/a";
        return String.valueOf(day);
    }

    /**
     * The individual measurements as label/value pairs, in display order.
     *
     * <p>Used by the {@code !stats} command and the webhook card, so both show the same figures in
     * the same order.
     */
    public List<Line> lines(Detail detail) {
        Detail d = detail == null ? Detail.FULL : detail;
        List<Line> out = new ArrayList<>(18);
        if (!inWorld) {
            out.add(new Line("Status", "not connected"));
            return out;
        }
        out.add(new Line("Server", serverLabel()));
        out.add(new Line("TPS", formatTps(tps)));
        out.add(new Line("MSPT", mspt() < 0f ? "n/a" : String.format(Locale.ROOT, "%.1f ms", mspt())));
        out.add(new Line("Ping", latency < 0 ? "n/a" : latency + " ms"));
        if (d == Detail.FULL) out.add(new Line("Players", playersLabel()));
        out.add(new Line("Uptime", formatUptime(uptimeMs)));
        if (d == Detail.FULL) {
            out.add(new Line("Dimension", dimension.isBlank() ? "?" : dimension));
            out.add(new Line("World", world.isBlank() ? "?" : world));
            out.add(new Line("Biome", biome.isBlank() ? "?" : biome));
            out.add(new Line("World time", formatWorldTime(worldTime)));
            out.add(new Line("Health", player.healthLabel()));
            out.add(new Line("Food", String.valueOf(player.food())));
            out.add(new Line("Position", player.coordsLabel()));
            out.add(new Line("Facing", player.facing().isBlank() ? "?" : player.facing()));
            out.add(new Line("Speed", String.format(Locale.ROOT, "%.2f b/s", player.speed())));
            out.add(new Line("FPS", String.valueOf(fps)));
            out.add(new Line("Memory", maxMemoryMb > 0
                ? usedMemoryMb + "/" + maxMemoryMb + " MB" : usedMemoryMb + " MB"));
        }
        return out;
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

    /** Where a figure came from, so the UI can be honest about what it is showing. */
    public enum Origin {
        /** Read straight off the client's own state. */
        CLIENT,
        /** Measured by the client from what the server sent (tick rate, latency). */
        OBSERVED,
        /** The server told the client (player list, world time, weather). */
        SERVER,
        /** The client cannot see it at all. */
        UNAVAILABLE
    }

    /** A figure whose origin a caller may want to state. */
    public enum Figure {
        TPS, MSPT, PING, PLAYERS, MAX_PLAYERS, UPTIME, FPS, MEMORY, WORLD_TIME, DAY, WEATHER,
        POSITION, BIOME, DIMENSION, HEALTH
    }

    /**
     * Where {@code figure} comes from.
     *
     * <p>This is what lets the status output distinguish "the server says 12 players" from "the
     * client counted 12 names in the tab list" — the same number with a different guarantee behind
     * it. A figure that is absent reports {@link Origin#UNAVAILABLE} regardless of its usual source.
     */
    public Origin source(Figure figure) {
        if (figure == null) return Origin.UNAVAILABLE;
        if (!inWorld) return Origin.UNAVAILABLE;
        return switch (figure) {
            case TPS -> tps > 0f ? Origin.OBSERVED : Origin.UNAVAILABLE;
            case MSPT -> mspt() > 0f ? Origin.OBSERVED : Origin.UNAVAILABLE;
            case PING -> latency >= 0 ? Origin.OBSERVED : Origin.UNAVAILABLE;
            case PLAYERS -> Origin.SERVER;
            case MAX_PLAYERS -> hasMaxPlayers() ? Origin.SERVER : Origin.UNAVAILABLE;
            case UPTIME -> uptimeMs > 0L ? Origin.CLIENT : Origin.UNAVAILABLE;
            case FPS, MEMORY -> Origin.CLIENT;
            case WORLD_TIME -> Origin.SERVER;
            case DAY -> day > 0L ? Origin.SERVER : Origin.UNAVAILABLE;
            case WEATHER -> weather.isBlank() ? Origin.UNAVAILABLE : Origin.SERVER;
            case POSITION -> player.absent() ? Origin.UNAVAILABLE : Origin.CLIENT;
            case BIOME -> biome.isBlank() ? Origin.UNAVAILABLE : Origin.SERVER;
            case DIMENSION -> dimension.isBlank() ? Origin.UNAVAILABLE : Origin.SERVER;
            case HEALTH -> player.absent() ? Origin.UNAVAILABLE : Origin.CLIENT;
        };
    }

    // ---------------------------------------------------------------- player delegates
    //
    // The player figures live in PlayerView. These keep the flat names that call sites and tests
    // already use, so moving the data did not ripple through the codebase.

    public float health() {
        return player.health();
    }

    public float maxHealth() {
        return player.maxHealth();
    }

    public int food() {
        return player.food();
    }

    public double x() {
        return player.x();
    }

    public double y() {
        return player.y();
    }

    public double z() {
        return player.z();
    }

    public String facing() {
        return player.facing();
    }

    public double speed() {
        return player.speed();
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

    /** The weather word for a rain and thunder state, as the client reports them. */
    public static String weatherName(boolean raining, boolean thundering) {
        if (thundering) return "thunder";
        return raining ? "rain" : "clear";
    }
}
