package com.logvex.addon.chatlog.core;

import com.logvex.addon.common.Messages;
import com.logvex.addon.stats.ServerStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the rich embeds the webhook sends.
 *
 * <p>Every card is assembled here so the look is consistent no matter which module raised it, and so
 * the exact structure can be asserted in a unit test. The builders take plain data and an
 * {@link Style} of appearance options; nothing here touches Minecraft or the network.
 */
public final class WebhookEmbeds {
    /** Appearance options shared by every embed, mirrored from the module settings. */
    public record Style(
        String accent,
        String footer,
        boolean showTimestamp,
        boolean inlineFields,
        String authorName,
        boolean showServer
    ) {
        public static Style defaults() {
            return new Style("blurple", "Log Client", true, true, "", true);
        }

        /** The accent colour for a type, honouring an explicit override. */
        int accentFor(MessageType type) {
            if (accent != null && !accent.isBlank() && !accent.equalsIgnoreCase("auto")) {
                return EmbedColor.named(accent);
            }
            return EmbedColor.forType(type);
        }
    }

    /** Marks a description that stopped before the end of its messages. */
    public static final String MORE_MARKER = "\n\u2026 and more";

    private WebhookEmbeds() {
    }

    /** A one-line, mention-safe rendering of an entry used inside an embed description or field. */
    public static String line(ChatEntry entry) {
        if (entry == null) return "";
        String who = entry.sender().isBlank() ? entry.type().toString() : entry.sender();
        return "`" + ReportFormatting.shortTime(entry.timestamp()) + "` **" + who + "** "
            + neutralize(entry.content());
    }

