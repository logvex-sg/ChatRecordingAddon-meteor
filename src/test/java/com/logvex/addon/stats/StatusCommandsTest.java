package com.logvex.addon.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live status commands: the catalogue, the template engine and the output modes.
 *
 * <p>The rules these tests protect are the ones a user would notice as a bug: a command that reports
 * a figure the client cannot see must say so instead of showing a plausible number, and an answer
 * configured as local must never be transmitted.
 */
class StatusCommandsTest {

    /** A connected player in a normal world. */
    private static ServerStats live() {
        return new ServerStats(
            true, "mc.example.net", "mc.example.net:25565", false,
            19.6f, 42, 12, 60, 4_320_000L,
            "minecraft:overworld", "overworld", "plains", 6_000L, 128L, "clear",
            144, 512L, 4096L, List.of("Alex", "Steve"),
            new PlayerView(17.5f, 20f, 2f, 15, 0, "survival",
                120.4, 64.0, -35.9, "north", 5.43,
                "Diamond Sword", "Shield",
                List.of("Head: Netherite Helmet"), List.of("Speed II (2m 10s)"),
                "Stone @ 12, 64, -8", false));
    }

    private static StatusCommands.Settings defaults() {
        return StatusCommands.Settings.DEFAULTS;
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    @DisplayName("every shipped template uses only placeholders the engine knows")
    void shippedTemplatesAreValid() {
        for (StatusCommand.Definition command : StatusCommand.all()) {
            assertTrue(StatusTemplate.isValid(command.template()),
                "!" + command.name() + " has an unknown placeholder: "
                    + StatusTemplate.unknownTokens(command.template()));
        }
    }

    @Test
    @DisplayName("every command answers on its own canonical name")
    void everyCommandAnswers() {
        for (StatusCommand.Definition command : StatusCommand.all()) {
            assertTrue(StatusCommands.answer(command.name(), live(), defaults(), "Alex").isPresent(),
                "!" + command.name() + " produced no answer");
        }
    }

    @Test
    @DisplayName("an alias resolves to the same answer as its canonical command")
    void aliasesMatchCanonical() {
        for (StatusCommand.Definition command : StatusCommand.all()) {
            String canonical = StatusCommands.answer(command.name(), live(), defaults(), "Alex")
                .orElseThrow().text();
            for (String alias : command.aliases()) {
                assertEquals(canonical,
                    StatusCommands.answer(alias, live(), defaults(), "Alex").orElseThrow().text(),
                    "!" + alias + " differs from !" + command.name());
            }
        }
    }

    @Test
    @DisplayName("no canonical name is also claimed as another command's alias")
    void aliasTableHasNoCollisions() {
        for (StatusCommand.Definition command : StatusCommand.all()) {
            for (String alias : command.aliases()) {
                assertFalse(StatusCommand.find(alias).isPresent(),
                    "'" + alias + "' is both an alias and a command name");
            }
        }
    }

    // ------------------------------------------------------------------ output

    @Test
    @DisplayName("!status summarises the server and the connection")
    void statusOverview() {
        String text = StatusCommands.answer("status", live(), defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(text.contains("mc.example.net"), text);
        assertTrue(text.contains("19.6"), text);
        assertTrue(text.contains("42"), text);
    }

    @Test
    @DisplayName("!tps reports the tick rate and the milliseconds per tick")
    void tpsAndMspt() {
        String text = StatusCommands.answer("tps", live(), defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(text.contains("19.6/20"), text);
        // 1000 / 19.6 is 51.0 ms, so the derived figure is reported rather than the tick rate again.
        assertTrue(text.contains("51.0"), text);
    }

    @Test
    @DisplayName("!online shows the maximum only when the server actually sent one")
    void onlineCount() {
        String withMax = StatusCommands.answer("online", live(), defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(withMax.contains("12/60"), withMax);

        ServerStats noMax = new ServerStats(true, "s", "", false, 20f, 40, 7, 0, 1_000L,
            "", "", "", 0L, 0L, "", 0, 0L, 0L, List.of(), PlayerView.EMPTY);
        String without = StatusCommands.answer("online", noMax, defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(without.contains("7"), without);
        assertTrue(without.contains("n/a"), "the absent maximum must be named, not guessed: " + without);
    }

    @Test
    @DisplayName("!coords and !location report the player's own position")
    void positionCommands() {
        assertTrue(StatusCommands.answer("coords", live(), defaults(), "Alex")
            .orElseThrow().text().contains("120, 64, -36"));
        String where = StatusCommands.answer("location", live(), defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(where.contains("plains"), where);
        assertTrue(where.contains("north"), where);
    }

    @Test
    @DisplayName("!health shows absorption only when the player has some")
    void healthWithAbsorption() {
        String text = StatusCommands.answer("health", live(), defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(text.contains("17.5/20"), text);
        assertTrue(text.contains("+2.0"), "absorption must be shown when present: " + text);
    }

    @Test
    @DisplayName("!gamemode and !weather report what the server told the client")
    void serverFigures() {
        assertTrue(StatusCommands.answer("gamemode", live(), defaults(), "Alex")
            .orElseThrow().text().contains("survival"));
        assertTrue(StatusCommands.answer("weather", live(), defaults(), "Alex")
            .orElseThrow().text().contains("clear"));
    }

    @Test
    @DisplayName("!uptime reports the session length")
    void uptime() {
        // 4_320_000 ms is 1h 12m.
        assertTrue(StatusCommands.answer("uptime", live(), defaults(), "Alex")
            .orElseThrow().text().contains("1h 12m"));
    }

    @Test
    @DisplayName("!target reports what the player is looking at")
    void target() {
        assertTrue(StatusCommands.answer("target", live(), defaults(), "Alex")
            .orElseThrow().text().contains("Stone @ 12, 64, -8"));
    }

    // ------------------------------------------------------------------ missing data

    @Test
    @DisplayName("a disconnected client answers with n/a rather than zero")
    void offlineReportsUnavailable() {
        String text = StatusCommands.answer("ping", ServerStats.OFFLINE, defaults(), "Alex")
            .orElseThrow().text();
        assertTrue(text.contains("n/a"), text);
        assertFalse(text.contains("0ms"), "a zero would read as a measurement: " + text);
    }

    @Test
    @DisplayName("a figure the server withholds is named as unavailable")
    void missingFiguresAreNamed() {
        assertEquals("n/a", StatusTemplate.value("maxplayers", ServerStats.OFFLINE,
            StatusTemplate.Context.EMPTY));
        assertEquals("n/a", StatusTemplate.value("biome", ServerStats.OFFLINE,
            StatusTemplate.Context.EMPTY));
        assertEquals("n/a", StatusTemplate.value("armor", ServerStats.OFFLINE,
            StatusTemplate.Context.EMPTY));
    }

    @Test
    @DisplayName("an unknown placeholder is left as written, so a typo is visible")
    void unknownPlaceholderIsKept() {
        assertEquals("x {nope} y",
            StatusTemplate.expand("x {nope} y", live(), "Alex"));
        assertFalse(StatusTemplate.isValid("TPS {tps} {nope}"));
        assertEquals(List.of("nope"), StatusTemplate.unknownTokens("TPS {tps} {nope}"));
    }

    // ------------------------------------------------------------------ requester attribution

    @Test
    @DisplayName("!ping answers with the requester's own latency, not this client's")
    void pingBelongsToTheRequester() {
        ServerStats mine = live();
        assertEquals("42", StatusTemplate.value("ping", mine,
            new StatusTemplate.Context("Alex", -1)));
        assertEquals("180", StatusTemplate.value("ping", mine,
            new StatusTemplate.Context("Stranger", 180)),
            "the tab list figure for the sender must win over this client's own reading");
    }

    @Test
    @DisplayName("a preview uses this client's figures when no requester is known")
    void previewFallsBackToLocalFigures() {
        assertEquals("42", StatusTemplate.value("ping", live(),
            new StatusTemplate.Context("", -1)));
    }

    // ------------------------------------------------------------------ output modes

    @Test
    @DisplayName("a command ships local-only, so nothing is transmitted out of the box")
    void shippedCommandsAreLocal() {
        for (StatusCommand.Definition command : StatusCommand.all()) {
            StatusOutput setting = StatusOutput.defaults(command.name());
            assertTrue(setting.localOnly(),
                "!" + command.name() + " must not reach the server by default");
        }
    }

    @Test
    @DisplayName("a local answer is marked as such and never as chat")
    void localAnswerIsNotChat() {
        StatusCommands.Answer answer = StatusCommands.answer("status", live(), defaults(), "Alex")
            .orElseThrow();
        assertTrue(answer.localOnly());
        assertEquals(StatusOutput.Output.LOCAL, answer.output());
    }

    @Test
    @DisplayName("a command switched to chat reports the chat mode")
    void chatModeIsHonoured() {
        Map<String, StatusOutput> commands = new java.util.LinkedHashMap<>(StatusOutput.defaultsAll());
        commands.put("ping", new StatusOutput(true, StatusOutput.Output.CHAT, "Ping: {ping}ms"));
        StatusCommands.Settings settings = new StatusCommands.Settings(true, commands);

        StatusCommands.Answer answer = StatusCommands.answer("ping", live(), settings, "Alex")
            .orElseThrow();
        assertEquals(StatusOutput.Output.CHAT, answer.output());
        assertFalse(answer.localOnly());
    }

    @Test
    @DisplayName("a sensitive command stays local even when it is configured for chat")
    void sensitiveOutputCannotLeaveTheClient() {
        Map<String, StatusOutput> commands = new java.util.LinkedHashMap<>(StatusOutput.defaultsAll());
        commands.put("coords", new StatusOutput(true, StatusOutput.Output.CHAT, "XYZ: {x}, {y}, {z}"));
        StatusCommands.Settings settings = new StatusCommands.Settings(true, commands);

        StatusCommands.Answer answer = StatusCommands.answer("coords", live(), settings, "Alex")
            .orElseThrow();
        assertTrue(answer.sensitive());
        assertTrue(answer.localOnly(),
            "a position must not be sent to the server just because chat was selected");
    }

    // ------------------------------------------------------------------ enable/disable

    @Test
    @DisplayName("a disabled command answers nothing")
    void disabledCommandIsSilent() {
        Map<String, StatusOutput> commands = new java.util.LinkedHashMap<>(StatusOutput.defaultsAll());
        commands.put("tps", new StatusOutput(false, StatusOutput.Output.LOCAL, "TPS: {tps}"));
        StatusCommands.Settings settings = new StatusCommands.Settings(true, commands);

        assertTrue(StatusCommands.answer("tps", live(), settings, "Alex").isEmpty());
    }

    @Test
    @DisplayName("the master switch turns every status command off")
    void masterSwitchOff() {
        StatusCommands.Settings off = new StatusCommands.Settings(false, null);
        assertTrue(StatusCommands.answer("status", live(), off, "Alex").isEmpty());
    }

    @Test
    @DisplayName("a command that is not a status verb is not answered here")
    void unknownVerbIsNotAnswered() {
        assertTrue(StatusCommands.answer("hello", live(), defaults(), "Alex").isEmpty());
        assertTrue(StatusCommands.answer("", live(), defaults(), "Alex").isEmpty());
    }

    // ------------------------------------------------------------------ configuration

    @Test
    @DisplayName("a settings line round-trips through encode and decode")
    void settingsRoundTrip() {
        StatusOutput original = new StatusOutput(true, StatusOutput.Output.CHAT,
            "Ping: {ping}ms", "latency");
        assertEquals(original, StatusOutput.decode(original.encode(), "ping"));
    }

    @Test
    @DisplayName("a malformed settings line falls back to the shipped default")
    void malformedLineFallsBack() {
        StatusOutput decoded = StatusOutput.decode("nonsense", "ping");
        assertEquals(StatusOutput.defaults("ping"), decoded);
    }

    @Test
    @DisplayName("an old configuration cannot resurrect a command that no longer exists")
    void unknownCommandIsIgnored() {
        Map<String, StatusOutput> decoded = StatusOutput.decodeAll(
            List.of("gone=on|chat|whatever"));
        assertTrue(decoded.containsKey("status"));
        assertFalse(decoded.containsKey("gone"));
    }

    @Test
    @DisplayName("a rename cannot hijack another command's name")
    void renameCannotShadowACommand() {
        // Asking `ping` to answer as `tps` would make two commands claim one word.
        StatusOutput decoded = StatusOutput.decode("on|local|Ping: {ping}ms|tps", "ping");
        assertFalse(decoded.renamed(), "the colliding rename must be dropped");
        assertEquals("ping", decoded.alias().isEmpty() ? "ping" : decoded.alias());
    }

    @Test
    @DisplayName("a renamed command answers on its new name and not its old one")
    void renameMovesTheVerb() {
        Map<String, StatusOutput> commands = new java.util.LinkedHashMap<>(StatusOutput.defaultsAll());
        commands.put("ping", new StatusOutput(true, StatusOutput.Output.LOCAL,
            "Ping: {ping}ms", "latency"));
        StatusCommands.Settings settings = new StatusCommands.Settings(true, commands);

        assertTrue(StatusCommands.answer("latency", live(), settings, "Alex").isPresent());
        assertTrue(StatusCommands.answer("ping", live(), settings, "Alex").isEmpty(),
            "the old word must stop resolving once the command is renamed");
    }

    @Test
    @DisplayName("the encoded table covers every command, in display order")
    void encodedTableIsComplete() {
        List<String> lines = StatusOutput.encodeAll(StatusOutput.defaultsAll());
        assertEquals(StatusCommand.all().size(), lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i).startsWith(StatusCommand.all().get(i).name() + "="), lines.get(i));
        }
    }

    // ------------------------------------------------------------------ wrapping

    @Test
    @DisplayName("a long answer wraps on spaces so it stays readable in the chat box")
    void wrapping() {
        List<String> lines = StatusCommands.wrap("one two three four five six seven", 16);
        assertTrue(lines.size() > 1);
        for (String line : lines) assertTrue(line.length() <= 16, line);
        assertEquals("one two three four five six seven", String.join(" ", lines));
    }

    @Test
    @DisplayName("a short answer is not wrapped")
    void shortAnswerIsOneLine() {
        assertEquals(List.of("short"), StatusCommands.wrap("short", 52));
        assertEquals(List.of(), StatusCommands.wrap("", 52));
    }

    // ------------------------------------------------------------------ roster

    @Test
    @DisplayName("!who names the visible players")
    void whoListsNames() {
        String text = StatusCommands.who(live());
        assertTrue(text.contains("Alex"), text);
        assertTrue(text.contains("Steve"), text);
    }

    @Test
    @DisplayName("!who says so when the server hides the names")
    void whoHidesNames() {
        ServerStats hidden = new ServerStats(true, "s", "", false, 20f, 40, 9, 0, 1_000L,
            "", "", "", 0L, 0L, "", 0, 0L, 0L, List.of(), PlayerView.EMPTY);
        String text = StatusCommands.who(hidden);
        assertTrue(text.contains("hides"), text);
        assertFalse(text.contains("9 players:"), "a hidden roster must not be presented as a list");
    }

    @Test
    @DisplayName("!who does not pretend to know anything while disconnected")
    void whoOffline() {
        assertEquals("Not connected.", StatusCommands.who(ServerStats.OFFLINE));
    }
}
