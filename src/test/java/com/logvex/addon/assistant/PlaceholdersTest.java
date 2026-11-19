package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.Placeholders;
import com.logvex.addon.assistant.core.ResponseTemplate;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The placeholder registry: built-ins, extension, and tolerance of unknown tokens. */
class PlaceholdersTest {

    private static final long NOW = 1_700_000_000_000L;

    private static ServerStats stats() {
        return new ServerStats(
            true,
            "mc.example.net",
            19.6f,
            42,
            12,
            60,
            7_500_000L,
            "minecraft:overworld",
            "overworld",
            "plains",
            6_000L,
            17.5f,
            20f,
            15,
            120.4,
            64.0,
            -35.9,
            "north",
            5.43,
            144,
            512L,
            4096L
        );
    }

    private static ResponseTemplate.Context context(ServerStats stats) {
        return new ResponseTemplate.Context("Notch", "hi", "mc.example.net", new AssistantMemory(),
            NOW, stats);
    }

    @Test
    @DisplayName("server and connection placeholders use the live figures")
    void serverPlaceholders() {
        ResponseTemplate.Context ctx = context(stats());
        assertEquals("19.6", ResponseTemplate.expand("{tps}", ctx));
        assertEquals("51.0", ResponseTemplate.expand("{mspt}", ctx));
        assertEquals("42 ms", ResponseTemplate.expand("{ping}", ctx));
        assertEquals("12", ResponseTemplate.expand("{players}", ctx));
        assertEquals("2h 5m", ResponseTemplate.expand("{uptime}", ctx));
        assertEquals("good", ResponseTemplate.expand("{verdict}", ctx));
    }

    @Test
    @DisplayName("world placeholders resolve dimension, world, biome, clock and facing")
    void worldPlaceholders() {
        ResponseTemplate.Context ctx = context(stats());
        assertEquals("minecraft:overworld", ResponseTemplate.expand("{dimension}", ctx));
        assertEquals("overworld", ResponseTemplate.expand("{world}", ctx));
        assertEquals("plains", ResponseTemplate.expand("{biome}", ctx));
        assertEquals("north", ResponseTemplate.expand("{facing}", ctx));
        assertTrue(ResponseTemplate.expand("{worldtime}", ctx).contains(":"));
    }

    @Test
    @DisplayName("player placeholders resolve health, hunger and each coordinate")
    void playerPlaceholders() {
        ResponseTemplate.Context ctx = context(stats());
        assertEquals("17.5/20", ResponseTemplate.expand("{health}", ctx));
        assertEquals("15", ResponseTemplate.expand("{hunger}", ctx));
        assertEquals("120", ResponseTemplate.expand("{x}", ctx));
        assertEquals("64", ResponseTemplate.expand("{y}", ctx));
        assertEquals("-36", ResponseTemplate.expand("{z}", ctx));
        assertEquals("120, 64, -36", ResponseTemplate.expand("{coords}", ctx));
        assertEquals("5.43", ResponseTemplate.expand("{speed}", ctx));
        assertEquals("144", ResponseTemplate.expand("{fps}", ctx));
    }

    @Test
    @DisplayName("time and date come from the context clock, not the wall clock")
    void timePlaceholders() {
        ResponseTemplate.Context ctx = context(stats());
        // Derived from the same formatters so the test does not depend on the machine's time zone.
        assertEquals(com.logvex.addon.common.Messages.clock(NOW), ResponseTemplate.expand("{time}", ctx));
        assertEquals(com.logvex.addon.common.Messages.dateTime(NOW),
            ResponseTemplate.expand("{date}", ctx));
    }

    @Test
    @DisplayName("figures the client cannot see resolve to n/a instead of a fake number")
    void offlineFiguresAreAbsent() {
        ResponseTemplate.Context offline = new ResponseTemplate.Context("Notch", "hi", "",
            new AssistantMemory(), NOW);
        assertEquals("n/a", ResponseTemplate.expand("{tps}", offline));
        assertEquals("n/a", ResponseTemplate.expand("{mspt}", offline));
        assertEquals("n/a", ResponseTemplate.expand("{ping}", offline));
        assertEquals("offline", ResponseTemplate.expand("{verdict}", offline));
        assertEquals("?", ResponseTemplate.expand("{biome}", offline));
    }

    @Test
    @DisplayName("unknown placeholders survive untouched, including malformed braces")
    void unknownTokensPreserved() {
        ResponseTemplate.Context ctx = context(stats());
        assertEquals("{nope}", ResponseTemplate.expand("{nope}", ctx));
        assertEquals("a { b", ResponseTemplate.expand("a { b", ctx));
        assertEquals("{}", ResponseTemplate.expand("{}", ctx));
    }

    @Test
    @DisplayName("placeholders are case-insensitive and tolerate surrounding spaces")
    void caseAndWhitespaceInsensitive() {
        ResponseTemplate.Context ctx = context(stats());
        assertEquals("19.6", ResponseTemplate.expand("{TPS}", ctx));
        assertEquals("19.6", ResponseTemplate.expand("{ Tps }", ctx));
        assertEquals("42 ms", ResponseTemplate.expand("{ Ping }", ctx));
    }

    @Test
    @DisplayName("a caller can register a new placeholder without touching the shared registry")
    void extensionIsLocalToTheCopy() {
        Placeholders custom = Placeholders.standard().copy();
        custom.register("mood", ctx -> "grumpy");

        assertEquals("grumpy", custom.expand("{mood}", context(stats())));
        // The shared registry never learned the new name, so it passes it through unchanged.
        assertEquals("{mood}", ResponseTemplate.expand("{mood}", context(stats())));
    }

    @Test
    @DisplayName("a registered name can be replaced and a family can be added")
    void overrideAndFamily() {
        Placeholders custom = Placeholders.standard().copy();
        custom.register("tps", ctx -> "plenty");
        custom.registerFamily("team:", (argument, ctx) -> argument.toUpperCase());

        ResponseTemplate.Context ctx = context(stats());
        assertEquals("plenty", custom.expand("{tps}", ctx));
        assertEquals("RED", custom.expand("{team:red}", ctx));
        // A matched family with a blank argument still resolves; the resolver decides the value.
        assertEquals("", custom.expand("{team:}", ctx));
    }

    @Test
    @DisplayName("the registry advertises its own names for documentation and help text")
    void namesAreDiscoverable() {
        Placeholders registry = Placeholders.standard();
        assertTrue(registry.names().contains("player"));
        assertTrue(registry.names().contains("tps"));
        assertTrue(registry.names().contains("biome"));
        assertTrue(registry.families().contains("fact:"));
    }
}
