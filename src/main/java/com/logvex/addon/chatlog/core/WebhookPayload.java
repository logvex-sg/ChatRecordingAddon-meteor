package com.logvex.addon.chatlog.core;

import java.util.List;
import java.util.Map;

/**
 * Builds webhook request bodies.
 *
 * <p>Delivery now uses Discord <b>rich embeds</b> instead of a flat {@code content} string: a
 * keyword alert, a chat summary, a digest, a finished session and a rename each render as a coloured
 * card with a title and named fields. Embeds read far better than a paste and, unlike content, are
 * not collapsed behind a "show more" link. This class is the thin compatibility seam over
 * {@link WebhookEmbeds} so existing callers keep working.
 *
 * <p>Every payload strictly disables mentions, so captured chat can never ping a role or
 * {@code @everyone}, and no payload ever embeds the webhook URL or any account data.
 */
public final class WebhookPayload {
    /** Discord rejects content longer than 2000 characters. */
    public static final int CONTENT_LIMIT = 2000;

    private WebhookPayload() {
    }

    /** A keyword alert card. */
    public static String alert(String username, KeywordRule rule, ChatEntry entry) {
        return alert(username, rule, entry, WebhookEmbeds.Style.defaults());
    }

    /** A keyword alert card with appearance options. */
    public static String alert(String username, KeywordRule rule, ChatEntry entry, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.alert(style, rule, entry));
    }

    /** A chat summary card. */
    public static String summary(String username, String server, List<ChatEntry> entries) {
        return summary(username, server, entries, WebhookEmbeds.Style.defaults());
    }

    /** A chat summary card with appearance options. */
    public static String summary(String username, String server, List<ChatEntry> entries, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.summary(style, server, entries));
    }

    /** A digest card. */
    public static String digest(String username, ChatDigest.Summary summary, String server, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.digest(style, summary, server));
    }

    /** A finished-session card. */
    public static String session(String username, SessionRecord record, long now, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.session(style, record, now));
    }

    /** A rename card. */
    public static String rename(String username, String from, String to, long at, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.rename(style, from, to, at));
    }

    /** A watchlist presence card. */
    public static String presence(String username, boolean joined, String player, String server, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.presence(style, joined, player, server));
    }

    /** A test card. */
    public static String test(String username) {
        return test(username, "", WebhookEmbeds.Style.defaults());
    }

    /** A test card with a version and appearance options. */
    public static String test(String username, String version, WebhookEmbeds.Style style) {
        return WebhookEmbeds.payload(username, WebhookEmbeds.test(style, version));
    }

    /** The username field alone, for callers that build the payload elsewhere. */
    public static Map<String, Object> envelope(String username, List<Map<String, Object>> embeds) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("username", username == null || username.isBlank() ? "Log Client" : username);
        root.put("embeds", embeds);
        root.put("allowed_mentions", Map.of("parse", List.of()));
        return root;
    }
}
