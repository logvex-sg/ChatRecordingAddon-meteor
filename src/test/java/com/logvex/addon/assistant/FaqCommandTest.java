package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.FaqCommand;
import com.logvex.addon.assistant.core.ReplyFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code !faq} command.
 *
 * <p>The command edits the same store {@code {fact:key}} reads, so a wrong answer here is visible in
 * two places at once. These tests pin the spelling of each subcommand and the properties a user
 * would notice immediately if they broke: a listing is numbered, a number means the same entry in
 * every view, a shortened list says how much it hid, and the store stops at fifty.
 */
class FaqCommandTest {

    private static AssistantMemory with(String... keyValuePairs) {
        AssistantMemory memory = new AssistantMemory();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            memory.rememberFact(keyValuePairs[i], keyValuePairs[i + 1], 1_000L);
        }
        return memory;
    }

    @Test
    @DisplayName("each documented subcommand parses to its own kind")
    void subcommandsParse() {
        assertEquals(FaqCommand.Kind.USAGE, FaqCommand.parse("").kind());
        assertEquals(FaqCommand.Kind.USAGE, FaqCommand.parse("   ").kind());
        assertEquals(FaqCommand.Kind.LIST, FaqCommand.parse("list").kind());
        assertEquals(FaqCommand.Kind.ADD, FaqCommand.parse("add spawn = the blue bed").kind());
        assertEquals(FaqCommand.Kind.REMOVE, FaqCommand.parse("remove spawn").kind());
        assertEquals(FaqCommand.Kind.VIEW, FaqCommand.parse("view spawn").kind());
        assertEquals(FaqCommand.Kind.COUNT, FaqCommand.parse("count").kind());
    }

    @Test
    @DisplayName("the aliases in the usage text all work")
    void aliasesResolve() {
        assertEquals(FaqCommand.Kind.LIST, FaqCommand.parse("ls").kind());
        assertEquals(FaqCommand.Kind.LIST, FaqCommand.parse("all").kind());
        assertEquals(FaqCommand.Kind.ADD, FaqCommand.parse("set a = b").kind());
        assertEquals(FaqCommand.Kind.ADD, FaqCommand.parse("remember a = b").kind());
        assertEquals(FaqCommand.Kind.REMOVE, FaqCommand.parse("rm a").kind());
        assertEquals(FaqCommand.Kind.REMOVE, FaqCommand.parse("delete a").kind());
        assertEquals(FaqCommand.Kind.VIEW, FaqCommand.parse("get a").kind());
        assertEquals(FaqCommand.Kind.VIEW, FaqCommand.parse("show a").kind());
        assertEquals(FaqCommand.Kind.COUNT, FaqCommand.parse("size").kind());
    }

    @Test
    @DisplayName("a bare number is a view, so !faq 30 opens entry 30")
    void bareNumberIsAView() {
        FaqCommand.Request request = FaqCommand.parse("30");
        assertEquals(FaqCommand.Kind.VIEW, request.kind());
        assertEquals("30", request.key());
    }

    @Test
    @DisplayName("a bare word is read as a key, not refused")
    void bareWordIsAView() {
        // "!faq spawn" is what a user tries first, so it must not answer with the usage block.
        FaqCommand.Request request = FaqCommand.parse("spawn");
        assertEquals(FaqCommand.Kind.VIEW, request.kind());
        assertEquals("spawn", request.key());
    }

    @Test
    @DisplayName("add splits on the first = so an answer may contain more")
    void addSplitsOnFirstEquals() {
        FaqCommand.Request request = FaqCommand.parse("add nether = x=10, z=20");
        assertEquals(FaqCommand.Kind.ADD, request.kind());
        assertEquals("nether", request.key());
        assertEquals("x=10, z=20", request.value());
    }

    // ---------------------------------------------------------------- numbering

    @Test
    @DisplayName("entries are numbered from 1 in stored order")
    void entriesAreNumbered() {
        AssistantMemory memory = with("spawn", "the blue bed", "nether", "x=10", "shop", "at spawn");
        List<FaqCommand.Entry> entries = FaqCommand.entries(memory);
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).number());
        assertEquals("spawn", entries.get(0).key());
        assertEquals(2, entries.get(1).number());
        assertEquals(3, entries.get(2).number());
    }

    @Test
    @DisplayName("!faq <number> resolves to the entry that number was printed as")
    void numberResolvesToEntry() {
        AssistantMemory memory = with("a", "1", "b", "2", "c", "3");
        assertEquals("b", FaqCommand.resolve(memory, "2").orElseThrow().key());
        assertEquals("c", FaqCommand.resolve(memory, "3").orElseThrow().key());
        assertTrue(FaqCommand.resolve(memory, "4").isEmpty(), "past the end is empty");
        assertTrue(FaqCommand.resolve(memory, "0").isEmpty(), "numbering starts at 1");
    }

    @Test
    @DisplayName("a number means the same entry in a filtered list as in the full one")
    void numbersSurviveFiltering() {
        // This is the property that makes a printed number usable: a filter must not renumber.
        AssistantMemory memory = with("spawn", "blue", "nether", "red", "spawn2", "green");
        List<FaqCommand.Entry> filtered = FaqCommand.matching(memory, "spawn");
        assertEquals(2, filtered.size());
        assertEquals(1, filtered.get(0).number(), "the first spawn keeps number 1");
        assertEquals(3, filtered.get(1).number(), "spawn2 is still entry 3");
        assertEquals("spawn2", FaqCommand.resolve(memory, "3").orElseThrow().key());
    }

    @Test
    @DisplayName("a key still resolves, and carries its number")
    void keyResolvesWithItsNumber() {
        AssistantMemory memory = with("a", "1", "b", "2");
        FaqCommand.Entry entry = FaqCommand.resolve(memory, "b").orElseThrow();
        assertEquals("b", entry.key());
        assertEquals(2, entry.number());
        assertEquals("2", entry.value());
    }

    @Test
    @DisplayName("only a plain positive number counts as a number")
    void numberParsingIsStrict() {
        assertEquals(30, FaqCommand.parseNumber("30"));
        assertEquals(30, FaqCommand.parseNumber(" 30 "));
        assertEquals(-1, FaqCommand.parseNumber("0"));
        assertEquals(-1, FaqCommand.parseNumber("-5"));
        assertEquals(-1, FaqCommand.parseNumber("3.5"));
        assertEquals(-1, FaqCommand.parseNumber("spawn"));
        assertEquals(-1, FaqCommand.parseNumber(""));
        assertEquals(-1, FaqCommand.parseNumber(null));
    }

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("a listing is numbered and says how many entries exist")
    void listingIsNumbered() {
        AssistantMemory memory = with("spawn", "the blue bed", "nether", "x=10");
        ReplyFormat.Embed shown = FaqCommand.listing(FaqCommand.matching(memory, ""), 12, "!",
            memory.facts().size());
        assertEquals(2, shown.fields().size());
        assertEquals("1", shown.fields().get(0).label());
        assertTrue(shown.fields().get(0).value().contains("spawn"), shown.fields().get(0).value());
        assertTrue(shown.footer().contains("2"), shown.footer());
    }

    @Test
    @DisplayName("a listing says how many entries it held back")
    void listingStatesWhatItHides() {
        AssistantMemory memory = new AssistantMemory();
        for (int i = 0; i < 20; i++) memory.rememberFact("key" + i, "value" + i, 1_000L);

        ReplyFormat.Embed shown = FaqCommand.listing(FaqCommand.matching(memory, ""), 5, "!",
            memory.facts().size());
        assertEquals(5, shown.fields().size(), "the limit caps the rows");
        assertTrue(shown.footer().contains("15 more"),
            "a shortened list must say how much it hid, footer was: " + shown.footer());
    }

    @Test
    @DisplayName("a filter matches the key or the answer, and an empty filter matches all")
    void filterMatchesBothHalves() {
        AssistantMemory memory = with("spawn", "the blue bed", "nether", "x=10");

        assertEquals(2, FaqCommand.matching(memory, "").size());
        assertEquals(1, FaqCommand.matching(memory, "spawn").size());
        assertEquals(1, FaqCommand.matching(memory, "blue").size(), "the answer is searchable too");
        assertEquals(0, FaqCommand.matching(memory, "nothing").size());
    }

    @Test
    @DisplayName("an empty store lists nothing rather than a broken block")
    void emptyStoreIsHandled() {
        AssistantMemory memory = new AssistantMemory();
        assertTrue(FaqCommand.matching(memory, "").isEmpty());
        ReplyFormat.Embed shown = FaqCommand.listing(List.of(), 12, "!", 0);
        assertTrue(shown.fields().isEmpty());
        assertTrue(shown.footer().contains("0"), shown.footer());
    }

    // ---------------------------------------------------------------- the cap

    @Test
    @DisplayName("the store stops at fifty entries")
    void storeStopsAtFifty() {
        assertEquals(50, FaqCommand.MAX_ENTRIES);
        FaqCommand.Request request = FaqCommand.parse("add extra = another");
        String problem = FaqCommand.addProblem(request, FaqCommand.MAX_ENTRIES, false);
        assertFalse(problem.isBlank(), "a new entry past the cap must be refused");
        assertTrue(problem.contains("full"), problem);
    }

    @Test
    @DisplayName("a full store still accepts an update to an existing entry")
    void fullStoreStillAllowsUpdates() {
        // Replacing a key does not grow the store, so a typo in entry 12 must stay fixable.
        FaqCommand.Request request = FaqCommand.parse("add spawn = corrected");
        String problem = FaqCommand.addProblem(request, FaqCommand.MAX_ENTRIES, true);
        assertEquals("", problem);
    }

    @Test
    @DisplayName("the cap is not hit one entry early")
    void capIsNotOffByOne() {
        FaqCommand.Request request = FaqCommand.parse("add key = value");
        assertEquals("", FaqCommand.addProblem(request, FaqCommand.MAX_ENTRIES - 1, false));
        assertFalse(FaqCommand.addProblem(request, FaqCommand.MAX_ENTRIES, false).isBlank());
    }

    @Test
    @DisplayName("the count block states the size against the cap")
    void countReportsAgainstTheCap() {
        String rendered = String.join("\n", ReplyFormat.render(FaqCommand.count(7, "!")));
        assertTrue(rendered.contains("7 of 50"), rendered);
        assertTrue(rendered.contains("43"), "the spare count is shown: " + rendered);
        assertTrue(rendered.contains("!faq <number>"), "the prefix is substituted: " + rendered);
        assertFalse(rendered.contains("{prefix}"), "no unsubstituted token: " + rendered);
    }

    // ---------------------------------------------------------------- rendering

    @Test
    @DisplayName("every block renders with the prefix that was passed in")
    void usageUsesTheConfiguredPrefix() {
        String rendered = String.join("\n", ReplyFormat.render(FaqCommand.usage(".")));
        assertTrue(rendered.contains(".faq add"), rendered);
        assertFalse(rendered.contains("!faq"), "no stale prefix should survive: " + rendered);
    }

    @Test
    @DisplayName("a detail block carries the entry's number in its title")
    void detailShowsTheNumber() {
        AssistantMemory memory = with("spawn", "the blue bed");
        FaqCommand.Entry entry = FaqCommand.resolve(memory, "1").orElseThrow();
        String rendered = String.join("\n", ReplyFormat.render(FaqCommand.detail(entry)));
        assertTrue(rendered.contains("#1"), rendered);
        assertTrue(rendered.contains("the blue bed"), rendered);
    }

    @Test
    @DisplayName("an entry added through the command is visible to the placeholder lookup")
    void addIsVisibleToTheFactPlaceholder() {
        // The FAQ and {fact:key} share one store; adding through one must be readable by the other.
        AssistantMemory memory = new AssistantMemory();
        FaqCommand.Request request = FaqCommand.parse("add spawn = the blue bed");
        assertTrue(memory.rememberFact(request.key(), request.value(), 1_000L));
        assertEquals("the blue bed", memory.fact("spawn").orElseThrow().value());
    }

    @Test
    @DisplayName("a missing name and a missing answer are reported separately")
    void addProblemNamesTheMissingHalf() {
        String noName = FaqCommand.addProblem(FaqCommand.parse("add = answer only"), 0, false);
        String noAnswer = FaqCommand.addProblem(FaqCommand.parse("add key only"), 0, false);
        assertFalse(noName.isBlank());
        assertFalse(noAnswer.isBlank());
        assertFalse(noName.equals(noAnswer), "the two mistakes must not share one message");
        assertEquals("", FaqCommand.addProblem(FaqCommand.parse("add key = answer"), 0, false));
    }
}
