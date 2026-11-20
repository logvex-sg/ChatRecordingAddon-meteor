package com.logvex.addon.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped status table must survive its own storage format.
 *
 * <p>Each line is {@code name = on|local|template}, and several shipped templates join figures with
 * {@code |}. A decoder that split the whole line on that character therefore cut those templates
 * short and read the tail as a command alias - which renamed the command to the fragment and made
 * it unreachable. Seven of thirty-three shipped commands were dead that way.
 */
class StatusTableIntegrityTest {

    @Test
    @DisplayName("every shipped status command still answers after a storage round trip")
    void everyCommandSurvivesTheRoundTrip() {
        Map<String, StatusOutput> decoded =
            StatusOutput.decodeAll(StatusOutput.encodeAll(StatusOutput.defaultsAll()));
        StatusCommands.Settings settings = new StatusCommands.Settings(true, decoded);

        List<String> dead = new ArrayList<>();
        for (String name : decoded.keySet()) {
            if (StatusCommands.answer(name, ServerStats.OFFLINE, settings, "Notch").isEmpty()) {
                dead.add(name);
            }
        }
        assertTrue(dead.isEmpty(), "these shipped commands no longer answer: " + dead);
    }

    @Test
    @DisplayName("a template containing the field separator round-trips unchanged")
    void templatesMayContainTheSeparator() {
        Map<String, StatusOutput> decoded =
            StatusOutput.decodeAll(StatusOutput.encodeAll(StatusOutput.defaultsAll()));

        List<String> drifted = new ArrayList<>();
        StatusOutput.defaultsAll().forEach((name, original) -> {
            StatusOutput back = decoded.get(name);
            if (!back.template().equals(original.template())) {
                drifted.add(name + ": [" + original.template() + "] -> [" + back.template() + "]");
            }
        });
        assertTrue(drifted.isEmpty(), "templates were rewritten: " + drifted);
        assertEquals("Server: {server} | TPS: {tps} | Ping: {ping}ms",
            decoded.get("status").template());
        assertEquals("", decoded.get("status").alias(), "a template tail became an alias");
    }

    @Test
    @DisplayName("an explicit alias still renames a command")
    void explicitAliasRenames() {
        StatusOutput setting = StatusOutput.decode("on|local|Ping: {ping}|alias=latency", "ping");
        assertEquals("latency", setting.alias());
        assertEquals("Ping: {ping}", setting.template());
        assertEquals("on|local|Ping: {ping}|alias=latency", setting.encode());
    }

    @Test
    @DisplayName("a legacy bare alias word is still understood")
    void legacyBareAliasStillLoads() {
        StatusOutput setting = StatusOutput.decode("on|local|Ping: {ping}|latency", "ping");
        assertEquals("latency", setting.alias());
        assertEquals("Ping: {ping}", setting.template());
    }

    @Test
    @DisplayName("a template that merely ends after a pipe keeps its text")
    void trailingPipeIsNotAnAlias() {
        StatusOutput setting = StatusOutput.decode("on|local|TPS: {tps} |", "tps");
        assertEquals("", setting.alias(), "a dangling separator is not a command name");
        assertEquals("TPS: {tps} |", setting.template());
    }

    @Test
    @DisplayName("a rename that shadows a canonical command is dropped")
    void shadowingRenameIsDropped() {
        StatusOutput setting = StatusOutput.decode("on|local|x|alias=tps", "ping");
        assertEquals("", setting.alias(), "a rename must not hijack another command");
    }

    @Test
    @DisplayName("{prefix} and {name} resolve inside a status template")
    void identityPlaceholdersResolve() {
        StatusTemplate.Context ctx = new StatusTemplate.Context("Notch", -1, "Assistant", "!");
        assertEquals("Try !help - I am Assistant",
            StatusTemplate.expand("Try {prefix}help - I am {name}", ServerStats.OFFLINE, ctx));
    }

    @Test
    @DisplayName("identity placeholders fall back rather than render blank")
    void identityPlaceholdersHaveFallbacks() {
        assertEquals("!", StatusTemplate.expand("{prefix}", ServerStats.OFFLINE, "Notch"));
        assertEquals("the assistant",
            StatusTemplate.expand("{name}", ServerStats.OFFLINE, "Notch"));
    }

    @Test
    @DisplayName("both identity placeholders are documented in the reference")
    void identityPlaceholdersAreDocumented() {
        Map<String, String> reference = StatusTemplate.reference();
        assertTrue(reference.containsKey("prefix"), "prefix missing from the reference");
        assertTrue(reference.containsKey("name"), "name missing from the reference");
    }
}
