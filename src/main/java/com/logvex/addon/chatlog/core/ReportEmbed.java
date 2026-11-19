package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The embed posted when a player reports something to the moderators.
 *
 * <p>Built here rather than in the module so the shape — and the mention that goes with it — can be
 * asserted without a running client. The payload is deliberately a rich embed with a mention line
 * above it, because a report is the one thing that must actually reach a person: a bare embed with no
 * ping is easy to miss on a busy channel.
 */
public final class ReportEmbed {
    /** The longest reason a report may carry, before it is clipped. */
    public static final int REASON_LIMIT = 900;

    /** The colour a report card uses: Discord's red. */
    public static final int REPORT_COLOR = 0xED4245;

    private ReportEmbed() {
    }

    /**
     * The card for one report.
     *
     * @param style      the appearance options the webhook module is configured with
     * @param reporter   who sent the report, as the client knows them
     * @param reason     what they reported, already free of leading/trailing space
     * @param server     the server name, or blank when unknown
     * @param kind       "report" or "mod", so the reader can tell a request for help from a call
     */
    public static DiscordEmbed card(WebhookEmbeds.Style style, String reporter, String reason,
                                    String server, String kind) {
        WebhookEmbeds.Style appearance = style == null ? WebhookEmbeds.Style.defaults() : style;
        String who = reporter == null || reporter.isBlank() ? "someone" : reporter;
        String body = reason == null ? "" : reason.strip();
        if (body.length() > REASON_LIMIT) {
            body = body.substring(0, REASON_LIMIT - 1) + "\u2026";
        }
        if (body.isBlank()) body = "(no reason given)";

        List<DiscordEmbed.Field> fields = new ArrayList<>();
        fields.add(new DiscordEmbed.Field("Reported by", who, true));
        if (server != null && !server.isBlank()) {
            fields.add(new DiscordEmbed.Field("Server", server, true));
        }

        String title = "mod".equalsIgnoreCase(kind) ? "Moderator requested" : "New report";
        DiscordEmbed embed = new DiscordEmbed()
            .title(title)
            .description(body)
            .color(REPORT_COLOR)
            .timestamp(java.time.Instant.now().toString());

        for (DiscordEmbed.Field field : fields) {
            embed.field(field.name(), field.value(), field.inline());
        }
        if (appearance.footer() != null && !appearance.footer().isBlank()) {
            embed.footer(appearance.footer());
        }
        return embed;
    }

    /**
     * The mention line that goes above the card, or blank when no role is configured.
     *
     * <p>A role id in angle brackets is what Discord renders as a real ping. Anything that is not an
     * id is passed through as plain text rather than sent as a broken mention.
     *
     * <p>There is deliberately no {@code @here} fallback: the payload only allows {@code roles}, so an
     * {@code @here} would render as dead text and look like a failed ping. With no role set the card
     * is posted without a mention, and the fix is to configure one.
     */
    public static String mention(String roleId) {
        if (roleId == null || roleId.isBlank()) return "";
        String trimmed = roleId.strip();
        if (trimmed.startsWith("<@&") && trimmed.endsWith(">")) return trimmed;
        if (trimmed.matches("\\d{5,25}")) return "<@&" + trimmed + ">";
        // Anything else is not an id; use it as plain text rather than sending a broken mention.
        return trimmed;
    }

    /**
     * The full JSON body for the webhook: the mention, then the card.
     *
     * <p>Built here rather than through {@link WebhookEmbeds#payload} because that helper pins
     * {@code allowed_mentions} to an empty parse list — correct for captured chat, which must never
     * ping anyone, but exactly wrong for a report, whose whole point is to ping a person. Only
     * {@code roles} is allowed here, never {@code everyone}, so a misconfigured report cannot mention
     * the whole server.
     */
    public static String payload(String username, String roleId, DiscordEmbed embed) {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("username", username == null || username.isBlank() ? "Log Client" : username);
        String mention = mention(roleId);
        if (!mention.isBlank()) root.put("content", mention);
        java.util.List<java.util.Map<String, Object>> embeds = new java.util.ArrayList<>(1);
        if (embed != null && !embed.isEmpty()) embeds.add(embed.toMap());
        root.put("embeds", embeds);
        root.put("allowed_mentions", java.util.Map.of("parse", java.util.List.of("roles")));
        return Json.toJson(root);
    }
}
