package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !modcall} is advertised in the help table, so the assistant has to answer it.
 *
 * <p>It had no dispatch case, which meant the line fell through and the player's reason was sent to
 * public chat — the exact opposite of a private report to the moderators. These tests pin that it
 * produces a report decision and never a chat line.
 */
class ModCallCommandTest {

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    @Test
    @DisplayName("!modcall files a report rather than falling through to chat")
    void modcallFilesAReport() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!modcall griefer at spawn", 1_000L);

        assertTrue(decision.acts());
        assertTrue(decision.hasReport(), "modcall must produce a report decision");
        assertFalse(decision.delivery() == AssistantBrain.Delivery.CHAT,
            "the reason must never be broadcast to public chat");
    }

    @Test
    @DisplayName("the report carries the player's reason, not the command name")
    void reportCarriesReason() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!modcall someone is xraying", 1_000L);
        String report = decision.report();

        assertTrue(report.contains("someone is xraying"), report);
        assertTrue(report.contains("Notch"), report);
        assertFalse(report.contains("modcall"), "the command word is not part of the reason: " + report);
    }

    @Test
    @DisplayName("!modcall with no reason explains the usage instead of filing a blank report")
    void modcallNeedsAReason() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!modcall", 1_000L);

        assertFalse(decision.hasReport(), "an empty reason must not file a report");
        assertTrue(decision.text().contains("Usage"), decision.text());
    }

    @Test
    @DisplayName("!mod and !modcall agree on the report kind")
    void modAndModcallMatch() {
        AssistantBrain.Decision viaMod = runtime().handle("Notch", "!mod help me", 1_000L);
        AssistantBrain.Decision viaModcall = runtime().handle("Notch", "!modcall help me", 1_000L);

        assertTrue(viaMod.hasReport());
        assertTrue(viaModcall.hasReport());
        assertTrue(viaMod.report().startsWith("mod|"), viaMod.report());
        assertTrue(viaModcall.report().startsWith("mod|"), viaModcall.report());
    }
}