package com.logvex.addon.chatlog.core;

import com.logvex.addon.common.Messages;

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
        for (ChatEntry entry : entries) {
            String rendered = line(entry);
            if (body.length() + rendered.length() + 1 > DiscordEmbed.DESCRIPTION_LIMIT) {
                body.append("\n\u2026 and more");
                break;
            }
            if (body.length() > 0) body.append('\n');
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
        return decorate(embed, style, MessageType.SYSTEM);
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

    /** The webhook test card. */
    public static DiscordEmbed test(Style style, String version) {
        DiscordEmbed embed = new DiscordEmbed()
            .title("\u2705 Webhook test")
            .description("Log Client can reach this webhook. Embeds are working.")
            .color(EmbedColor.GREEN);
        if (version != null && !version.isBlank()) embed.field("Version", version, true);
        return decorate(embed, style, MessageType.SYSTEM);
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
