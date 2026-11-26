package com.logvex.addon.chatlog.core;

/**
 * Where one feature's webhook cards are sent: a URL and the display name to send them under.
 *
 * <p>The routing used to be decided by asking "is the shared relay configured?" and taking its URL,
 * otherwise the module's own. That test looks at the shared URL only, so a user who set just a
 * per-feature route ({@code keywords=...}) sent nothing at all: the route was skipped because the
 * shared URL was blank, and the fallback was blank too. Resolution therefore starts from the route
 * and only then falls back, so either one alone is enough.
 *
 * @param url      the webhook URL, blank when nothing is configured
 * @param username the display name, never blank
 */
public record WebhookTarget(String url, String username) {

    /** Used when no display name is configured anywhere. */
    public static final String DEFAULT_USERNAME = "Log Client";

    public WebhookTarget {
        if (url == null) url = "";
        if (username == null || username.isBlank()) username = DEFAULT_USERNAME;
    }

    /**
     * Resolves the target for a feature.
     *
     * <p>The route wins over the shared URL because it is the more specific instruction, and the
     * module's own URL is the last resort. A blank username is replaced rather than sent, because
     * Discord rejects an empty {@code username} field.
     *
     * @param routedUrl      the feature's own route, or blank when it inherits
     * @param routedName     the display name published with the route, or blank
     * @param ownUrl         the calling module's own URL, or blank
     * @param ownName        the calling module's own display name, or blank
     */
    public static WebhookTarget resolve(String routedUrl, String routedName, String ownUrl,
                                        String ownName) {
        String url = !isBlank(routedUrl) ? routedUrl.strip() : (isBlank(ownUrl) ? "" : ownUrl.strip());
        String name = !isBlank(routedName) ? routedName.strip()
            : (isBlank(ownName) ? DEFAULT_USERNAME : ownName.strip());
        return new WebhookTarget(url, name);
    }

    /** True when a card would actually be sent. */
    public boolean configured() {
        return !url.isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
