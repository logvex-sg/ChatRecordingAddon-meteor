package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.FeatureWebhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each feature can name its own webhook. The rules that matter are that an unset feature inherits
 * the shared URL — so nothing changes for a user who never touches the setting — and that a typo
 * cannot redirect a card to nothing.
 */
class FeatureWebhookTest {

    private static final String SHARED = "https://discord.com/api/webhooks/1/shared";

    @Test
    @DisplayName("an unset feature inherits the shared URL")
    void inheritsShared() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of());
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MACROS));
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MODCALL));
        assertFalse(routes.overridden(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("an override wins over the shared URL")
    void overrideWins() {
        String own = "https://discord.com/api/webhooks/2/own";
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("macros=" + own));

        assertEquals(own, routes.url(FeatureWebhook.Feature.MACROS));
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MODCALL));
        assertTrue(routes.overridden(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("an override works with no shared URL at all")
    void overrideWithoutShared() {
        String own = "https://discord.com/api/webhooks/2/own";
        FeatureWebhook routes = new FeatureWebhook("", List.of("modcall=" + own));

        assertEquals(own, routes.url(FeatureWebhook.Feature.MODCALL));
        assertTrue(routes.configured(FeatureWebhook.Feature.MODCALL));
        assertFalse(routes.configured(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("an unknown feature name is ignored rather than breaking delivery")
    void unknownFeatureIgnored() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("nonsense=https://example.com"));
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("a blank override is ignored, so the shared URL still applies")
    void blankOverrideIgnored() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("macros="));
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MACROS));
        assertFalse(routes.overridden(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("a comment line is skipped")
    void commentSkipped() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("# macros=ignored"));
        assertEquals(SHARED, routes.url(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("feature names are matched case-insensitively and trimmed")
    void namesAreNormalised() {
        String own = "https://discord.com/api/webhooks/3/own";
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of("  MACROS = " + own + "  "));
        assertEquals(own, routes.url(FeatureWebhook.Feature.MACROS));
    }

    @Test
    @DisplayName("nothing configured means nothing is sent")
    void emptyIsNotConfigured() {
        FeatureWebhook routes = new FeatureWebhook("", List.of());
        assertFalse(routes.configured(FeatureWebhook.Feature.HELP));
        assertEquals("", routes.url(FeatureWebhook.Feature.HELP));
    }

    @Test
    @DisplayName("a null feature falls back to the shared URL")
    void nullFeatureFallsBack() {
        FeatureWebhook routes = new FeatureWebhook(SHARED, List.of());
        assertEquals(SHARED, routes.url(null));
        assertNull(FeatureWebhook.Feature.of("not-a-feature"));
    }
}