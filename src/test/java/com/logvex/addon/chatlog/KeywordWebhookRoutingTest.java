package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.FeatureWebhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyword alerts can be routed to their own webhook.
 *
 * <p>Keyword hits are the noisiest thing this addon sends — a busy word can fire dozens of cards an
 * hour — so they are the feature a user most wants out of the main channel. Every other sender
 * already had its own route; keywords did not, which meant the only options were "same channel as
 * everything" or "off".
 */
class KeywordWebhookRoutingTest {

    private static final String SHARED = "https://discord.com/api/webhooks/1/shared";
    private static final String KEYWORDS = "https://discord.com/api/webhooks/2/keywords";

    @Test
    @DisplayName("keywords can name their own webhook")
    void keywordsCanBeRouted() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("keywords=" + KEYWORDS));
        assertEquals(KEYWORDS, routes.url(FeatureWebhook.Feature.KEYWORDS));
        assertTrue(routes.overridden(FeatureWebhook.Feature.KEYWORDS));
        // And the other features are untouched by it.
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MACROS));
        assertFalse(routes.overridden(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("keywords inherit the shared webhook when not routed")
    void keywordsInherit() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of());
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.KEYWORDS));
        assertFalse(routes.overridden(FeatureWebhook.Feature.KEYWORDS));
    }

    @Test
    @DisplayName("the keywords route is parsed case-insensitively")
    void keywordsRouteIsCaseInsensitive() {
        FeatureWebhook routes = new FeatureWebhook("", List.of("KEYWORDS = " + KEYWORDS));
        assertEquals(KEYWORDS, routes.url(FeatureWebhook.Feature.KEYWORDS));
    }
}
