package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.ResponseTemplate;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !ping} must report the latency of the player who asked, never this client's own figure.
 *
 * <p>The bug: a client's owner sees 40ms and a visitor sees 300ms. Both type {@code !ping}, and
 * both were told 40ms, because the answer was built from the local player's tab-list entry. A
 * measurement must be attributed to the person it was taken from, so these tests pin the
 * attribution rather than the formatting.
 */
class PingAttributionTest {

    private static final long NOW = 1_700_000_000_000L;

    /** The operator's own connection: fast, and the figure that must never be borrowed. */
    private static final int LOCAL_PING = 40;

    private static ServerStats stats(int localLatency) {
        return new ServerStats(
            true, "mc.example.net", 20.0f, localLatency, 12, 60, 7_500_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L, 17.5f, 20f, 15,
            120.4, 64.0, -35.9, "north", 5.43, 144, 512L, 4096L);
    }

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.DEFAULTS.withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    // ------------------------------------------------------------ the reported bug

    @Test
    @DisplayName("a visitor's !ping reports their latency, not the client owner's")
    void visitorSeesOwnPingNotTheOperators() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.unverified("Visitor"), "!ping", NOW, stats(LOCAL_PING), null, 305);

        assertTrue(decision.acts(), "!ping must answer a remote player");
        String text = decision.text();
        assertTrue(text.contains("305"), "the visitor's own figure must be reported: " + text);
        assertFalse(text.contains(String.valueOf(LOCAL_PING)),
            "the operator's latency must not appear in a visitor's answer: " + text);
    }

    @Test
    @DisplayName("the verdict is judged from the asker's latency, not the client owner's")
    void verdictFollowsTheAsker() {
        AssistantRuntime runtime = runtime();
        // 305ms is "fair" on this client's scale; 40ms would have been "good".
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.unverified("Visitor"), "!ping", NOW, stats(LOCAL_PING), null, 305);

        assertTrue(decision.text().contains("fair"),
            "a 305ms visitor must not be told their connection is good: " + decision.text());
    }

    @Test
    @DisplayName("a visitor whose latency could not be read gets n/a, not the operator's number")
    void unknownVisitorPingIsNotFilledFromTheOperator() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.unverified("Visitor"), "!ping", NOW, stats(LOCAL_PING), null, -1);

        assertTrue(decision.acts(), "an unknown figure still answers rather than going silent");
        String text = decision.text();
        assertTrue(text.contains("n/a"), "an unreadable figure must read as n/a: " + text);
        assertFalse(text.contains(String.valueOf(LOCAL_PING)),
            "the operator's figure must not stand in for an unknown one: " + text);
    }

    @Test
    @DisplayName("two players asking in turn get two different answers")
    void eachAskerGetsTheirOwnFigure() {
        AssistantRuntime runtime = runtime();
        String slow = runtime.handle(SenderIdentity.unverified("Visitor"), "!ping", NOW,
            stats(LOCAL_PING), null, 305).text();
        String fast = runtime.handle(SenderIdentity.unverified("Regular"), "!ping", NOW + 1_000L,
            stats(LOCAL_PING), null, 62).text();

        assertTrue(slow.contains("305"), "the first asker's figure: " + slow);
        assertTrue(fast.contains("62"), "the second asker's figure: " + fast);
        assertFalse(slow.equals(fast), "two different askers must not share one answer");
    }

    // ------------------------------------------------------------ the local player

    @Test
    @DisplayName("the local player's !ping reports their own figure")
    void localPlayerSeesOwnPing() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.local("Owner", java.util.UUID.randomUUID()), "!ping", NOW,
            stats(LOCAL_PING), null, LOCAL_PING);

        assertTrue(decision.acts());
        assertTrue(decision.text().contains(String.valueOf(LOCAL_PING)),
            "the local player's own latency: " + decision.text());
    }

    @Test
    @DisplayName("the command path and the trigger path agree for the local player")
    void bothPathsAgreeForTheLocalPlayer() {
        // A caller that cannot read the tab list passes no figure. For the local player the client's
        // own reading *is* their figure, so both answering paths must fall back to it identically -
        // otherwise !ping reports one number and the shipped !ping rule another.
        AssistantRuntime withTriggers = runtime();
        AssistantBrain.Decision viaTrigger = withTriggers.handle(
            SenderIdentity.local("Owner", java.util.UUID.randomUUID()), "!ping", NOW,
            stats(LOCAL_PING), null, -1);

        AssistantRuntime withoutTriggers = new AssistantRuntime(new AssistantMemory());
        withoutTriggers.setTriggers(TriggerSet.empty());
        withoutTriggers.setConfig(AssistantConfig.DEFAULTS.withAudience(TriggerAudience.EVERYONE));
        withoutTriggers.setDuplicateFilter(false);
        AssistantBrain.Decision viaCommand = withoutTriggers.handle(
            SenderIdentity.local("Owner", java.util.UUID.randomUUID()), "!ping", NOW,
            stats(LOCAL_PING), null, -1);

        assertTrue(viaTrigger.acts() && viaCommand.acts());
        assertTrue(viaTrigger.text().contains(String.valueOf(LOCAL_PING)),
            "the trigger path must use the local reading: " + viaTrigger.text());
        assertTrue(viaCommand.text().contains(String.valueOf(LOCAL_PING)),
            "the command path must use the local reading: " + viaCommand.text());
    }

    // ------------------------------------------------------------ the underlying rule

    @Test
    @DisplayName("a preview with no asker still shows this client's figure")
    void previewFallsBackToTheClient() {
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            "", "", "mc.example.net", new AssistantMemory(), NOW, stats(LOCAL_PING));

        assertEquals("40 ms", ResponseTemplate.expand("{ping}", context),
            "a preview is about this client, so its own figure is correct there");
        assertEquals("good", ResponseTemplate.expand("{verdict}", context));
    }

    @Test
    @DisplayName("a reply attributed to an asker never borrows the client's figure")
    void attributedContextDoesNotFallBack() {
        ResponseTemplate.Context preview = new ResponseTemplate.Context(
            "Visitor", "", "mc.example.net", new AssistantMemory(), NOW, stats(LOCAL_PING));

        assertEquals("n/a", ResponseTemplate.expand("{ping}", preview.forRequester(-1)),
            "a named asker with an unreadable figure must not inherit the client's");
        assertEquals("unknown", ResponseTemplate.expand("{verdict}", preview.forRequester(-1)),
            "a verdict needs a latency to judge; unknown is the honest answer");
        assertEquals("300 ms", ResponseTemplate.expand("{ping}", preview.forRequester(300)));
    }
}
