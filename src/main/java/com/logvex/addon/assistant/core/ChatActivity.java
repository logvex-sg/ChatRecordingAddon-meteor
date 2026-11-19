package com.logvex.addon.assistant.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counts what chat has done this session: lines seen, lines answered, and who has been talking.
 *
 * <p>Only what the client actually observed is counted. Nothing here is an estimate, so
 * {@code !activity} can never overstate how busy a server was. The counters are session-scoped and
 * cleared on a world change, because a figure that survived a reconnect would describe a different
 * server.
 *
 * <p>The per-player map is bounded by {@link #MAX_PLAYERS}: on a large server an unbounded map would
 * grow with every passer-by for no benefit, so past the cap the least recent speaker is dropped.
 */
public final class ChatActivity {
    /** How many distinct speakers to track. */
    public static final int MAX_PLAYERS = 128;

    private long seen;
    private long answered;
    private long suppressed;
    private long firstAt;
    private long lastAt;

    private final Map<String, PlayerCount> players = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> commands = new LinkedHashMap<>();

    /** One speaker's counts. */
    public record PlayerCount(String name, long messages, long firstAt, long lastAt) {
    }

    /** Records one observed chat line from {@code player}. */
    public void observe(String player, long now) {
        seen++;
        if (firstAt == 0L || now < firstAt) firstAt = now;
        lastAt = Math.max(lastAt, now);

        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) return;
        String key = name.toLowerCase(Locale.ROOT);
        PlayerCount existing = players.get(key);
        if (existing == null) {
            players.put(key, new PlayerCount(name, 1, now, now));
            while (players.size() > MAX_PLAYERS) {
                String oldest = players.keySet().iterator().next();
                players.remove(oldest);
            }
        } else {
            players.put(key, new PlayerCount(existing.name(), existing.messages() + 1,
                existing.firstAt() == 0L ? now : existing.firstAt(), now));
        }
    }

    /** Records that the assistant produced a reply. */
    public void recordReply() {
        answered++;
    }

    /** Records that a line was dropped as a duplicate. */
    public void recordSuppressed() {
        suppressed++;
    }

    /** Records that one named command was answered. */
    public void recordCommand(String command) {
        if (command == null || command.isBlank()) return;
        commands.merge(command.trim().toLowerCase(Locale.ROOT), 1L, Long::sum);
    }

    /** How many times {@code command} has been answered this session. */
    public long commandCount(String command) {
        if (command == null) return 0;
        return commands.getOrDefault(command.trim().toLowerCase(Locale.ROOT), 0L);
    }

    /** Every command that has been answered, busiest first. */
    public java.util.Map<String, Long> commands() {
        return java.util.Map.copyOf(commands);
    }

    public long seen() {
        return seen;
    }

    public long answered() {
        return answered;
    }

    public long suppressed() {
        return suppressed;
    }

    /** How many distinct players have spoken this session. */
    public int activePlayers() {
        return players.size();
    }

    public long firstAt() {
        return firstAt;
    }

    public long lastAt() {
        return lastAt;
    }

    /** The speaker with the most messages, or null when nobody has spoken. */
    public PlayerCount mostActive() {
        PlayerCount best = null;
        for (PlayerCount count : players.values()) {
            if (best == null || count.messages() > best.messages()) best = count;
        }
        return best;
    }

    /** Every tracked speaker, busiest first. */
    public java.util.List<PlayerCount> ranked() {
        java.util.List<PlayerCount> out = new java.util.ArrayList<>(players.values());
        out.sort((a, b) -> Long.compare(b.messages(), a.messages()));
        return java.util.List.copyOf(out);
    }

    /** How many messages {@code player} has sent this session. */
    public long messagesFrom(String player) {
        if (player == null) return 0;
        PlayerCount count = players.get(player.trim().toLowerCase(Locale.ROOT));
        return count == null ? 0 : count.messages();
    }

    /** Session length in milliseconds, or 0 when nothing has been seen. */
    public long sessionMs(long now) {
        if (firstAt == 0L) return 0L;
        return Math.max(0L, now - firstAt);
    }

    /** Messages per minute over the observed session, or 0 when it is too short to mean anything. */
    public double messagesPerMinute(long now) {
        long span = sessionMs(now);
        if (span < 1_000L) return 0.0;
        return seen * 60_000.0 / span;
    }

    public void reset() {
        seen = 0;
        answered = 0;
        suppressed = 0;
        firstAt = 0;
        lastAt = 0;
        players.clear();
        commands.clear();
    }
}