    /** A keyword alert card: the matched rule, where it fired and the line that triggered it. */
    public static DiscordEmbed alert(Style style, KeywordRule rule, ChatEntry entry) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDD14 Keyword alert")
            .description(neutralize(entry.content()))
            .color(style.accentFor(MessageType.PUBLIC_CHAT));
        embed.field("Pattern", "`" + rule.pattern() + "`", true);
        embed.field("Type", entry.type().toString(), true);
        if (style.showServer()) embed.field("Server", server(entry.server()), true);
        if (!entry.sender().isBlank()) embed.field("Sender", entry.sender(), true);
        embed.field("When", ReportFormatting.time(entry.timestamp()), true);
        return decorate(embed, style, entry.type());
    }

    /**
     * A chat summary card. The messages form the description (bounded to the embed limit), and a
     * footer field carries the window and the counts, so a report of a busy hour stays readable.
     */
    public static DiscordEmbed summary(Style style, String server, List<ChatEntry> entries) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCAC Chat summary")
            .color(style.accentFor(MessageType.PUBLIC_CHAT));

        StringBuilder body = new StringBuilder();
        // Room is reserved for the marker up front. Without that the guard lets the body fill the
        // description and the marker is then clipped to a bare ellipsis, which reads as a typo.
        int room = DiscordEmbed.DESCRIPTION_LIMIT - MORE_MARKER.length();
        for (ChatEntry entry : entries) {
            String rendered = line(entry);
            int separator = body.length() > 0 ? 1 : 0;
            if (body.length() + separator + rendered.length() > room) {
                body.append(MORE_MARKER);
                break;
            }
            if (separator > 0) body.append('\n');
            body.append(rendered);
        }
        embed.description(body.toString());

        long from = Long.MAX_VALUE;
        long to = 0;
        for (ChatEntry entry : entries) {
            from = Math.min(from, entry.timestamp());
            to = Math.max(to, entry.timestamp());
        }
        if (from == Long.MAX_VALUE) from = 0;

        embed.field("Messages", Messages.number(entries.size()), true);
        embed.field("Window", Messages.duration(Math.max(0, to - from)), true);
        if (style.showServer()) embed.field("Server", server(server), true);
        return decorate(embed, style, MessageType.PUBLIC_CHAT);
    }

    /** A digest card built from the computed {@link ChatDigest.Summary}. */
    public static DiscordEmbed digest(Style style, ChatDigest.Summary summary, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCCA Chat digest")
            .color(style.accentFor(MessageType.SYSTEM));
        return decorate(figures(embed, summary, style, server), style, MessageType.SYSTEM);
    }

    /**
     * The counts, the top players and the highlights of {@code summary}, added to {@code embed}.
     *
     * <p>Shared by the digest and the requested summary so the two cards cannot report different
     * numbers for the same window.
     */
    private static DiscordEmbed figures(DiscordEmbed embed, ChatDigest.Summary summary,
                                        Style style, String server) {
        embed.field("Messages", Messages.number(summary.totalMessages()), true);
        embed.field("Players", Messages.number(summary.uniquePlayers()), true);
        embed.field("Window", Messages.duration(Math.max(0, summary.toTimestamp() - summary.fromTimestamp())), true);
        embed.field("Joins", Messages.number(summary.joinEvents()), true);
        embed.field("Leaves", Messages.number(summary.leaveEvents()), true);
        embed.field("Deaths", Messages.number(summary.deathEvents()), true);
        if (style.showServer()) embed.field("Server", server(server), true);

        if (!summary.topPlayers().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ChatDigest.PlayerLine player : summary.topPlayers()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("**").append(player.name()).append("** \u2014 ")
                    .append(player.messages()).append(" msg");
            }
            embed.field("Most active", sb.toString(), false);
        }
        if (!summary.keywordHighlights().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String highlight : summary.keywordHighlights()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('`').append(highlight).append('`');
            }
            embed.field("Highlights", sb.toString(), false);
        }
        return embed;
    }

    /**
     * A chat-summary report: the digest figures plus who asked for them.
     *
     * <p>Separate from {@link #digest} because this one is <em>requested</em>. A scheduled digest
     * arrives on its own and needs no attribution; a summary someone asked for should say who
     * asked, so a moderator reading it knows who to answer. The figures themselves are the same, so
     * the two cards cannot disagree about what happened.
     *
     * @param requestedBy the player who asked, or blank for a scheduled send
     * @param note        a short line explaining why it was sent, or blank
     */
    public static DiscordEmbed summaryReport(Style style, ChatDigest.Summary summary,
                                             String requestedBy, String note, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCCB Chat summary report")
            .color(style.accentFor(MessageType.SYSTEM));

        if (requestedBy != null && !requestedBy.isBlank()) {
            embed.field("Requested by", neutralize(requestedBy), true);
        }
        if (note != null && !note.isBlank()) {
            embed.field("Note", neutralize(note), false);
        }
        return decorate(figures(embed, summary, style, server), style, MessageType.SYSTEM);
    }

    /** A session-ended card: the totals for the stay that just finished. */
    public static DiscordEmbed session(Style style, SessionRecord record, long now) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCF6 Session summary")
            .color(style.accentFor(MessageType.SYSTEM));
        embed.field("Server", server(record.server()), true);
        embed.field("Duration", Messages.duration(record.durationMs(now)), true);
        embed.field("Messages", Messages.number(record.messages()), true);
        embed.field("Players", Messages.number(record.uniquePlayers()), true);
        embed.field("Joins", Messages.number(record.joins()), true);
        embed.field("Leaves", Messages.number(record.leaves()), true);
        embed.field("Deaths", Messages.number(record.deaths()), true);
        embed.field("Alerts", Messages.number(record.keywordAlerts()), true);
        return decorate(embed, style, MessageType.SYSTEM);
    }

    /** A player-rename card. */
    public static DiscordEmbed rename(Style style, String from, String to, long at) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83C\uDFAD Player renamed")
            .description("**" + from + "** is now known as **" + to + "**.")
            .color(style.accentFor(MessageType.PLAYER_MESSAGE));
        embed.field("Previous", from, true);
        embed.field("Current", to, true);
        embed.field("Observed", ReportFormatting.time(at), true);
        return decorate(embed, style, MessageType.PLAYER_MESSAGE);
    }

    /** A watchlist join/leave card. */
    public static DiscordEmbed presence(Style style, boolean joined, String player, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title(joined ? "\u2705 Player joined" : "\uD83D\uDEAA Player left")
            .description("**" + player + "** " + (joined ? "joined" : "left") + " the server.")
            .color(style.accentFor(joined ? MessageType.JOIN : MessageType.LEAVE));
        embed.field("Player", player, true);
        if (style.showServer()) embed.field("Server", server(server), true);
        return decorate(embed, style, joined ? MessageType.JOIN : MessageType.LEAVE);
    }

    /**
     * A macro-run card: who ran which shortcut, and the command that went out.
     *
     * <p>Shown as a card rather than a line because a macro run has three facts worth lining up —
     * the shortcut, the sender, the expanded command — and a sentence runs them together. The
     * expanded command is included on purpose: it is what the server actually received.
     */
    public static DiscordEmbed macroRun(Style style, String shortcut, String sender, String command,
                                        boolean remote, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title(remote ? "\uD83D\uDD17 Shared macro run" : "\u26A1 Macro run")
            .description("**" + shortcut + "**")
            .color(remote ? EmbedColor.PURPLE : EmbedColor.TEAL);
        embed.field("Command", "`" + neutralize(command) + "`");
        embed.field("Sender", sender == null || sender.isBlank() ? "local player" : sender, true);
        embed.field("Kind", remote ? "remote" : "local", true);
        if (style.showServer()) embed.field("Server", server(server), true);
        return decorate(embed, style, MessageType.SYSTEM);
    }

    /** The webhook test card. */
    public static DiscordEmbed test(Style style, String version) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\u2705 Webhook test")
            .description("Log Client can reach this webhook. Embeds are working.")
            .color(EmbedColor.GREEN);
        if (version != null && !version.isBlank()) embed.field("Version", version, true);
        return decorate(embed, style, MessageType.SYSTEM);
    }

    /** A mention-alert card: a watched or own name was used in chat. */
    public static DiscordEmbed mention(Style style, List<String> names, ChatEntry entry) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDD0E Mention")
            .description(neutralize(entry.content()))
            .color(EmbedColor.YELLOW);
        embed.field("Names", String.join(", ", names), true);
        if (!entry.sender().isBlank()) embed.field("Sender", entry.sender(), true);
        if (style.showServer()) embed.field("Server", server(entry.server()), true);
        embed.field("When", ReportFormatting.time(entry.timestamp()), true);
        return decorate(embed, style, MessageType.PUBLIC_CHAT);
    }

    /** A leaderboard card: the most active players in the current window. */
    public static DiscordEmbed leaderboard(Style style, List<ChatDigest.PlayerLine> players, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83C\uDFC6 Chat leaderboard")
            .color(EmbedColor.PURPLE);

        StringBuilder body = new StringBuilder();
        int rank = 1;
        for (ChatDigest.PlayerLine player : players) {
            if (body.length() > 0) body.append('\n');
            body.append("**").append(rank++).append(".** ").append(player.name())
                .append(" \u2014 ").append(Messages.number(player.messages())).append(" msg");
        }
        if (body.length() > 0) embed.description(body.toString());
        embed.field("Players", Messages.number(players.size()), true);
        if (style.showServer()) embed.field("Server", server(server), true);
        return decorate(embed, style, MessageType.SYSTEM);
    }

    /** A player-profile card built from the observed activity of one player. */
    public static DiscordEmbed profile(Style style, PlayerActivity activity, String server) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDC64 Player profile")
            .description("**" + activity.name() + "**")
            .color(EmbedColor.TEAL);
        embed.field("Messages", Messages.number(activity.messageCount()), true);
        embed.field("Joins", Messages.number(activity.joinCount()), true);
        embed.field("Leaves", Messages.number(activity.leaveCount()), true);
        embed.field("Deaths", Messages.number(activity.deathCount()), true);
        embed.field("Sessions", Messages.number(activity.sessionCount()), true);
        if (activity.firstSeen() > 0) embed.field("First seen", ReportFormatting.time(activity.firstSeen()), true);
        if (activity.lastSeen() > 0) embed.field("Last seen", ReportFormatting.time(activity.lastSeen()), true);
        if (style.showServer()) embed.field("Server", server(server), true);
        return decorate(embed, style, MessageType.PLAYER_MESSAGE);
    }

    /** A death notice card. */
    public static DiscordEmbed death(Style style, ChatEntry entry) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\u2620\uFE0F Death")
            .description(neutralize(entry.content()))
            .color(EmbedColor.RED);
        if (!entry.sender().isBlank()) embed.field("Player", entry.sender(), true);
        if (style.showServer()) embed.field("Server", server(entry.server()), true);
        embed.field("When", ReportFormatting.time(entry.timestamp()), true);
        return decorate(embed, style, MessageType.DEATH);
    }

    /**
     * A server-status card: the same figures the stats HUD and {@code !stats} show, as one clean
     * card with a verdict-coloured accent.
     */
    public static DiscordEmbed status(Style style, ServerStats stats) {
        ServerStats s = stats == null ? ServerStats.OFFLINE : stats;
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCE1 Server status")
            .description("**" + s.verdict().toUpperCase(java.util.Locale.ROOT) + "**")
            .color(s.offline() ? EmbedColor.RED
                : s.lagging() ? EmbedColor.ORANGE
                : s.latency() > 400 ? EmbedColor.YELLOW
                : EmbedColor.GREEN);

        if (s.offline()) {
            embed.field("Connection", "not connected", true);
            return decorate(embed, style, MessageType.SYSTEM);
        }

        // Built from the same list the chat readout uses, so the card and the command can never
        // disagree about a figure. The tick rate is the one field the card spells out against the
        // target, because on a card there is room to show the ceiling it is measured against.
        for (ServerStats.Line line : s.lines(ServerStats.Detail.FULL)) {
            String value = "TPS".equals(line.label())
                ? line.value() + " / " + ServerStats.formatTps(ServerStats.TARGET_TPS)
                : line.value();
            embed.field(line.label(), value, true);
        }
        return decorate(embed, style, MessageType.SYSTEM);
    }

    /** A server-announcement card. */
    public static DiscordEmbed announcement(Style style, ChatEntry entry) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\uD83D\uDCE3 Announcement")
            .description(neutralize(entry.content()))
            .color(EmbedColor.PURPLE);
        if (style.showServer()) embed.field("Server", server(entry.server()), true);
        embed.field("When", ReportFormatting.time(entry.timestamp()), true);
        return decorate(embed, style, MessageType.ANNOUNCEMENT);
    }

    /** Wraps an embed in the webhook envelope: username, embeds, and mentions disabled. */
    public static String payload(String username, List<DiscordEmbed> embeds) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("username", username == null || username.isBlank() ? "Log Client" : username);
        List<Map<String, Object>> list = new ArrayList<>(embeds.size());
        for (DiscordEmbed embed : embeds) {
            if (embed != null && !embed.isEmpty()) list.add(embed.toMap());
        }
        root.put("embeds", list);
        // Never let captured chat ping a role or @everyone.
        root.put("allowed_mentions", Map.of("parse", List.of()));
        return Json.toJson(root);
    }

    /** A single-embed convenience wrapper. */
    public static String payload(String username, DiscordEmbed embed) {
        return payload(username, List.of(embed));
    }

    private static DiscordEmbed decorate(DiscordEmbed embed, Style style, MessageType type) {
        if (style.footer() != null && !style.footer().isBlank()) embed.footer(style.footer());
        if (style.showTimestamp()) embed.timestamp(Instant.now().toString());
        if (style.authorName() != null && !style.authorName().isBlank()) {
            embed.author(style.authorName(), "");
        }
        return embed;
    }

    private static String server(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** Escapes the markdown that could break a line or ping, so captured text renders literally. */
    static String neutralize(String text) {
        if (text == null) return "";
        return text.replace("@", "@\u200B").replace("`", "\u2018").replace("\n", " ");
    }
}
