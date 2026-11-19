package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.PlayerView;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;
import com.logvex.addon.stats.StatusOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live status commands as the chat layer sees them.
 *
 * <p>The unit tests in {@code stats} cover the rendering; these cover the routing, which is where a
 * status readout could leak. A local answer must arrive as a notification and never as chat, and a
 * command that describes the player must be refused for anyone but the local player.
 */
class LiveStatusDispatchTest {

    private static ServerStats live() {
        return new ServerStats(
            true, "mc.example.net", "mc.example.net:25565", false,
            19.6f, 42, 12, 60, 4_320_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L, 128L, "clear",
            144, 512L, 4096L, List.of("Alex", "Steve"),
            new PlayerView(17.5f, 20f, 0f, 15, 0, "survival",
                120.4, 64.0, -35.9, "north", 5.43,
                "Diamond Sword", "", List.of(), List.of(), "", false));
    }

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    private static StatusCommands.Settings with(String command, StatusOutput setting) {
        Map<String, StatusOutput> commands = new LinkedHashMap<>(StatusOutput.defaultsAll());
        commands.put(command, setting);
        return new StatusCommands.Settings(true, commands);
    }

    // ------------------------------------------------------------------ delivery

    @Test
    @DisplayName("a local status answer is delivered as a notification, never as chat")
    void localAnswerIsANotification() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!status", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertTrue(decision.acts());
        assertEquals(AssistantBrain.Delivery.NOTIFY, decision.delivery(),
            "a local answer must not be spoken into public chat");
        assertTrue(decision.text().contains("mc.example.net"), decision.text());
    }

    @Test
    @DisplayName("a status command configured for chat is delivered as chat")
    void chatAnswerIsChat() {
        StatusCommands.Settings settings = with("ping",
            new StatusOutput(true, StatusOutput.Output.CHAT, "Ping: {ping}ms"));

        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!ping", 1_000L, live(), settings, -1);

        assertEquals(AssistantBrain.Delivery.CHAT, decision.delivery());
        assertTrue(decision.text().startsWith("Ping: "), decision.text());
    }

    @Test
    @DisplayName("a status answer is not remembered as a conversation exchange")
    void statusIsNotRemembered() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!tps", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertTrue(decision.acts());
        assertFalse(decision.remember(), "a status readout is not part of the conversation");
    }

    @Test
    @DisplayName("the overview asks the caller to mirror it to the admin webhook")
    void overviewCarriesAStatusCard() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!status", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertTrue(decision.hasStatusCard());
        assertEquals("status", decision.statusCard());
        assertFalse(decision.hasReport(), "an overview is not a report to the public webhook");
    }

    @Test
    @DisplayName("only the overview carries a card, so other figures do not spam the webhook")
    void otherCommandsCarryNoCard() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!tps", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertFalse(decision.hasStatusCard());
    }

    // ------------------------------------------------------------------ privacy

    @Test
    @DisplayName("a remote sender cannot run a command that describes the local player")
    void sensitiveCommandsAreLocalOnly() {
        for (String command : List.of("coords", "location", "target", "armor", "held", "effects",
            "network")) {
            AssistantBrain.Decision decision = runtime().handle(
                SenderIdentity.unverified("Stranger"), "!" + command, 1_000L, live(),
                StatusCommands.Settings.DEFAULTS, -1);

            assertTrue(decision.denied(), "!" + command + " must be refused for a stranger");
            assertFalse(decision.text().contains("120, 64"), "no position may be revealed");
        }
    }

    @Test
    @DisplayName("the local player can run every sensitive command")
    void localPlayerCanReadSensitiveFigures() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!coords", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertTrue(decision.acts());
        assertFalse(decision.denied());
        assertTrue(decision.text().contains("120, 64"), decision.text());
    }

    @Test
    @DisplayName("a server figure is answerable for anyone")
    void publicFiguresAreOpen() {
        for (String command : List.of("status", "tps", "ping", "online", "uptime", "server")) {
            AssistantBrain.Decision decision = runtime().handle(
                SenderIdentity.unverified("Stranger"), "!" + command, 1_000L, live(),
                StatusCommands.Settings.DEFAULTS, -1);

            assertNotNull(decision, "!" + command + " produced no decision");
            assertTrue(decision.acts(), "!" + command + " must answer");
            assertFalse(decision.denied(), "!" + command + " must not be refused");
        }
    }

    @Test
    @DisplayName("!ping answers with the sender's own latency when the caller supplies it")
    void pingUsesTheSendersLatency() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.unverified("Stranger"), "!ping", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, 180);

        assertTrue(decision.text().contains("180"), decision.text());
        assertFalse(decision.text().contains("42"), "this client's own latency is not the sender's");
    }

    @Test
    @DisplayName("!stats is delivered as a labelled block, not a pipe-joined line")
    void statsIsABlock() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!stats", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertTrue(decision.acts());
        assertTrue(decision.hasEmbed(), "every figure must be laid out as fields");
        String rendered = String.join("\n", decision.lines(60, null));
        assertTrue(rendered.contains("TPS"), rendered);
        assertTrue(rendered.contains("Ping"), rendered);
        assertFalse(rendered.contains(" | "), "the old separator must be gone: " + rendered);
    }

    // ------------------------------------------------------------------ switches

    @Test
    @DisplayName("a disabled command produces no decision at all")
    void disabledCommandIsIgnored() {
        StatusCommands.Settings settings = with("tps",
            new StatusOutput(false, StatusOutput.Output.LOCAL, "TPS: {tps}"));

        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!tps", 1_000L, live(), settings, -1);

        assertFalse(decision.acts(), "a switched-off command must not answer");
    }

    @Test
    @DisplayName("a missing snapshot answers rather than throwing")
    void nullStatsIsHandled() {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), "!ping", 1_000L, null,
            StatusCommands.Settings.DEFAULTS, -1);

        assertNotNull(decision);
        assertTrue(decision.acts());
    }

    @Test
    @DisplayName("an alias still reaches its command through the catalogue")
    void aliasStillAnswers() {
        AssistantBrain.Decision viaAlias = runtime().handle(
            SenderIdentity.local("Me", null), "!latency", 1_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);
        AssistantBrain.Decision viaCanonical = runtime().handle(
            SenderIdentity.local("Me", null), "!ping", 2_000L, live(),
            StatusCommands.Settings.DEFAULTS, -1);

        assertEquals(viaCanonical.text(), viaAlias.text());
    }
}
