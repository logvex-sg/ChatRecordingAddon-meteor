package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.FeatureWebhook;
import com.logvex.addon.chatlog.core.WebhookTarget;
import com.logvex.addon.chatlog.mc.LogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a keyword alert is sent.
 *
 * <p>The bug this guards: the old code asked "is a shared relay configured?" and used its URL,
 * otherwise its own. A user who set only a keyword route therefore sent nothing at all, because the
 * shared URL was blank — the route was ignored and the fallback was empty.
 */
class WebhookTargetTest {

    private static final String ROUTED = "https://discord.com/api/webhooks/2/keywords";
    private static final String OWN = "https://discord.com/api/webhooks/3/own";
    private static final String SHARED = "https://discord.com/api/webhooks/1/shared";
    private static final String CHATLOGGER = "https://discord.com/api/webhooks/4/logger";

    @Test
    @DisplayName("the keyword route wins when it is set")
    void routeWins() {
        WebhookTarget target = WebhookTarget.resolve(ROUTED, "Relay", OWN, "Own");
        assertEquals(ROUTED, target.url());
        assertEquals("Relay", target.username());
        assertTrue(target.configured());
    }

    @Test
    @DisplayName("the route works even when no shared webhook exists")
    void routeWorksWithoutShared() {
        // This is the case the old hasRelay() check broke: a route with no shared fallback.
        WebhookTarget target = WebhookTarget.resolve(ROUTED, "", "", "Own");
        assertEquals(ROUTED, target.url(), "a keyword route must send without a shared webhook");
        assertTrue(target.configured());
        assertEquals("Own", target.username(), "a blank routed name falls back to the module's name");
    }

    @Test
    @DisplayName("the module's own webhook is used when no route is set")
    void ownUrlWhenUnrouted() {
        WebhookTarget target = WebhookTarget.resolve("", "", OWN, "Own");
        assertEquals(OWN, target.url());
        assertEquals("Own", target.username());
    }

    @Test
    @DisplayName("nothing is configured when neither is set")
    void nothingConfigured() {
        WebhookTarget target = WebhookTarget.resolve("", "", "", "");
        assertFalse(target.configured());
        assertEquals("", target.url());
    }

    @Test
    @DisplayName("a keyword route alone is enough, with no shared webhook")
    void routeAloneIsEnough() {
        // Reproduces the old bug exactly: hasRelay() was false because the shared URL was blank, so
        // the module fell back to its own blank URL and the route was never consulted.
        LogService service = LogService.get();
        try {
            service.setRelayTarget("", "");
            service.setRelayRoutes(new FeatureWebhook("", List.of("keywords=" + ROUTED)));

            assertFalse(service.hasRelay(), "the shared URL is blank in this scenario");
            assertEquals(ROUTED, service.relayUrlFor(FeatureWebhook.Feature.KEYWORDS),
                "the route must still be found when only it is configured");
            assertTrue(service.relayConfiguredFor(FeatureWebhook.Feature.KEYWORDS));
        } finally {
            service.setRelayRoutes(new FeatureWebhook("", List.of()));
        }
    }

    @Test
    @DisplayName("the chat-logger keyword webhook outranks the relay route")
    void chatLoggerOverrideWins() {
        LogService service = LogService.get();
        try {
            service.setRelayTarget(SHARED, "Relay");
            service.setRelayRoutes(new FeatureWebhook(SHARED, List.of("keywords=" + ROUTED)));
            service.setKeywordRoute(CHATLOGGER);

            // The chat-logger's own setting is the one the user just filled in, so it must win.
            assertEquals(CHATLOGGER, service.relayUrlFor(FeatureWebhook.Feature.KEYWORDS));
            // Other features are unaffected by it.
            assertEquals(SHARED, service.relayUrlFor(FeatureWebhook.Feature.MACROS));
        } finally {
            service.setKeywordRoute("");
            service.setRelayRoutes(new FeatureWebhook("", List.of()));
            service.setRelayTarget("", "");
        }
    }

    @Test
    @DisplayName("clearing the chat-logger webhook falls back to the relay route")
    void clearingFallsBackToRoute() {
        LogService service = LogService.get();
        try {
            service.setRelayTarget(SHARED, "Relay");
            service.setRelayRoutes(new FeatureWebhook(SHARED, List.of("keywords=" + ROUTED)));
            service.setKeywordRoute(CHATLOGGER);
            service.setKeywordRoute("");

            assertFalse(service.hasKeywordRoute());
            assertEquals(ROUTED, service.relayUrlFor(FeatureWebhook.Feature.KEYWORDS),
                "clearing the override must fall back to the keywords route, not to the shared URL");
        } finally {
            service.setRelayRoutes(new FeatureWebhook("", List.of()));
            service.setRelayTarget("", "");
        }
    }
}
