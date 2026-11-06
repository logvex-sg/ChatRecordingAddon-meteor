package com.logvex.addon.chatlog.core;

import com.logvex.addon.common.Messages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a short, human-readable summary of a batch of chat entries.
 *
 * <p>This is pure text processing so it can be unit tested without a Minecraft runtime. It is used
 * both by the {@code chat-digest} module (on demand and on a timer) and by the webhook report path.
 * The output is deliberately bounded: a digest of a busy hour must not become another wall of chat.
 */
public final class ChatDigest {
    /** A per-player line in the digest. */
    public record PlayerLine(String name, long messages, long joins, long leaves, long deaths) {
        public PlayerLine {
            if (name == null) name = "";
        }
    }

    /** The computed summary. */
    public record Summary(
        long fromTimestamp,
        long toTimestamp,
        long totalMessages,
        int uniquePlayers,
        Map<MessageType, Long> byType,
        List<PlayerLine> topPlayers,
        List<String> keywordHighlights,
        long joinEvents,
        long leaveEvents,
        long deathEvents
    ) {
        public long count(MessageType type) {
            Long value = byType.get(type);
            return value == null ? 0 : value;
        }

        public boolean isEmpty() {
            return totalMessages == 0;
        }
    }

    private ChatDigest() {
    }

    /** Summarises {@code entries}, keeping the {@code topPlayerCount} most active players. */
    public static Summary summarise(List<ChatEntry> entries, int topPlayerCount) {
        long from = Long.MAX_VALUE;
        long to = 0;
        long total = 0;
        long joins = 0;
        long leaves = 0;
        long deaths = 0;

        Map<MessageType, Long> byType = new EnumMap<>(MessageType.class);
        Map<String, long[]> perPlayer = new LinkedHashMap<>();
        List<String> keywordHighlights = new ArrayList<>();

        if (entries != null) {
            for (ChatEntry entry : entries) {
                if (entry == null) continue;
                total++;
                if (entry.timestamp() < from) from = entry.timestamp();
                if (entry.timestamp() > to) to = entry.timestamp();
                byType.merge(entry.type(), 1L, Long::sum);
                switch (entry.type()) {
                    case JOIN -> joins++;
                    case LEAVE -> leaves++;
                    case DEATH -> deaths++;
                    default -> {
                    }
                }
                if (!entry.sender().isBlank()) {
                    long[] counts = perPlayer.computeIfAbsent(entry.sender(), k -> new long[4]);
                    counts[0]++;
                    if (entry.type() == MessageType.JOIN) counts[1]++;
                    if (entry.type() == MessageType.LEAVE) counts[2]++;
                    if (entry.type() == MessageType.DEATH) counts[3]++;
                }
            }
        }

        List<PlayerLine> top = new ArrayList<>(perPlayer.size());
        for (Map.Entry<String, long[]> e : perPlayer.entrySet()) {
            long[] c = e.getValue();
            top.add(new PlayerLine(e.getKey(), c[0], c[1], c[2], c[3]));
        }
        top.sort(Comparator.comparingLong(PlayerLine::messages).reversed()
            .thenComparing(PlayerLine::name, String.CASE_INSENSITIVE_ORDER));
        if (topPlayerCount > 0 && top.size() > topPlayerCount) {
            top = new ArrayList<>(top.subList(0, topPlayerCount));
        }

        if (from == Long.MAX_VALUE) from = 0;

        return new Summary(from, to, total, perPlayer.size(), byType, List.copyOf(top),
            List.copyOf(keywordHighlights), joins, leaves, deaths);
    }

    /** Renders a summary as the lines a module or the GUI would print. */
    public static List<String> lines(Summary summary, long nowMillis) {
        List<String> out = new ArrayList<>();
        if (summary == null || summary.isEmpty()) {
            out.add("No messages in this period.");
            return out;
        }

        String window = Messages.duration(Math.max(0, summary.toTimestamp() - summary.fromTimestamp()));
        out.add(Messages.plural(summary.totalMessages(), "message") + " from "
            + Messages.plural(summary.uniquePlayers(), "player") + " over " + window + ".");
        out.add("Joins " + summary.joinEvents() + ", leaves " + summary.leaveEvents()
            + ", deaths " + summary.deathEvents() + ".");

        List<String> types = new ArrayList<>();
        for (MessageType type : MessageType.values()) {
            long count = summary.count(type);
            if (count > 0) types.add(type + " " + Messages.number(count));
        }
        if (!types.isEmpty()) out.add(String.join(", ", types) + ".");

        if (!summary.topPlayers().isEmpty()) {
            StringBuilder sb = new StringBuilder("Most active: ");
            List<String> names = new ArrayList<>();
            for (PlayerLine line : summary.topPlayers()) {
                names.add(line.name() + " (" + line.messages() + ")");
            }
            sb.append(String.join(", ", names)).append('.');
            out.add(sb.toString());
        }

        return out;
    }

    /** A compact one-line form suitable for a HUD or a notification title. */
    public static String headline(Summary summary) {
        if (summary == null || summary.isEmpty()) return "No messages.";
        return Messages.number(summary.totalMessages()) + " messages, "
            + summary.uniquePlayers() + " players, " + summary.joinEvents() + " joins";
    }

    /** True when {@code text} mentions a player name in the digest, for search. */
    public static boolean mentions(Summary summary, String name) {
        if (summary == null || name == null || name.isBlank()) return false;
        String needle = name.trim().toLowerCase(Locale.ROOT);
        for (PlayerLine line : summary.topPlayers()) {
            if (line.name().toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
}
